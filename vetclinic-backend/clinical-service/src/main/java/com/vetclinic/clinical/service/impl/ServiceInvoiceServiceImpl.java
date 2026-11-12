package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.InvoiceAmounts;
import com.vetclinic.clinical.service.ServiceInvoiceService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ServiceInvoiceServiceImpl implements ServiceInvoiceService {

    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final ServiceInvoiceRepository invoiceRepository;
    private final VisitSupport support;
    private final VisitAssembler assembler;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ServiceInvoiceServiceImpl(PetVisitRepository visitRepository, VisitServiceDetailRepository detailRepository,
                                     ServiceInvoiceRepository invoiceRepository, VisitSupport support,
                                     VisitAssembler assembler, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.invoiceRepository = invoiceRepository;
        this.support = support;
        this.assembler = assembler;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
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
        BigDecimal pending = sum(items, PaymentStatus.PENDING);
        BigDecimal paid = sum(items, PaymentStatus.PAID);
        return new InvoiceStatementResponse(visit.getId(), visit.getVisitCode(), visit.getStatus(), items, pending, paid);
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
                i.getPaymentStatus(), i.getCashierId(), i.getPaidAt());
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
        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT && visit.getStatus() != VisitStatus.COMPLETED) {
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
        if (invoice.getPaymentStatus() == PaymentStatus.PAID) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_CANCELLED);
        }
    }

    // ================================================================== thu tiền + hoàn tất ca (T10) trong MỘT giao dịch

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceStatementResponse confirmPayment(Long visitId, List<Long> invoiceIds) {
        Long cashierId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(visitId);   // khóa dòng ca: hai thu ngân cùng bấm thì người đến sau thấy hóa đơn đã PAID (BILL_1403)
        if (visit.getStatus() != VisitStatus.WAITING_PAYMENT && visit.getStatus() != VisitStatus.COMPLETED) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT);
        }
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        Map<Long, ServiceInvoice> byDetail = support.invoicesOf(details.stream().map(VisitServiceDetail::getId).toList());
        Map<Long, ServiceInvoice> byInvoiceId = new java.util.HashMap<>();
        byDetail.values().forEach(i -> byInvoiceId.put(i.getId(), i));

        Set<Long> wanted = new LinkedHashSet<>(invoiceIds);
        for (Long id : wanted) {
            if (!byInvoiceId.containsKey(id)) {
                throw new AppException(ErrorCode.INVOICE_NOT_FOUND, "Hóa đơn " + id + " không thuộc đợt khám này");   // BILL_1401
            }
        }
        // Kiểm tra TẤT CẢ trước khi ghi: một hóa đơn không hợp lệ thì không thu hóa đơn nào
        for (Long id : wanted) {
            requirePending(byInvoiceId.get(id));
        }
        var now = clock.instant();
        for (Long id : wanted) {
            ServiceInvoice invoice = byInvoiceId.get(id);
            invoice.setPaymentStatus(PaymentStatus.PAID);
            invoice.setCashierId(cashierId);
            invoice.setPaidAt(now);
            invoiceRepository.saveAndFlush(invoice);   // @Version chống thu trùng
        }

        boolean anyPending = new HashSet<>(byInvoiceId.values()).stream().anyMatch(i -> i.getPaymentStatus() == PaymentStatus.PENDING);
        if (!anyPending && visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
            // T10: thu đủ -> hoàn tất ca, người đổi trạng thái = thu ngân; ca COMPLETED do khách bỏ về thì giữ nguyên trạng thái
            support.transition(visit, VisitStatus.COMPLETED, cashierId, "Đã thu đủ hóa đơn dịch vụ, hoàn tất đợt khám");
            eventPublisher.publishEvent(new VisitCompletedEvent(UUID.randomUUID(), clock.instant(), cashierId, visit.getId(),
                    visit.getVisitCode(), visit.getPet().getId(), false));
        }
        return buildStatement(visit);
    }

    // ================================================================== công nợ

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> visitsWithDebt() {
        return assembler.summaries(visitRepository.findCompletedWithDebt());
    }
}
