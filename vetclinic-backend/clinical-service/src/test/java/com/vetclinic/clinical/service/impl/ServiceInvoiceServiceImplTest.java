package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Luồng chuẩn của Lộ trình Task 3.9: 100.000 + 250.000 + 180.000 = 530.000 đ (VAT dịch vụ 0%). */
@ExtendWith(MockitoExtension.class)
class ServiceInvoiceServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitServiceDetailHistoryRepository detailHistoryRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private ApplicationEventPublisher publisher;
    @Mock
    private VisitAssembler assembler;

    private ServiceInvoiceServiceImpl service;
    private ServiceInvoice examInvoice;
    private ServiceInvoice labInvoice;
    private ServiceInvoice xrayInvoice;

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository, detailHistoryRepository,
                invoiceRepository, publisher, CLOCK);
        service = new ServiceInvoiceServiceImpl(visitRepository, detailRepository, invoiceRepository, support, assembler, publisher, CLOCK);
        examInvoice = invoice(11L, 1L, "100000.00", "0.00");
        labInvoice = invoice(12L, 2L, "250000.00", "0.00");
        xrayInvoice = invoice(13L, 3L, "180000.00", "0.00");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static ServiceInvoice invoice(long id, long detailId, String unit, String vat) {
        return ServiceInvoice.builder().id(id).invoiceCode("SINV-2026-" + id).visitServiceDetailId(detailId)
                .unitPrice(new BigDecimal(unit)).vatRate(new BigDecimal(vat)).build();
    }

    /** Ca #100 có 3 dịch vụ và 3 hóa đơn; trả về ca để test đổi trạng thái. */
    private PetVisit stubVisit(VisitStatus status) {
        PetVisit visit = visit(100L, status, DOCTOR);
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail lab = detail(2L, 100L, service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail xray = detail(3L, 100L, service(7L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00"), ServiceDetailStatus.COMPLETED);
        // lenient: mỗi test chỉ dùng một phần các lời gọi này (bảng kê không khóa dòng, thu tiền không dùng findById)
        org.mockito.Mockito.lenient().when(visitRepository.findById(100L)).thenReturn(Optional.of(visit));
        org.mockito.Mockito.lenient().when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        org.mockito.Mockito.lenient().when(detailRepository.findByVisitIdOrderByIdAsc(100L)).thenReturn(List.of(exam, lab, xray));
        org.mockito.Mockito.lenient().when(invoiceRepository.findByVisitServiceDetailIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(examInvoice, labInvoice, xrayInvoice));
        return visit;
    }

    // ================================================================== bảng kê (UC-34)

    @Test
    void statement_listsEveryInvoiceWithItsAmounts_andTheTotalToCollectIsThePendingOnes() {
        stubVisit(VisitStatus.WAITING_PAYMENT);
        xrayInvoice.setDiscountAmount(new BigDecimal("30000.00"));
        examInvoice.setPaymentStatus(PaymentStatus.PAID);

        InvoiceStatementResponse statement = service.statement(100L);

        assertThat(statement.invoices()).extracting(ServiceInvoiceResponse::invoiceCode)
                .containsExactly("SINV-2026-11", "SINV-2026-12", "SINV-2026-13");
        assertThat(statement.totalPending()).isEqualByComparingTo("400000.00");   // 250.000 + (180.000 - 30.000)
        assertThat(statement.totalPaid()).isEqualByComparingTo("100000.00");
        ServiceInvoiceResponse xray = statement.invoices().get(2);
        assertThat(xray.amountBeforeVat()).isEqualByComparingTo("180000.00");
        assertThat(xray.payableAmount()).isEqualByComparingTo("150000.00");
        assertThat(xray.category()).isEqualTo(ServiceCategory.IMAGING);
    }

    @Test
    void statement_cancelledInvoicesAreListedButNeverCounted() {
        stubVisit(VisitStatus.WAITING_PAYMENT);
        labInvoice.setPaymentStatus(PaymentStatus.CANCELLED);

        InvoiceStatementResponse statement = service.statement(100L);

        assertThat(statement.invoices()).hasSize(3);
        assertThat(statement.totalPending()).isEqualByComparingTo("280000.00");
    }

    @Test
    void statement_unknownVisit_isNotFound() {
        when(visitRepository.findById(404L)).thenReturn(Optional.empty());
        assertError(ErrorCode.VISIT_NOT_FOUND, () -> service.statement(404L));
    }

    @Test
    void statement_vatIsAppliedPerInvoice() {
        stubVisit(VisitStatus.WAITING_PAYMENT);
        examInvoice.setVatRate(new BigDecimal("0.05"));   // 100.000 x 1,05

        ServiceInvoiceResponse exam = service.statement(100L).invoices().get(0);

        assertThat(exam.vatAmount()).isEqualByComparingTo("5000.00");
        assertThat(exam.payableAmount()).isEqualByComparingTo("105000.00");
    }

    // ================================================================== giảm giá (EX-14)

    private void stubInvoiceOf(ServiceInvoice invoice, long detailId, long visitId, int quantity, VisitStatus visitStatus) {
        VisitServiceDetail d = detail(detailId, visitId, service(7L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00"), ServiceDetailStatus.COMPLETED);
        d.setQuantity(quantity);
        org.mockito.Mockito.lenient().when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.lenient().when(detailRepository.findById(detailId)).thenReturn(Optional.of(d));
        org.mockito.Mockito.lenient().when(visitRepository.findById(visitId)).thenReturn(Optional.of(visit(visitId, visitStatus, DOCTOR)));
    }

    @Test
    void discount_withinTheInvoiceAmount_isSaved() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubInvoiceOf(xrayInvoice, 3L, 100L, 1, VisitStatus.WAITING_PAYMENT);

        ServiceInvoiceResponse response = service.discount(13L, new BigDecimal("30000"));

        assertThat(xrayInvoice.getDiscountAmount()).isEqualByComparingTo("30000.00");
        assertThat(response.payableAmount()).isEqualByComparingTo("150000.00");
        verify(invoiceRepository).saveAndFlush(xrayInvoice);
    }

    @Test
    void discount_exceedingTheInvoicesOwnAmount_isRefused_BILL_1402() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubInvoiceOf(xrayInvoice, 3L, 100L, 1, VisitStatus.WAITING_PAYMENT);

        // 300.000 > 180.000 của chính hóa đơn X-quang (dù tổng ca là 530.000)
        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.discount(13L, new BigDecimal("300000")));
        assertThat(xrayInvoice.getDiscountAmount()).isEqualByComparingTo("0");
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void discount_ceilingIncludesVatAndQuantity_andTheBoundaryIsAllowed() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        ServiceInvoice vaccine = invoice(14L, 4L, "150000.00", "0.05");
        stubInvoiceOf(vaccine, 4L, 100L, 2, VisitStatus.COMPLETED);   // 2 x 150.000 x 1,05 = 315.000; ca còn công nợ cũng được giảm

        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.discount(14L, new BigDecimal("315000.01")));
        service.discount(14L, new BigDecimal("315000.00"));

        assertThat(vaccine.getDiscountAmount()).isEqualByComparingTo("315000.00");
    }

    @Test
    void discount_refusedForPaidOrCancelledInvoices_unknownInvoices_andVisitsThatAreNotReady() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        xrayInvoice.setPaymentStatus(PaymentStatus.PAID);
        stubInvoiceOf(xrayInvoice, 3L, 100L, 1, VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.discount(13L, new BigDecimal("1000")));

        labInvoice.setPaymentStatus(PaymentStatus.CANCELLED);
        stubInvoiceOf(labInvoice, 2L, 100L, 1, VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.discount(12L, new BigDecimal("1000")));

        when(invoiceRepository.findById(404L)).thenReturn(Optional.empty());
        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.discount(404L, new BigDecimal("1000")));

        ServiceInvoice early = invoice(15L, 5L, "100000.00", "0.00");
        stubInvoiceOf(early, 5L, 101L, 1, VisitStatus.IN_PROGRESS);   // bác sĩ chưa chốt
        assertError(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, () -> service.discount(15L, new BigDecimal("1000")));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    // ================================================================== thu tiền + hoàn tất ca (T10)

    @Test
    void confirmPayment_ofEveryInvoice_marksThemPaid_andCompletesTheVisitInTheSameTransaction() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = stubVisit(VisitStatus.WAITING_PAYMENT);

        InvoiceStatementResponse result = service.confirmPayment(100L, List.of(11L, 12L, 13L));

        assertThat(List.of(examInvoice, labInvoice, xrayInvoice)).allSatisfy(i -> {
            assertThat(i.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(i.getCashierId()).isEqualTo(RECEPTIONIST);
            assertThat(i.getPaidAt()).isNotNull();
        });
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(result.visitStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(result.totalPaid()).isEqualByComparingTo("530000.00");
        assertThat(result.totalPending()).isEqualByComparingTo("0");
        ArgumentCaptor<PetVisitStatusHistory> history = ArgumentCaptor.forClass(PetVisitStatusHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(history.getValue().getToStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(history.getValue().getChangedByUserId()).isEqualTo(RECEPTIONIST);   // người đổi trạng thái = thu ngân
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(((VisitCompletedEvent) event.getValue()).abandoned()).isFalse();
    }

    @Test
    void confirmPayment_ofSomeInvoices_keepsTheVisitWaitingForPayment() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = stubVisit(VisitStatus.WAITING_PAYMENT);

        InvoiceStatementResponse result = service.confirmPayment(100L, List.of(11L, 12L));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING_PAYMENT);   // còn hóa đơn X-quang PENDING
        assertThat(xrayInvoice.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.totalPending()).isEqualByComparingTo("180000.00");
        verify(historyRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void confirmPayment_cancelledInvoicesDoNotBlockCompletion() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = stubVisit(VisitStatus.WAITING_PAYMENT);
        xrayInvoice.setPaymentStatus(PaymentStatus.CANCELLED);   // dịch vụ đã hủy

        service.confirmPayment(100L, List.of(11L, 12L));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);   // mọi hóa đơn PAID hoặc CANCELLED
    }

    @Test
    void confirmPayment_ofDebtOnACompletedVisit_collectsMoneyButKeepsTheStatusAndPublishesNothing() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = stubVisit(VisitStatus.COMPLETED);   // khách bỏ về trước đó: hóa đơn đã làm giữ PENDING

        InvoiceStatementResponse result = service.confirmPayment(100L, List.of(11L));

        assertThat(examInvoice.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(result.totalPending()).isEqualByComparingTo("430000.00");
        verify(historyRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void confirmPayment_twiceOrOnBadInvoices_isRefused_andNothingIsPaid() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubVisit(VisitStatus.WAITING_PAYMENT);
        examInvoice.setPaymentStatus(PaymentStatus.PAID);
        labInvoice.setPaymentStatus(PaymentStatus.CANCELLED);

        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.confirmPayment(100L, List.of(11L)));   // bấm đúp / thu lần hai
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.confirmPayment(100L, List.of(12L)));
        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.confirmPayment(100L, List.of(999L)));   // không thuộc ca
        // Một hóa đơn hợp lệ + một hóa đơn đã thu: kiểm tra hết TRƯỚC khi ghi nên hóa đơn X-quang vẫn PENDING
        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.confirmPayment(100L, List.of(13L, 11L)));
        assertThat(xrayInvoice.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmPayment_refusedUntilTheDoctorClosesTheVisit_BILL_1409() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.CANCELLED, DOCTOR)));

        assertError(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, () -> service.confirmPayment(100L, List.of(11L)));
        assertError(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, () -> service.confirmPayment(101L, List.of(11L)));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmPayment_duplicatedIdsInOneRequestAreCollectedOnce() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubVisit(VisitStatus.WAITING_PAYMENT);

        service.confirmPayment(100L, List.of(11L, 11L));

        verify(invoiceRepository).saveAndFlush(examInvoice);
    }

    // ================================================================== công nợ

    @Test
    void visitsWithDebt_delegatesToTheAssemblerWithCompletedVisitsThatStillHavePendingInvoices() {
        PetVisit debtor = visit(100L, VisitStatus.COMPLETED, DOCTOR);
        when(visitRepository.findCompletedWithDebt()).thenReturn(List.of(debtor));
        when(assembler.summaries(List.of(debtor))).thenReturn(List.of());

        assertThat(service.visitsWithDebt()).isEmpty();
        verify(assembler).summaries(List.of(debtor));
    }
}
