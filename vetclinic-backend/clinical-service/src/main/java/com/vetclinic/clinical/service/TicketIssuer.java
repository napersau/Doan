package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.entity.QueueTicketItem;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.QueueTicketItemRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.QueueTicketIssuedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nơi DUY NHẤT tạo và đóng phiếu xếp hàng (SM-07, Q1). Luôn gọi bên trong giao dịch của người gọi, nên "thu tiền + phát phiếu"
 * là một giao dịch cục bộ: không bao giờ có "đã thu tiền mà không có phiếu" (BR-35).
 * <ul>
 *   <li>thu xong phí khám -> phiếu DOCTOR (doctor_id = Bác sĩ đã chọn hoặc null = danh sách chờ phân công);</li>
 *   <li>thu xong tiền XN / CĐHA -> phiếu phòng; dịch vụ cùng đợt khám, cùng phòng gộp vào MỘT phiếu (queue_ticket_items);</li>
 *   <li>ca cấp cứu -> phát ngay, priority 2, thu sau; ca có đủ kết quả CLS (T06) -> phiếu DOCTOR priority 1, không thu tiền.</li>
 * </ul>
 * Số phiếu = số lớn nhất trong ngày của loại hàng đợi + 1; UNIQUE (queue_type, service_date, ticket_no) chặn phát trùng
 * (người gọi thử lại khi hai quầy thu cùng lúc).
 */
@Component
@RequiredArgsConstructor
public class TicketIssuer {

    private final QueueTicketRepository ticketRepository;
    private final QueueTicketItemRepository itemRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ================================================================== phát phiếu

    /**
     * Phí khám / XN / CĐHA vừa được thu (hoặc ca cấp cứu: gọi với issuedBy = null ngay khi tiếp đón / chỉ định).
     * Bỏ qua dịch vụ không cần phiếu (làm tại phòng) và trường hợp không còn ý nghĩa xếp hàng (ca đã qua bước chờ tương ứng).
     */
    public void issueForPaidServices(PetVisit visit, Collection<VisitServiceDetail> details, UUID issuedBy) {
        int priority = visit.isEmergency() ? QueueTicket.PRIORITY_EMERGENCY : QueueTicket.PRIORITY_NORMAL;
        for (VisitServiceDetail d : details) {
            ServiceCategory category = d.getService().getCategory();
            if (category == ServiceCategory.EXAMINATION) {
                // Phí khám thu SAU khi đã khám (ca cấp cứu) thì không phát phiếu nữa; tối đa 1 phiếu DOCTOR còn hiệu lực
                if (visit.getStatus() == VisitStatus.WAITING && activeDoctorTicket(visit.getId()).isEmpty()) {
                    issue(visit, QueueType.DOCTOR, visit.getDoctorId(), priority, issuedBy);
                }
            } else if (category.isParaclinical()) {
                if (visit.getStatus() != VisitStatus.WAITING_RESULTS || d.getStatus() != ServiceDetailStatus.PENDING
                        || itemRepository.findByVisitServiceDetailId(d.getId()).isPresent()) {
                    continue;   // đã có phiếu (ca cấp cứu), đã làm / hủy, hoặc ca đã rời bước chờ kết quả
                }
                QueueType type = QueueType.ofRoom(category);
                QueueTicket ticket = ticketRepository
                        .findFirstByVisitIdAndQueueTypeAndStatus(visit.getId(), type, TicketStatus.WAITING)
                        .orElseGet(() -> issue(visit, type, null, priority, issuedBy));   // gộp dịch vụ cùng phòng vào phiếu đang chờ
                itemRepository.save(QueueTicketItem.of(ticket.getId(), d.getId()));
            }
        }
    }

    /** T06: ca có đủ kết quả cận lâm sàng -> phiếu DOCTOR ưu tiên cho Bác sĩ khám chính, không thu tiền (Q6). */
    public QueueTicket issueResultsReady(PetVisit visit) {
        return issue(visit, QueueType.DOCTOR, visit.getDoctorId(), QueueTicket.PRIORITY_RESULTS_READY, null);
    }

    private QueueTicket issue(PetVisit visit, QueueType type, UUID doctorId, int priority, UUID issuedBy) {
        LocalDate today = LocalDate.now(clock);
        Integer max = ticketRepository.findMaxTicketNo(type, today);
        QueueTicket ticket = ticketRepository.saveAndFlush(QueueTicket.builder()
                .queueType(type)
                .serviceDate(today)
                .ticketNo(max == null ? 1 : max + 1)
                .visitId(visit.getId())
                .doctorId(type == QueueType.DOCTOR ? doctorId : null)
                .priority(priority)
                .status(TicketStatus.WAITING)
                .issuedAt(clock.instant())
                .issuedBy(issuedBy)
                .build());
        // Gửi sang RabbitMQ SAU KHI commit: làm mới hàng đợi tương ứng, báo số phiếu cho chủ nuôi
        eventPublisher.publishEvent(new QueueTicketIssuedEvent(UUID.randomUUID(), clock.instant(), issuedBy, ticket.getId(),
                type.name(), ticket.getTicketNo(), visit.getId(), visit.getVisitCode(), visit.getPet().getId(),
                ticket.getDoctorId(), priority));
        return ticket;
    }

