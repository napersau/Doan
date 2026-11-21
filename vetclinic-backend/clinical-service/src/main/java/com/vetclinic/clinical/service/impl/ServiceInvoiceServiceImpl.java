package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.TicketInfo;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.InvoiceAmounts;
import com.vetclinic.clinical.service.ServiceInvoiceService;
import com.vetclinic.clinical.service.TicketIssuer;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ServiceInvoiceServiceImpl implements ServiceInvoiceService {

    /** Hai quầy thu cùng lúc có thể tính trùng số phiếu (MAX + 1): UNIQUE chặn lại, thử lại tối đa chừng này lần. */
    /** Số lần thử khi nhiều quầy cùng phát một số phiếu (mỗi lần thua chỉ có một người thắng, nên cần nhiều hơn số quầy). */
    static final int MAX_TICKET_ATTEMPTS = 8;

    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final ServiceInvoiceRepository invoiceRepository;
    private final QueueTicketRepository ticketRepository;
    private final VisitSupport support;
    private final TicketIssuer ticketIssuer;
    private final VisitAssembler assembler;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ServiceInvoiceServiceImpl(PetVisitRepository visitRepository, VisitServiceDetailRepository detailRepository,
                                     ServiceInvoiceRepository invoiceRepository, QueueTicketRepository ticketRepository,
                                     VisitSupport support, TicketIssuer ticketIssuer, VisitAssembler assembler,
                                     OutboxWriter outboxWriter, Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.invoiceRepository = invoiceRepository;
        this.ticketRepository = ticketRepository;
        this.support = support;
        this.ticketIssuer = ticketIssuer;
        this.assembler = assembler;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ================================================================== bảng kê (UC-34)

    @Override
    @Transactional(readOnly = true)
    public InvoiceStatementResponse statement(Long visitId) {
        PetVisit visit = visitRepository.findById(visitId).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        return buildStatement(visit);
    }

    private InvoiceStatementResponse buildStatement(PetVisit visit) {
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        Map<Long, ServiceInvoice> invoices = support.invoicesOf(details.stream().map(VisitServiceDetail::getId).toList());
        List<ServiceInvoiceResponse> items = details.stream()
                .filter(d -> invoices.containsKey(d.getId()))
                .map(d -> toResponse(invoices.get(d.getId()), d))
                .toList();
        List<TicketInfo> tickets = ticketRepository.findByVisitIdAndStatusIn(visit.getId(), TicketStatus.ACTIVE).stream()
                .map(TicketInfo::of).toList();
        return new InvoiceStatementResponse(visit.getId(), visit.getVisitCode(), visit.getStatus(), items,
                sum(items, PaymentStatus.PENDING), sum(items, PaymentStatus.PAID), sum(items, PaymentStatus.REFUNDED), tickets);
    }

    private static BigDecimal sum(List<ServiceInvoiceResponse> items, PaymentStatus status) {
        return items.stream().filter(i -> i.paymentStatus() == status).map(ServiceInvoiceResponse::payableAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static ServiceInvoiceResponse toResponse(ServiceInvoice i, VisitServiceDetail d) {
        return new ServiceInvoiceResponse(i.getId(), i.getInvoiceCode(), d.getId(), d.getService().getId(), d.getService().getName(),
                d.getService().getCategory(), d.getQuantity(), i.getUnitPrice(), i.getVatRate(), i.getDiscountAmount(),
                InvoiceAmounts.beforeVat(d.getQuantity(), i.getUnitPrice()),
                InvoiceAmounts.vat(d.getQuantity(), i.getUnitPrice(), i.getVatRate()),
                InvoiceAmounts.payable(d.getQuantity(), i.getUnitPrice(), i.getVatRate(), i.getDiscountAmount()),
                i.getPaymentStatus(), i.getCashierId(), i.getPaidAt(), d.getService().isRequiresPrepayment());
    }

    // ================================================================== giảm giá (EX-14)

    @Override
    @Transactional
    public ServiceInvoiceResponse discount(Long invoiceId, BigDecimal discountAmount) {
        ServiceInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        VisitServiceDetail detail = detailRepository.findById(invoice.getVisitServiceDetailId())
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        requirePending(invoice);
        PetVisit visit = visitRepository.findById(detail.getVisitId()).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        // Dịch vụ thu trước giảm được ngay trước khi thu; dịch vụ làm tại phòng chỉ thu (và giảm) khi đã chốt ca
        if (!detail.getService().isRequiresPrepayment() && !isClosed(visit)) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT);
        }
        BigDecimal discount = discountAmount.setScale(2, RoundingMode.HALF_UP);
        // Trần là tiền của CHÍNH hóa đơn này (không phải tổng cả ca)
        if (discount.compareTo(InvoiceAmounts.grossWithVat(detail.getQuantity(), invoice.getUnitPrice(), invoice.getVatRate())) > 0) {
            throw new AppException(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT);
        }
        invoice.setDiscountAmount(discount);
        invoiceRepository.saveAndFlush(invoice);   // @Version: hai người cùng sửa một hóa đơn thì người đến sau nhận SYS_1005
        return toResponse(invoice, detail);
    }

    private static void requirePending(ServiceInvoice invoice) {
        if (invoice.getPaymentStatus() == PaymentStatus.PAID || invoice.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_CANCELLED);
        }
    }

    private static boolean isClosed(PetVisit visit) {
        return visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED;
    }

    // ================================================================== thu tiền + phát phiếu + hoàn tất ca (T10) trong MỘT giao dịch

    /**
     * KHÔNG @Transactional: chạy giao dịch trong TransactionTemplate để thử lại khi hai quầy thu cùng lúc tính trùng số phiếu
     * (UNIQUE queue_type, service_date, ticket_no). Mỗi lần thử là một giao dịch mới, lần trước đã rollback toàn bộ.
     */
    @Override
    public InvoiceStatementResponse confirmPayment(Long visitId, List<Long> invoiceIds) {
        UUID cashierId = SecurityUtils.getCurrentUserId();
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(s -> doConfirmPayment(visitId, invoiceIds, cashierId));
            } catch (DataIntegrityViolationException e) {
                if (attempt >= MAX_TICKET_ATTEMPTS || !isTicketNumberClash(e)) {
                    throw e;
                }
                backOff(attempt);
            }
        }
    }

    /** Chờ ngẫu nhiên tăng dần trước khi thử lại, để các quầy đang tranh cùng một số phiếu tách nhau ra. */
    static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean isTicketNumberClash(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("uq_qt_number");
    }

    private InvoiceStatementResponse doConfirmPayment(Long visitId, List<Long> invoiceIds, UUID cashierId) {
        PetVisit visit = support.lock(visitId);   // khóa dòng ca: hai thu ngân cùng bấm thì người đến sau thấy hóa đơn đã PAID (BILL_1403)
        if (visit.getStatus() == VisitStatus.CANCELLED) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, "Đợt khám đã hủy, không còn khoản nào để thu");
        }
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        Map<Long, VisitServiceDetail> detailById = details.stream().collect(Collectors.toMap(VisitServiceDetail::getId, d -> d));
        Map<Long, ServiceInvoice> byDetail = support.invoicesOf(detailById.keySet());
        Map<Long, ServiceInvoice> byInvoiceId = new HashMap<>();
        byDetail.values().forEach(i -> byInvoiceId.put(i.getId(), i));

        Set<Long> wanted = new LinkedHashSet<>(invoiceIds);
        if (wanted.isEmpty() && (visit.getStatus() != VisitStatus.WAITING_PAYMENT
                || byInvoiceId.values().stream().anyMatch(i -> i.getPaymentStatus() == PaymentStatus.PENDING))) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chọn ít nhất một hóa đơn để thu");
        }
        for (Long id : wanted) {
            if (!byInvoiceId.containsKey(id)) {
                throw new AppException(ErrorCode.INVOICE_NOT_FOUND, "Hóa đơn " + id + " không thuộc đợt khám này");   // BILL_1401
            }
        }
        // Kiểm tra TẤT CẢ trước khi ghi: một hóa đơn không hợp lệ thì không thu hóa đơn nào
        boolean closed = isClosed(visit);
        for (Long id : wanted) {
            ServiceInvoice invoice = byInvoiceId.get(id);
            requirePending(invoice);
            VisitServiceDetail detail = detailById.get(invoice.getVisitServiceDetailId());
            // BR-35: chính sách thu trước đọc từ danh mục; dịch vụ làm tại phòng chỉ thu khi Bác sĩ đã chốt chuyên môn
            if (!detail.getService().isRequiresPrepayment() && !closed) {
                throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT);
            }
        }
        var now = clock.instant();
        List<VisitServiceDetail> prepaidJustPaid = new ArrayList<>();
        for (Long id : wanted) {
            ServiceInvoice invoice = byInvoiceId.get(id);
            invoice.setPaymentStatus(PaymentStatus.PAID);
            invoice.setCashierId(cashierId);
            invoice.setPaidAt(now);
            invoiceRepository.saveAndFlush(invoice);   // @Version chống thu trùng
            VisitServiceDetail detail = detailById.get(invoice.getVisitServiceDetailId());
            if (detail.getService().isRequiresPrepayment()) {
                prepaidJustPaid.add(detail);
            }
        }
        // Q1: thu tiền xong thì phát phiếu xếp hàng, cùng giao dịch: phí khám -> phiếu DOCTOR; XN / CĐHA -> phiếu phòng
        ticketIssuer.issueForPaidServices(visit, prepaidJustPaid, cashierId);

        boolean anyPending = byInvoiceId.values().stream().anyMatch(i -> i.getPaymentStatus() == PaymentStatus.PENDING);
        if (!anyPending && visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
            // T10: mọi hóa đơn PAID / CANCELLED / REFUNDED -> hoàn tất ca, người đổi trạng thái = thu ngân; ca COMPLETED do khách bỏ về thì giữ nguyên
            support.transition(visit, VisitStatus.COMPLETED, cashierId, "Đã thu đủ hóa đơn dịch vụ, hoàn tất đợt khám");
            outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCompletedEvent(UuidV7.generate(clock), clock.instant(), cashierId, visit.getId(),
                    visit.getVisitCode(), visit.getPet().getId(), false));
        }
        return buildStatement(visit);
    }

    // ================================================================== màn hình Thu ngân (3 mục)

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> cashierPending(CashierSection section) {
        List<PetVisit> visits = switch (section) {
            case EXAM_FEE -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING);
            case PARACLINIC -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_RESULTS);
            case FINAL -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_PAYMENT);
        };
        if (section == CashierSection.FINAL || visits.isEmpty()) {
            return assembler.summaries(visits);
        }
        List<Long> ids = visits.stream().map(PetVisit::getId).toList();
        List<VisitServiceDetail> details = detailRepository.findByVisitIdInOrderByIdAsc(ids);
        Map<Long, ServiceInvoice> invoices = support.invoicesOf(details.stream().map(VisitServiceDetail::getId).toList());
        Set<Long> withDoctorTicket = ticketRepository.findByVisitIdInAndQueueTypeAndStatusIn(ids, QueueType.DOCTOR, TicketStatus.ACTIVE)
                .stream().map(QueueTicket::getVisitId).collect(Collectors.toSet());

        Set<Long> chosen = details.stream()
                .filter(d -> d.getStatus() == ServiceDetailStatus.PENDING && invoices.containsKey(d.getId())
                        && invoices.get(d.getId()).getPaymentStatus() == PaymentStatus.PENDING)
                .filter(d -> section == CashierSection.EXAM_FEE
                        ? d.getService().getCategory() == ServiceCategory.EXAMINATION && !withDoctorTicket.contains(d.getVisitId())
                        : d.getService().getCategory().isParaclinical())
                .map(VisitServiceDetail::getVisitId)
                .collect(Collectors.toSet());
        return assembler.summaries(visits.stream().filter(v -> chosen.contains(v.getId())).toList());
    }

    // ================================================================== công nợ

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> visitsWithDebt() {
        return assembler.summaries(visitRepository.findCompletedWithDebt());
    }
}
