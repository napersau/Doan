package com.vetclinic.billing.service.impl;

import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.billing.constant.InventoryTransactionType;
import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.constant.VisitStatus;
import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.MedicineInvoiceItem;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.entity.PrescriptionItem;
import com.vetclinic.billing.repository.InventoryTransactionRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineStockRepository;
import com.vetclinic.billing.repository.PrescriptionRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.billing.service.VisitLookup;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.vetclinic.billing.testsupport.TestData.vid;
import static com.vetclinic.billing.testsupport.TestData.CLOCK;
import static com.vetclinic.billing.testsupport.TestData.DOCTOR;
import static com.vetclinic.billing.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static com.vetclinic.billing.testsupport.TestData.item;
import static com.vetclinic.billing.testsupport.TestData.loginAs;
import static com.vetclinic.billing.testsupport.TestData.medicine;
import static com.vetclinic.billing.testsupport.TestData.mid;
import static com.vetclinic.billing.testsupport.TestData.prescription;
import static com.vetclinic.billing.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicineInvoiceServiceImplTest {

    @Mock
    private MedicineInvoiceRepository invoiceRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private MedicineStockRepository stockRepository;
    @Mock
    private InventoryTransactionRepository inventoryRepository;
    @Mock
    private VisitLookup visitLookup;
    @Mock
    private StockAlertPublisher stockAlerts;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private EntityManager entityManager;

    private MedicineInvoiceServiceImpl service;

    /** "CSDL" giả của kho: deductStock trừ thật vào đây và refresh() đọc lại từ đây, mô phỏng UPDATE nguyên tử. */
    private final Map<java.util.UUID, Integer> stockDb = new HashMap<>();
    private final com.vetclinic.billing.service.MedicinePriceBook priceBook = com.vetclinic.billing.testsupport.TestData.priceBook();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(stockRepository.deduct(any(java.util.UUID.class), anyInt())).thenAnswer(inv -> {
            java.util.UUID id = inv.getArgument(0);
            int quantity = inv.getArgument(1);
            if (stockDb.get(id) < quantity) {
                return 0;
            }
            stockDb.merge(id, -quantity, Integer::sum);
            return 1;
        });
        lenient().doAnswer(inv -> {
            Medicine medicine = inv.getArgument(0);
            com.vetclinic.billing.testsupport.TestData.setStock(medicine, stockDb.get(medicine.getId()));
            return null;
        }).when(entityManager).refresh(any(Medicine.class));
        lenient().when(invoiceRepository.saveAndFlush(any(MedicineInvoice.class))).thenAnswer(inv -> {
            MedicineInvoice invoice = inv.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(5L);
            }
            return invoice;
        });
        lenient().when(visitLookup.tryFind(any(java.util.UUID.class))).thenReturn(visit(7L, VisitStatus.WAITING_PAYMENT, DOCTOR));
        service = new MedicineInvoiceServiceImpl(invoiceRepository, prescriptionRepository, stockRepository, priceBook,
                inventoryRepository, visitLookup, new BillingAssembler(), stockAlerts, outboxWriter, entityManager, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static MedicineInvoiceRequest buy(String discount, int... itemIdAndQuantity) {
        MedicineInvoiceRequest.Line[] lines = new MedicineInvoiceRequest.Line[itemIdAndQuantity.length / 2];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = new MedicineInvoiceRequest.Line((long) itemIdAndQuantity[2 * i], itemIdAndQuantity[2 * i + 1]);
        }
        return new MedicineInvoiceRequest(List.of(lines), discount == null ? null : new BigDecimal(discount));
    }

    /** Đơn #3 của ca #7: 10 viên Clavamox (dòng 10, 15.000đ, VAT 5%) + 2 viên Drontal (dòng 11, 45.000đ, VAT 5%). */
    private Prescription standardPrescription() {
        return prescription(3L, 7L, item(10L, medicine(1L, "Clavamox", "15000", 200), 10),
                item(11L, medicine(2L, "Drontal", "45000", 100), 2));
    }

    private void prescriptionReadyToInvoice(Prescription prescription, VisitStatus visitStatus) {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(prescriptionRepository.findById(3L)).thenReturn(Optional.of(prescription));
        lenient().when(prescriptionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(prescription));
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, visitStatus, DOCTOR));
    }

    /** Hóa đơn PENDING đang mua cả hai dòng của đơn chuẩn, giá snapshot theo danh mục lúc lập. */
    private MedicineInvoice pendingInvoice(Prescription prescription, int amoxQuantity, int drontalQuantity) {
        MedicineInvoice invoice = MedicineInvoice.builder().id(5L).invoiceCode("MINV-2026-0005").prescription(prescription)
                .discountAmount(BigDecimal.ZERO).paymentStatus(PaymentStatus.PENDING).build();
        invoice.addItem(MedicineInvoiceItem.builder().id(100L).prescriptionItem(prescription.getItems().get(0))
                .quantity(amoxQuantity).unitPrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).build());
        if (drontalQuantity > 0) {
            invoice.addItem(MedicineInvoiceItem.builder().id(101L).prescriptionItem(prescription.getItems().get(1))
                    .quantity(drontalQuantity).unitPrice(new BigDecimal("45000")).vatRate(new BigDecimal("0.05")).build());
        }
        return invoice;
    }

    // ================================================================== lập hóa đơn

    @Test
    void create_snapshotsPriceAndVat_buildsMinvCode_andIsPending() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        MedicineInvoiceResponse response = service.create(3L, buy(null, 10, 10));   // mua cả 10 viên Clavamox

        assertThat(response.invoiceCode()).isEqualTo("MINV-2026-0005");   // năm 2026 (CLOCK) + id
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("15000");
        assertThat(response.items().get(0).vatRate()).isEqualByComparingTo("0.05");
        assertThat(response.amountBeforeVat()).isEqualByComparingTo("150000");
        assertThat(response.payableAmount()).isEqualByComparingTo("157500");   // 10 x 15.000 x 1,05
        assertThat(response.visitCode()).isEqualTo("VIS-2026-7");
        verify(inventoryRepository, never()).save(any());   // lập hóa đơn không đụng kho
        verify(stockRepository, never()).deduct(any(java.util.UUID.class), anyInt());
    }

    @Test
    void create_partialPurchaseWithDiscount() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.COMPLETED);   // khách bỏ về rồi quay lại vẫn mua được
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        MedicineInvoiceResponse response = service.create(3L, buy("7500", 10, 4, 11, 1));   // 4 viên + 1 Drontal

        assertThat(response.payableAmount()).isEqualByComparingTo("102750.00");   // (60.000 + 45.000) x 1,05 - 7.500
        assertThat(response.items()).hasSize(2);
    }

    @Test
    void create_visitNotYetClosedByDoctorIsRefused() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.IN_PROGRESS);

        assertError(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, () -> service.create(3L, buy(null, 10, 1)));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_secondInvoiceForTheSamePrescriptionIsRefused_evenIfTheFirstWasCancelled() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        MedicineInvoice cancelled = pendingInvoice(prescription, 10, 0);
        cancelled.setPaymentStatus(PaymentStatus.CANCELLED);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.of(cancelled));

        assertError(ErrorCode.PRESCRIPTION_ALREADY_INVOICED, () -> service.create(3L, buy(null, 10, 1)));
    }

    @Test
    void create_prescribedQuantityIsOnlyAReference_customerMayBuyMoreLessOrEqual() {
        Prescription prescription = standardPrescription();   // kê 10 viên Clavamox (tồn 200), 2 viên Drontal (tồn 100)
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        MedicineInvoiceResponse response = service.create(3L, buy(null, 10, 25, 11, 1));   // nhiều hơn kê (25 > 10) và ít hơn kê (1 < 2)

        assertThat(response.items()).extracting(i -> i.quantity()).containsExactly(25, 1);
        assertThat(response.items().get(0).stockQuantity()).isEqualTo(200);   // giao diện thấy tồn kho để quyết định số lượng
        assertThat(response.items().get(0).prescribedQuantity()).isEqualTo(10);
    }

    @Test
    void create_cannotBuyMoreThanTheStockInTheWarehouse() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.create(3L, buy(null, 10, 201)));   // kho còn 200
        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.create(3L, buy(null, 10, 10, 11, 101)));   // một dòng vượt là từ chối cả hóa đơn
        verify(invoiceRepository, never()).saveAndFlush(any());
        // đúng bằng tồn kho thì được (mức tồn tối thiểu chỉ là ngưỡng cảnh báo, không chặn bán)
        assertThat(service.create(3L, buy(null, 10, 200)).items().get(0).quantity()).isEqualTo(200);
    }

    @Test
    void create_discountCannotExceedTheInvoiceAmount() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        // 1 viên Clavamox = 15.750 đã gồm VAT
        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.create(3L, buy("15750.01", 10, 1)));
        assertThat(service.create(3L, buy("15750", 10, 1)).payableAmount()).isEqualByComparingTo("0");   // bằng đúng tổng thì được
    }

    @Test
    void create_rejectsLinesThatDoNotBelongToThePrescription_zeroQuantity_andDuplicates() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(3L, buy(null, 99, 1)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(3L, buy(null, 10, 0)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(3L, buy(null, 10, 1, 10, 2)));
    }

    @Test
    void create_concurrentDuplicateCaughtByUniqueConstraint() {
        Prescription prescription = standardPrescription();
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());
        when(invoiceRepository.saveAndFlush(any(MedicineInvoice.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertError(ErrorCode.PRESCRIPTION_ALREADY_INVOICED, () -> service.create(3L, buy(null, 10, 1)));
    }

    @Test
    void create_inactiveMedicineCannotBeSold() {
        Prescription prescription = standardPrescription();
        prescription.getItems().get(0).getMedicine().setActive(false);
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.create(3L, buy(null, 10, 1)));
    }

    @Test
    void create_medicineWithoutAnEffectivePrice_isRefused_PHARM_1412() {
        // thuốc không có dòng giá đang hiệu lực trong medicine_prices (không đăng ký vào bảng giá thử)
        com.vetclinic.billing.entity.Medicine unpriced = com.vetclinic.billing.entity.Medicine.builder().id(mid(900L))
                .category(com.vetclinic.billing.testsupport.TestData.category()).code("MED_900").name("Chưa có giá").dosageForm("Viên").baseUnit("Viên")
                .packagingSpec("Hộp").build();
        unpriced.setStock(com.vetclinic.billing.entity.MedicineStock.builder().quantityOnHand(50).build());
        Prescription prescription = prescription(3L, 7L, item(10L, unpriced, 5));
        prescriptionReadyToInvoice(prescription, VisitStatus.WAITING_PAYMENT);
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_PRICE_NOT_FOUND, () -> service.create(3L, buy(null, 10, 1)));
    }

    // ================================================================== sửa / hủy

    @Test
    void updateItems_changesQuantityKeepingTheSnapshotPrice() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = pendingInvoice(prescription, 10, 0);
        // bảng giá đổi sau khi lập hóa đơn (medicine_prices có dòng mới): hóa đơn vẫn giữ snapshot 15.000, sửa số lượng không đổi đơn giá
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        assertThat(service.updateItems(5L, buy(null, 10, 8)).payableAmount()).isEqualByComparingTo("126000");
        assertThat(service.updateItems(5L, buy(null, 10, 10)).payableAmount()).isEqualByComparingTo("157500");
    }

    @Test
    void updateItems_zeroQuantityRemovesTheLine_butAtLeastOneMustRemain() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = pendingInvoice(prescription, 10, 2);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.updateItems(5L, buy(null, 11, 0));
        assertThat(response.items()).hasSize(1);
        assertThat(response.payableAmount()).isEqualByComparingTo("157500");

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.updateItems(5L, buy(null, 10, 0)));   // bỏ nốt dòng cuối
    }

    @Test
    void updateItems_rechecksTheDiscountAgainstTheNewTotal() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = pendingInvoice(prescription, 10, 0);
        invoice.setDiscountAmount(new BigDecimal("100000"));
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        // giảm còn 5 viên => tổng 78.750 < giảm giá 100.000 đang có
        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.updateItems(5L, buy(null, 10, 5)));
        // đổi cả giảm giá cho khớp thì được
        assertThat(service.updateItems(5L, buy("8750", 10, 5)).payableAmount()).isEqualByComparingTo("70000");
    }

    @Test
    void updateItems_beyondStockOrUnknownLineIsRefused_butMoreThanPrescribedIsFine() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(standardPrescription(), 10, 0);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.updateItems(5L, buy(null, 10, 201)));   // kho còn 200
        assertThat(service.updateItems(5L, buy(null, 10, 11)).items().get(0).quantity()).isEqualTo(11);   // 11 > 10 đã kê: được
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.updateItems(5L, buy(null, 11, 1)));   // dòng 11 chưa có trong hóa đơn
    }

    @Test
    void updateAndCancel_onlyWhilePending() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice paid = pendingInvoice(standardPrescription(), 10, 0);
        paid.setPaymentStatus(PaymentStatus.PAID);
        MedicineInvoice cancelled = pendingInvoice(standardPrescription(), 10, 0);
        cancelled.setPaymentStatus(PaymentStatus.CANCELLED);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paid));
        when(invoiceRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cancelled));

        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.updateItems(5L, buy(null, 10, 1)));
        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.cancel(5L));
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.updateItems(6L, buy(null, 10, 1)));
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.cancel(6L));
    }

    @Test
    void cancel_pendingInvoiceBecomesCancelledAndStockIsUntouched() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(standardPrescription(), 10, 0);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.cancel(5L);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(stockRepository, never()).deduct(any(java.util.UUID.class), anyInt());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void unknownInvoice() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(invoiceRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.cancel(404L));
        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.confirmPayment(404L));
    }

    // ================================================================== thu tiền + trừ kho

    @Test
    void confirmPayment_marksPaid_deductsStock_logsSignedExportSale_andPublishesEvents() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = pendingInvoice(prescription, 10, 0);
        stockDb.put(mid(1L), 200);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.confirmPayment(5L);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.cashierId()).isEqualTo(RECEPTIONIST);
        assertThat(response.paidAt()).isEqualTo(CLOCK.instant());
        assertThat(stockDb.get(mid(1L))).isEqualTo(190);   // 200 -> 190 (Lộ trình Task 4.4)

        ArgumentCaptor<InventoryTransaction> log = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryRepository).save(log.capture());
        assertThat(log.getValue().getTransactionType()).isEqualTo(InventoryTransactionType.EXPORT_SALE);
        assertThat(log.getValue().getQuantity()).isEqualTo(-10);   // quantity có dấu
        assertThat(log.getValue().getMedicineInvoiceId()).isEqualTo(5L);
        assertThat(log.getValue().getCreatedBy()).isEqualTo(RECEPTIONIST);

        ArgumentCaptor<DomainEvent> events = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("MEDICINE_INVOICE"), eq(5L), events.capture());
        MedicineInvoicePaidEvent paid = (MedicineInvoicePaidEvent) events.getValue();
        assertThat(paid.payableAmount()).isEqualByComparingTo("157500");
        assertThat(paid.visitId()).isEqualTo(com.vetclinic.billing.testsupport.TestData.vid(7L));
        verify(stockAlerts).publishIfLow(prescription.getItems().get(0).getMedicine(), RECEPTIONIST);
    }

    @Test
    void confirmPayment_deductsInMedicineIdOrder_toAvoidDeadlocks() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        // dòng hóa đơn xếp ngược: Drontal (#2) trước Clavamox (#1)
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = MedicineInvoice.builder().id(5L).invoiceCode("MINV-2026-0005").prescription(prescription).build();
        invoice.addItem(MedicineInvoiceItem.builder().prescriptionItem(prescription.getItems().get(1)).quantity(2)
                .unitPrice(new BigDecimal("45000")).vatRate(new BigDecimal("0.05")).build());
        invoice.addItem(MedicineInvoiceItem.builder().prescriptionItem(prescription.getItems().get(0)).quantity(10)
                .unitPrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).build());
        stockDb.put(mid(1L), 200);
        stockDb.put(mid(2L), 100);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        service.confirmPayment(5L);

        InOrder order = inOrder(stockRepository);
        order.verify(stockRepository).deduct(mid(1L), 10);
        order.verify(stockRepository).deduct(mid(2L), 2);
    }

    @Test
    void confirmPayment_insufficientStockOnAnyLineAbortsTheWholeSale_invoiceStaysPending() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription prescription = standardPrescription();
        MedicineInvoice invoice = pendingInvoice(prescription, 10, 2);
        stockDb.put(mid(1L), 200);
        stockDb.put(mid(2L), 1);   // Drontal chỉ còn 1, cần 2 (EX-15)
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.confirmPayment(5L));

        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(invoice.getPaidAt()).isNull();
        verify(invoiceRepository, never()).saveAndFlush(any());
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));   // không có thông báo giả
        verify(stockAlerts, never()).publishIfLow(any(), any());
    }

    @Test
    void confirmPayment_twiceIsRefusedWithoutTouchingStock() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(standardPrescription(), 10, 0);
        invoice.setPaymentStatus(PaymentStatus.PAID);   // EX-16: lần bấm thứ hai thấy PAID
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.confirmPayment(5L));
        verify(stockRepository, never()).deduct(any(java.util.UUID.class), anyInt());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void confirmPayment_cancelledInvoiceIsRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(standardPrescription(), 10, 0);
        invoice.setPaymentStatus(PaymentStatus.CANCELLED);
        when(invoiceRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.confirmPayment(5L));
    }
}