    // ================================================================== vòng đời phiếu DOCTOR (gắn với T02 / T04 / T05 / T07 / T09 / T11)

    public Optional<QueueTicket> activeDoctorTicket(Long visitId) {
        return ticketRepository.findFirstByVisitIdAndQueueTypeAndStatusIn(visitId, QueueType.DOCTOR, TicketStatus.ACTIVE);
    }

    /**
     * T02 / T07: Bác sĩ bấm "Bắt đầu" khi bệnh nhân vào phòng. Ca phải có phiếu DOCTOR đang chờ (đã nộp phí khám, ca cấp cứu,
     * hoặc phiếu ưu tiên của ca có kết quả) - thanh toán tách khỏi khám (BR-35). Phiếu -> IN_SERVICE.
     */
    public QueueTicket startDoctorTicket(Long visitId, UUID doctorId) {
        QueueTicket ticket = ticketRepository
                .findFirstByVisitIdAndQueueTypeAndStatus(visitId, QueueType.DOCTOR, TicketStatus.WAITING)
                .orElseThrow(() -> new AppException(ErrorCode.NO_QUEUE_TICKET));
        ticket.setDoctorId(doctorId);
        ticket.setStatus(TicketStatus.IN_SERVICE);
        return ticketRepository.save(ticket);
    }

    /** T04 / T05 / T09: Bác sĩ rời ca (chỉ định CLS hoặc chốt ca) -> phiếu DONE. Không có phiếu (mở lại hồ sơ - T14) thì bỏ qua. */
    public void finishDoctorTicket(Long visitId) {
        ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(visitId, QueueType.DOCTOR, TicketStatus.IN_SERVICE)
                .ifPresent(t -> {
                    t.setStatus(TicketStatus.DONE);
                    ticketRepository.save(t);
                });
    }

    /** Lễ tân đổi / gỡ Bác sĩ (Q3): phiếu DOCTOR đang chờ đi theo, GIỮ NGUYÊN số phiếu và giờ phát. */
    public void reassignDoctorTicket(Long visitId, UUID doctorId) {
        ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(visitId, QueueType.DOCTOR, TicketStatus.WAITING)
                .ifPresent(t -> {
                    t.setDoctorId(doctorId);
                    ticketRepository.save(t);
                });
    }

    /** Khách bỏ về / hủy ca (T03, T08, T11, T12): mọi phiếu còn hiệu lực của ca -> CANCELLED. */
    public void cancelActiveTickets(Long visitId) {
        for (QueueTicket t : ticketRepository.findByVisitIdAndStatusIn(visitId, TicketStatus.ACTIVE)) {
            t.setStatus(TicketStatus.CANCELLED);
            ticketRepository.save(t);
        }
    }

    // ================================================================== vòng đời phiếu phòng XN / CĐHA

    /** Phiếu phòng chứa dịch vụ này (null nếu dịch vụ chưa có phiếu: khách chưa nộp tiền, hoặc dịch vụ không cần phiếu). */
    public Optional<QueueTicket> roomTicketOf(Long detailId) {
        return itemRepository.findByVisitServiceDetailId(detailId).flatMap(i -> ticketRepository.findById(i.getTicketId()));
    }

    /** Các dịch vụ của một phiếu phòng. */
    public List<Long> detailIdsOf(Long ticketId) {
        return itemRepository.findByTicketIdIn(List.of(ticketId)).stream().map(QueueTicketItem::getVisitServiceDetailId).toList();
    }

    /**
     * Sau khi một dịch vụ của phiếu phòng hoàn tất / bị hủy (SM-07): phiếu đang chờ mà không còn dịch vụ chờ -> CANCELLED; phiếu đang
     * làm (IN_SERVICE) mà mọi dịch vụ đã xong / hủy -> DONE (đã phục vụ xong lượt này, kể cả khi dịch vụ bị hủy giữa chừng).
     */
    public void refreshRoomTicketOf(Long detailId) {
        roomTicketOf(detailId).ifPresent(ticket -> {
            if (!TicketStatus.ACTIVE.contains(ticket.getStatus())) {
                return;
            }
            List<VisitServiceDetail> details = detailRepository.findAllById(detailIdsOf(ticket.getId()));
            boolean anyPending = details.stream().anyMatch(d -> d.getStatus() == ServiceDetailStatus.PENDING);
            boolean anyOpen = details.stream().anyMatch(d -> ServiceDetailStatus.OPEN.contains(d.getStatus()));
            if (ticket.getStatus() == TicketStatus.WAITING && !anyPending) {
                ticket.setStatus(TicketStatus.CANCELLED);
            } else if (ticket.getStatus() == TicketStatus.IN_SERVICE && !anyOpen) {
                ticket.setStatus(TicketStatus.DONE);
            } else {
                return;
            }
            ticketRepository.save(ticket);
        });
    }
}
