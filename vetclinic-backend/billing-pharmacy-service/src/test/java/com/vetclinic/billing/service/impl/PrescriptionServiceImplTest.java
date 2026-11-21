package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.constant.VisitStatus;
import com.vetclinic.billing.dto.request.PrescriptionItemRequest;
import com.vetclinic.billing.dto.request.PrescriptionRequest;
import com.vetclinic.billing.dto.response.PrescriptionResponse;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.repository.MedicineInvoiceItemRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.PrescriptionRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.VisitLookup;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static com.vetclinic.billing.testsupport.TestData.vid;
import static com.vetclinic.billing.testsupport.TestData.uid;
import static com.vetclinic.billing.testsupport.TestData.DOCTOR;
import static com.vetclinic.billing.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.billing.testsupport.TestData.OWNER;
import static com.vetclinic.billing.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static com.vetclinic.billing.testsupport.TestData.item;
import static com.vetclinic.billing.testsupport.TestData.loginAs;
import static com.vetclinic.billing.testsupport.TestData.medicine;
import static com.vetclinic.billing.testsupport.TestData.prescription;
import static com.vetclinic.billing.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private MedicineRepository medicineRepository;
    @Mock
    private MedicineInvoiceRepository invoiceRepository;
    @Mock
    private MedicineInvoiceItemRepository invoiceItemRepository;
    @Mock
    private VisitLookup visitLookup;

    private PrescriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PrescriptionServiceImpl(prescriptionRepository, medicineRepository, invoiceRepository,
                invoiceItemRepository, visitLookup, new BillingAssembler(), tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static PrescriptionItemRequest line(long medicineId, int quantity) {
        return new PrescriptionItemRequest(com.vetclinic.billing.testsupport.TestData.mid(medicineId), quantity, "1 viên", "2 lần/ngày", 5, null);
    }

    private static PrescriptionRequest request(long visitId, PrescriptionItemRequest... lines) {
        return new PrescriptionRequest(vid(visitId), "Cho ăn nhẹ", List.of(lines));
    }

    private void doctorOnInProgressVisit(long visitId) {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitLookup.require(vid(visitId))).thenReturn(visit(visitId, VisitStatus.IN_PROGRESS, DOCTOR));
    }

    // ================================================================== kê đơn

    @Test
    void create_hasNoPriceNoStatus_andDoesNotTouchStock() {
        doctorOnInProgressVisit(7L);
        Medicine amox = medicine(1L, "Clavamox", "15000", 200);
        when(prescriptionRepository.findWithItemsByVisitId(vid(7L))).thenReturn(Optional.empty());
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(amox));

        PrescriptionResponse response = service.create(request(7L, line(1L, 10)));

        assertThat(response.visitId()).isEqualTo(com.vetclinic.billing.testsupport.TestData.vid(7L));
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(10);
        assertThat(response.stockWarnings()).isEmpty();
        assertThat(response.medicineInvoiceId()).isNull();
        assertThat(response.visitCode()).isEqualTo("VIS-2026-7");   // làm giàu từ clinical
        assertThat(amox.getStockQuantity()).isEqualTo(200);         // kê đơn không trừ kho (BR-29)
    }

    @Test
    void create_overStock_savesAndOnlyWarns() {
        doctorOnInProgressVisit(7L);
        Medicine amox = medicine(1L, "Clavamox 250mg", "15000", 200);
        when(prescriptionRepository.findWithItemsByVisitId(vid(7L))).thenReturn(Optional.empty());
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(amox));

        PrescriptionResponse response = service.create(request(7L, line(1L, 300)));   // EX-13

        assertThat(response.items().get(0).quantity()).isEqualTo(300);
        assertThat(response.stockWarnings()).containsExactly("Clavamox 250mg: tồn kho 200 < số lượng kê 300");
        verify(prescriptionRepository).saveAndFlush(any(Prescription.class));
    }

    @Test
    void create_byAnotherDoctorIsRefused() {
        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.IN_PROGRESS, DOCTOR));

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.create(request(7L, line(1L, 1))));
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_afterTheVisitIsClosedIsRefused() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.WAITING_PAYMENT, DOCTOR));

        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.create(request(7L, line(1L, 1))));
    }

    @Test
    void create_secondPrescriptionForTheSameVisitIsRefused() {
        doctorOnInProgressVisit(7L);
        when(prescriptionRepository.findWithItemsByVisitId(vid(7L)))
                .thenReturn(Optional.of(prescription(3L, 7L, item(1L, medicine(1L, "Clavamox", "15000", 200), 5))));

        assertError(ErrorCode.PRESCRIPTION_ALREADY_EXISTS, () -> service.create(request(7L, line(1L, 1))));
    }

    @Test
    void create_concurrentDuplicateCaughtByUniqueConstraint() {
        doctorOnInProgressVisit(7L);
        when(prescriptionRepository.findWithItemsByVisitId(vid(7L))).thenReturn(Optional.empty());
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(medicine(1L, "Clavamox", "15000", 200)));
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertError(ErrorCode.PRESCRIPTION_ALREADY_EXISTS, () -> service.create(request(7L, line(1L, 1))));
    }

    @Test
    void create_sameMedicineTwiceInOneRequestIsRefused() {
        doctorOnInProgressVisit(7L);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(request(7L, line(1L, 1), line(1L, 2))));
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_inactiveOrUnknownMedicineIsRefused() {
        doctorOnInProgressVisit(7L);
        Medicine inactive = medicine(1L, "Thuốc cũ", "1000", 10);
        inactive.setActive(false);
        when(prescriptionRepository.findWithItemsByVisitId(vid(7L))).thenReturn(Optional.empty());
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(inactive));

        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.create(request(7L, line(1L, 1))));
        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.create(request(7L, line(2L, 1))));   // #2 không có trong danh mục
    }

    // ================================================================== sửa đơn

    @Test
    void update_mergesInPlace_keepsExistingItemIdsAndAddsRemovesOthers() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        Medicine amox = medicine(1L, "Clavamox", "15000", 200);
        Medicine drontal = medicine(2L, "Drontal", "45000", 100);
        Medicine vanguard = medicine(3L, "Vanguard", "180000", 50);
        Prescription existing = prescription(3L, 7L, item(10L, amox, 5), item(11L, drontal, 2));
        when(prescriptionRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.IN_PROGRESS, DOCTOR));
        when(prescriptionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));
        when(invoiceRepository.existsByPrescriptionIdAndPaymentStatusNot(3L, PaymentStatus.CANCELLED)).thenReturn(false);
        when(invoiceItemRepository.existsByPrescriptionItemId(11L)).thenReturn(false);
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(amox, vanguard));

        // giữ Clavamox (đổi 5 -> 8), bỏ Drontal, thêm Vanguard
        PrescriptionResponse response = service.update(3L, request(7L, line(1L, 8), line(3L, 1)));

        assertThat(response.items()).extracting(i -> i.medicineId()).containsExactly(com.vetclinic.billing.testsupport.TestData.mid(1L), com.vetclinic.billing.testsupport.TestData.mid(3L));
        assertThat(existing.getItems().get(0).getId()).isEqualTo(10L);   // dòng cũ được cập nhật tại chỗ
        assertThat(existing.getItems().get(0).getQuantity()).isEqualTo(8);
        assertThat(existing.getItems()).hasSize(2);
    }

    @Test
    void update_isLockedOnceThereIsAnActiveMedicineInvoice() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        Prescription existing = prescription(3L, 7L, item(10L, medicine(1L, "Clavamox", "15000", 200), 5));
        when(prescriptionRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.IN_PROGRESS, DOCTOR));   // ca đã được mở lại (T14)
        when(prescriptionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));
        when(invoiceRepository.existsByPrescriptionIdAndPaymentStatusNot(3L, PaymentStatus.CANCELLED)).thenReturn(true);

        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.update(3L, request(7L, line(1L, 1))));
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_cannotDropAnItemThatAnInvoiceReferences() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        Medicine amox = medicine(1L, "Clavamox", "15000", 200);
        Medicine drontal = medicine(2L, "Drontal", "45000", 100);
        Prescription existing = prescription(3L, 7L, item(10L, amox, 5), item(11L, drontal, 2));
        when(prescriptionRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.IN_PROGRESS, DOCTOR));
        when(prescriptionRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(existing));
        when(invoiceRepository.existsByPrescriptionIdAndPaymentStatusNot(3L, PaymentStatus.CANCELLED)).thenReturn(false);   // hóa đơn đã hủy
        when(medicineRepository.findWithCategoryAndStockByIdIn(any())).thenReturn(List.of(amox));
        when(invoiceItemRepository.existsByPrescriptionItemId(11L)).thenReturn(true);   // hóa đơn đã hủy vẫn tham chiếu dòng này

        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.update(3L, request(7L, line(1L, 5))));
    }

    @Test
    void update_afterTheVisitIsClosedIsRefused() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        Prescription existing = prescription(3L, 7L, item(10L, medicine(1L, "Clavamox", "15000", 200), 5));
        when(prescriptionRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.WAITING_PAYMENT, DOCTOR));

        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.update(3L, request(7L, line(1L, 1))));
    }

    // ================================================================== tra cứu

    @Test
    void getById_ownerSeesOwnPetsPrescriptionOnly() {
        Prescription existing = prescription(3L, 7L, item(10L, medicine(1L, "Clavamox", "15000", 200), 5));
        when(prescriptionRepository.findWithItemsById(3L)).thenReturn(Optional.of(existing));
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());
        when(visitLookup.require(vid(7L))).thenReturn(visit(7L, VisitStatus.COMPLETED, DOCTOR));   // chủ nuôi = OWNER

        loginAs(OWNER, RoleCode.PET_OWNER);
        assertThat(service.getById(3L).petName()).isEqualTo("Lu");

        loginAs(uid(6), RoleCode.PET_OWNER);
        assertError(ErrorCode.PRESCRIPTION_NOT_FOUND, () -> service.getById(3L));
    }

    @Test
    void getById_staffGetsInvoiceStatus_andClinicalDownDoesNotBreakIt() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Prescription existing = prescription(3L, 7L, item(10L, medicine(1L, "Clavamox", "15000", 200), 5));
        when(prescriptionRepository.findWithItemsById(3L)).thenReturn(Optional.of(existing));
        when(invoiceRepository.findByPrescriptionId(3L)).thenReturn(Optional.empty());
        when(visitLookup.tryFind(any(java.util.UUID.class))).thenReturn(null);   // clinical tạm sập

        PrescriptionResponse response = service.getById(3L);

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.visitCode()).isNull();
    }
}
