package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.VisitServiceDetailHistory;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Các thao tác dùng chung khi đổi trạng thái đợt khám. Luôn gọi bên trong một transaction.
 * Mỗi lần đổi trạng thái đều ghi bảng pet_visit_status_histories trong cùng transaction (quy chuẩn §6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitSupport {

    private final PetVisitRepository visitRepository;
    private final PetVisitStatusHistoryRepository historyRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final VisitServiceDetailHistoryRepository detailHistoryRepository;
    private final ServiceInvoiceRepository invoiceRepository;
    private final TicketIssuer ticketIssuer;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** Khóa dòng đợt khám (SELECT ... FOR UPDATE). Bắt buộc dùng trước mọi thao tác ghi. */
    public PetVisit lock(Long visitId) {
        return visitRepository.findByIdForUpdate(visitId)
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
    }

    public void assertStatus(PetVisit visit, VisitStatus expected, String message) {
        if (visit.getStatus() != expected) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, message);
        }
    }

    /** Chỉ Bác sĩ được Lễ tân phân công mới thao tác chuyên môn trên ca này. */
    public void assertAssignedTo(PetVisit visit, Long doctorId) {
        if (visit.getDoctorId() == null || !visit.getDoctorId().equals(doctorId)) {
            throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);
        }
    }

    public void transition(PetVisit visit, VisitStatus to, Long actorId, String note) {
        VisitStatus from = visit.getStatus();
        if (!from.canTransitionTo(to)) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Không thể chuyển đợt khám từ " + from + " sang " + to);
        }
        visit.setStatus(to);
        visitRepository.save(visit);
        record(visit.getId(), from, to, actorId, note);
    }

    public void record(Long visitId, VisitStatus from, VisitStatus to, Long actorId, String note) {
        historyRepository.save(PetVisitStatusHistory.builder()
                .visitId(visitId)
                .fromStatus(from)
                .toStatus(to)
                .changedByUserId(actorId)
                .notes(note)
                .build());
    }

    /** Audit trail tiến độ một chỉ định. action: ORDERED, START_PROCESSING, SAMPLE_COLLECTED, COMPLETED, CANCELLED. */
    public void recordDetail(Long detailId, ServiceDetailStatus from, ServiceDetailStatus to, String action,
                             Long actorId, String reason) {
        detailHistoryRepository.save(VisitServiceDetailHistory.builder()
                .visitServiceDetailId(detailId)
                .fromStatus(from)
                .toStatus(to)
                .action(action)
                .changedByUserId(actorId)
                .reason(reason)
                .build());
    }

    /**
     * Lập hóa đơn RIÊNG cho một dòng chỉ định (BR-19, FR-32): đúng một hóa đơn PENDING, snapshot đơn giá và thuế suất hiện hành
     * của dịch vụ. PHẢI gọi trong CÙNG giao dịch với việc tạo dòng chỉ định. Mã SINV-YYYY-XXXX sinh từ id (không count + 1).
     */
    public ServiceInvoice createInvoice(VisitServiceDetail detail, ClinicService service) {
        ServiceInvoice invoice = invoiceRepository.saveAndFlush(ServiceInvoice.builder()
                // Mã tạm duy nhất; đổi thành mã theo id ngay bên dưới (id chỉ có sau khi INSERT)
                .invoiceCode("TMP-" + UUID.randomUUID())
                .visitServiceDetailId(detail.getId())
                .unitPrice(service.getPrice())
                .vatRate(service.getVatRate())
                .build());
        invoice.setInvoiceCode(String.format("SINV-%d-%04d", LocalDate.now(clock).getYear(), invoice.getId()));
        return invoice;
    }

    /**
     * BR-33 / EX-08 (cập nhật 17/11/2026): hủy dịch vụ thì hóa đơn của chính dịch vụ đó đổi trong CÙNG giao dịch:
     * chưa thu -> CANCELLED; đã thu và {@code refund} (phòng khám không thực hiện được dịch vụ XN / CĐHA) -> REFUNDED;
     * đã thu và khách tự bỏ / tự hủy -> giữ PAID (không hoàn). Phí khám đã thu không bao giờ hoàn (CLIN_1310).
     */
    public void settleInvoiceOfCancelled(VisitServiceDetail detail, boolean refund) {
        invoiceRepository.findByVisitServiceDetailId(detail.getId()).ifPresent(invoice -> {
            switch (invoice.getPaymentStatus()) {
                case PENDING -> {
                    invoice.setPaymentStatus(PaymentStatus.CANCELLED);
                    invoiceRepository.save(invoice);
                }
                case PAID -> {
                    if (!refund) {
                        return;   // khách tự bỏ / tự hủy: giữ PAID, không hoàn tiền
                    }
                    if (!detail.getService().getCategory().isParaclinical()) {
                        throw new AppException(ErrorCode.REFUND_NOT_ALLOWED);   // chỉ hoàn dịch vụ XN / CĐHA, không hoàn phí khám
                    }
                    invoice.setPaymentStatus(PaymentStatus.REFUNDED);
                    invoiceRepository.save(invoice);
                }
                default -> { }   // đã CANCELLED / REFUNDED: không đổi
            }
        });
    }

    /**
     * Giai đoạn 2 = ca đã từng qua WAITING_RESULTS (T04). Chỉ định cận lâm sàng chỉ được làm ở Giai đoạn 1 (T04).
     */
    public boolean isPhaseTwo(Long visitId) {
        return historyRepository.existsByVisitIdAndToStatus(visitId, VisitStatus.WAITING_RESULTS);
    }

    /**
     * Dịch vụ khám (EXAMINATION) tạo ở T01 (check-in, PENDING), IN_PROGRESS ở T02; hoàn tất (COMPLETED) khi Bác sĩ rời giai đoạn
     * khám: chỉ định cận lâm sàng (T04) hoặc chốt ca (T05 / T09). Hóa đơn của nó không đổi (đã thu lúc tiếp đón, ca cấp cứu thu sau).
     */
    public void completeExamDetails(Long visitId, Long actorId) {
        for (VisitServiceDetail detail : detailRepository.findByVisitIdOrderByIdAsc(visitId)) {
            if (detail.getService().getCategory() == ServiceCategory.EXAMINATION
                    && detail.getStatus() == ServiceDetailStatus.IN_PROGRESS) {
                detail.setStatus(ServiceDetailStatus.COMPLETED);
                detailRepository.save(detail);
                recordDetail(detail.getId(), ServiceDetailStatus.IN_PROGRESS, ServiceDetailStatus.COMPLETED, "COMPLETED", actorId, null);
            }
        }
    }

    /** Hóa đơn của nhiều dòng chỉ định theo lô (key = id dòng chỉ định). */
    public Map<Long, ServiceInvoice> invoicesOf(Collection<Long> detailIds) {
        if (detailIds.isEmpty()) {
            return Map.of();
        }
        return invoiceRepository.findByVisitServiceDetailIdIn(detailIds).stream()
                .collect(Collectors.toMap(ServiceInvoice::getVisitServiceDetailId, Function.identity()));
    }

    /**
     * T06 / Q6 (FR-44): mỗi khi một dịch vụ cận lâm sàng COMPLETED / CANCELLED, nếu không còn dịch vụ Lab / CĐHA nào PENDING hoặc
     * IN_PROGRESS thì HỆ THỐNG tự chuyển ca WAITING_RESULTS -> RESULTS_READY (lịch sử {@code changed_by_user_id = NULL}) và phát
     * PHIẾU DOCTOR ưu tiên (priority 1, không thu tiền) cho Bác sĩ khám chính: đứng trước ca thường, sau ca cấp cứu.
     * Phải gọi SAU KHI đã khóa dòng đợt khám (khóa TRƯỚC rồi mới đếm) và trong transaction READ_COMMITTED, để thấy kết quả mới nhất
     * của kỹ thuật viên phòng còn lại vừa commit; nếu đếm trước khi khóa thì hai kỹ thuật viên hoàn tất cùng lúc làm ca kẹt mãi.
     * {@code actorId} là người vừa hoàn tất dịch vụ cuối (chỉ dùng cho event, không ghi vào lịch sử trạng thái).
     */
    public void evaluateParaclinicalCompletion(PetVisit visit, Long actorId) {
        if (visit.getStatus() != VisitStatus.WAITING_RESULTS) {
            return;
        }
        long open = detailRepository.countByVisitAndCategoriesAndStatuses(
                visit.getId(), ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN);
        if (open > 0) {
            return;
        }
        Long doctorId = visit.getDoctorId();
        transition(visit, VisitStatus.RESULTS_READY, null,
                "Đã có đủ kết quả cận lâm sàng: hệ thống tự chuyển và phát phiếu ưu tiên vào hàng đợi Bác sĩ");
        // Phiếu phòng còn chờ (dịch vụ đã bị hủy hết) đóng lại; phiếu DOCTOR ưu tiên mới cho Bác sĩ khám chính
        ticketIssuer.issueResultsReady(visit);
        Long petId = visit.getPet().getId();
        eventPublisher.publishEvent(new ParaclinicalResultsReadyEvent(UUID.randomUUID(), clock.instant(), null,
                visit.getId(), visit.getVisitCode(), doctorId, petId));
        log.info("Đợt khám {} đã có đủ kết quả cận lâm sàng (RESULTS_READY), đã phát phiếu ưu tiên cho bác sĩ", visit.getVisitCode());
    }
}
