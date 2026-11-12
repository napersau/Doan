package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AppointmentClient;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.ServiceOrderItem;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.response.AppointmentSummaryResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.QueueOrders;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.lock.NamedLockService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitServiceImplTest {

    private static final long ADMIN_ID = 1L;

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitServiceDetailHistoryRepository detailHistoryRepository;
    @Mock
    private ClinicServiceRepository serviceRepository;
    @Mock
    private com.vetclinic.clinical.repository.ServiceInvoiceRepository invoiceRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private AppointmentClient appointmentClient;
    @Mock
    private NamedLockService lockService;
    @Mock
    private ApplicationEventPublisher publisher;

    private VisitServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository,
                detailHistoryRepository, invoiceRepository, publisher, CLOCK);
        VisitAssembler assembler = new VisitAssembler(detailRepository, mock(LabTestResultRepository.class),
                mock(ImagingResultRepository.class), mock(ImagingAttachmentRepository.class), invoiceRepository,
                new com.vetclinic.clinical.service.VisitTimeline(historyRepository));
        // Mỗi dòng chỉ định được lập hóa đơn riêng ngay khi tạo (BR-19): giả lập INSERT hóa đơn có id
        lenient().when(invoiceRepository.saveAndFlush(any(com.vetclinic.clinical.entity.ServiceInvoice.class))).thenAnswer(inv -> {
            com.vetclinic.clinical.entity.ServiceInvoice saved = inv.getArgument(0);
            saved.setId(900L + saved.getVisitServiceDetailId());
            return saved;
        });
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // Khóa tên giả: chạy thẳng action, giống khi lấy được khóa
        lenient().when(lockService.runLocked(anyCollection(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        service = new VisitServiceImpl(visitRepository, petRepository, historyRepository, detailRepository,
                serviceRepository, authClient, appointmentClient, support, assembler,
                lockService, publisher, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================== tiếp nhận (UC-19)

    @Test
    void checkIn_walkIn_createsWaitingVisitWithEmergencyFlagAndPublishesEvent() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesById(11L)).thenReturn(Optional.of(pet(11L, dog())));
        stubSaveVisit();

        VisitResponse response = service.checkIn(new CheckInRequest(11L, null, "  Co giật  ", true, null));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING);
        assertThat(response.visitCode()).isEqualTo("VIS-2026-00100");
        assertThat(response.emergency()).isTrue();
        assertThat(response.receptionistId()).isEqualTo(RECEPTIONIST);
        assertThat(response.appointmentId()).isNull();
        assertThat(response.initialSymptoms()).isEqualTo("Co giật");
        assertThat(response.doctorId()).isNull();

        PetVisitStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(history.getNotes()).contains("vãng lai").contains("CẤP CỨU");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        VisitCheckedInEvent checkedIn = (VisitCheckedInEvent) event.getValue();
        assertThat(checkedIn.visitId()).isEqualTo(100L);
        assertThat(checkedIn.appointmentId()).isNull();
        assertThat(checkedIn.emergency()).isTrue();
    }

    @Test
    void checkIn_withTodaysConfirmedAppointment_usesItsPetAndLinksTheAppointment() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(7L)).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 30), 11L)));
        when(petRepository.findWithSpeciesById(11L)).thenReturn(Optional.of(pet(11L, dog())));
        stubSaveVisit();

        // Không gửi petId và triệu chứng: lấy từ lịch hẹn
        VisitResponse response = service.checkIn(new CheckInRequest(null, 7L, null, false, null));

        assertThat(response.petId()).isEqualTo(11L);
        assertThat(response.appointmentId()).isEqualTo(7L);
        assertThat(response.initialSymptoms()).isEqualTo("Khám tổng quát");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(((VisitCheckedInEvent) event.getValue()).appointmentId()).isEqualTo(7L);
    }

    private PetVisit checkInWithSlot(LocalTime start, LocalTime end) {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(7L)).thenReturn(ApiResponse.success(new AppointmentSummaryResponse(7L,
                "APT-20261030-00007", 11L, 5L, LocalDate.of(2026, 10, 30), start, end, "Khám", "CONFIRMED")));
        when(petRepository.findWithSpeciesById(11L)).thenReturn(Optional.of(pet(11L, dog())));
        stubSaveVisit();

        service.checkIn(new CheckInRequest(null, 7L, null, false, null));

        ArgumentCaptor<PetVisit> saved = ArgumentCaptor.forClass(PetVisit.class);
        verify(visitRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void checkIn_queueOrderIsTheCheckInInstantInEpochMicros_andNoSkipYet() {
        // v3.0: không còn tầng "có hẹn đúng giờ" và không còn cột scheduled_at; queue_order = thời điểm tiếp nhận (epoch micro giây)
        PetVisit visit = checkInWithSlot(LocalTime.of(9, 0), LocalTime.of(9, 30));

        assertThat(visit.getQueueOrder()).isEqualTo(QueueOrders.of(CLOCK.instant()));
        assertThat(visit.getQueueOrder()).isEqualTo(1_793_325_600_000_000L);   // 2026-10-30T02:00:00Z
        assertThat(visit.getSkipCount()).isZero();
        assertThat(lastHistory().getNotes()).contains("lịch hẹn #7");
    }

    @Test
    void checkIn_lateForTheSlot_getsNoSpecialTreatment_theSameRuleAsEveryoneElse() {
        // Khung 08:00-08:30 đã qua lúc 09:00: giờ hẹn không còn ảnh hưởng thứ tự (khách đến trễ thật sự thì lịch đã NO_SHOW, FR-18)
        PetVisit visit = checkInWithSlot(LocalTime.of(8, 0), LocalTime.of(8, 30));

        assertThat(visit.getQueueOrder()).isEqualTo(QueueOrders.of(CLOCK.instant()));
    }

    @Test
    void checkIn_appointmentThatIsNotConfirmedOrNotToday_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(1L)).thenReturn(ApiResponse.success(appointment("PENDING", LocalDate.of(2026, 10, 30), 11L)));
        when(appointmentClient.getAppointment(2L)).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 31), 11L)));
        when(appointmentClient.getAppointment(3L)).thenReturn(ApiResponse.success(appointment("NO_SHOW", LocalDate.of(2026, 10, 30), 11L)));

        for (long id : new long[]{1L, 2L, 3L}) {
            assertError(ErrorCode.APPOINTMENT_NOT_CHECKABLE, () -> service.checkIn(new CheckInRequest(null, id, null, false, null)));
        }
        verify(visitRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkIn_petThatDoesNotMatchTheAppointment_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(7L)).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 30), 11L)));

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.checkIn(new CheckInRequest(99L, 7L, null, false, null)));
    }

    @Test
    void checkIn_petAlreadyInAnActiveVisit_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesById(11L)).thenReturn(Optional.of(pet(11L, dog())));
        when(visitRepository.existsByPetIdAndStatusIn(11L, VisitStatus.ACTIVE)).thenReturn(true);

        assertError(ErrorCode.PET_HAS_OPEN_VISIT, () -> service.checkIn(new CheckInRequest(11L, null, null, false, null)));
        verify(visitRepository, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void checkIn_appointmentThatAlreadyHasAVisit_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.existsByAppointmentId(7L)).thenReturn(true);

        // Chặn ngay trước khi gọi sang appointment-service (lịch đã CHECKED_IN sẽ báo "chưa sẵn sàng" gây khó hiểu)
        assertError(ErrorCode.PET_HAS_OPEN_VISIT, () -> service.checkIn(new CheckInRequest(null, 7L, null, false, null)));
        verifyNoInteractions(appointmentClient);
    }

    @Test
    void checkIn_withNeitherPetNorAppointment_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.checkIn(new CheckInRequest(null, null, null, false, null)));
    }

    // ================================================================== phân công Bác sĩ (UC-21, EX-05)

    @Test
    void assignDoctor_toAvailableDoctor_setsDoctorButKeepsWaiting() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        VisitResponse response = service.assignDoctor(100L, new AssignDoctorRequest(DOCTOR));

        assertThat(response.doctorId()).isEqualTo(DOCTOR);
        // Số phòng không còn cột assigned_room: lấy từ Bác sĩ (users.room_number) vừa được kiểm tra qua auth-service
        assertThat(response.assignedRoom()).isEqualTo("P01");
        assertThat(visit.getDoctorId()).isEqualTo(DOCTOR);
        assertThat(response.status()).isEqualTo(VisitStatus.WAITING);
        // Trạng thái không đổi nên KHÔNG ghi pet_visit_status_histories (State Machine Q2)
        verify(historyRepository, never()).save(any());
    }

    @Test
    void assignDoctor_ignoresWhetherTheDoctorIsBusy_FR24() {
        // v3.0: Lễ tân xếp ca vào hàng đợi của Bác sĩ BẤT KỲ, bất cứ lúc nào, không cần Bác sĩ rảnh (Bác sĩ đang khám ca khác vẫn nhận)
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        service.assignDoctor(100L, new AssignDoctorRequest(DOCTOR));

        assertThat(visit.getDoctorId()).isEqualTo(DOCTOR);
    }

    @Test
    void assignDoctor_toAnotherDoctor_movesTheVisitButKeepsItsQueueOrder() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(OTHER_DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P02");
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        long before = visit.getQueueOrder();
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        service.assignDoctor(100L, new AssignDoctorRequest(OTHER_DOCTOR));

        assertThat(visit.getDoctorId()).isEqualTo(OTHER_DOCTOR);
        assertThat(visit.getQueueOrder()).isEqualTo(before);   // ca đến trước vẫn đứng trước trong hàng đợi mới
    }

    @Test
    void unassignDoctor_sendsTheVisitBackToTheUnassignedList_keepingQueueOrder() {
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        long before = visit.getQueueOrder();
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        service.unassignDoctor(100L);

        assertThat(visit.getDoctorId()).isNull();
        assertThat(visit.getQueueOrder()).isEqualTo(before);
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void unassignDoctor_refusedWhenNotWaiting_orWhenNobodyIsAssigned() {
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, null)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.unassignDoctor(100L));   // bác sĩ đã bắt đầu khám: FR-25
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.unassignDoctor(101L));
    }

    @Test
    void checkIn_withADoctor_putsTheVisitInThatDoctorsQueueAndTellsTheEvent() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        when(petRepository.findWithSpeciesById(11L)).thenReturn(Optional.of(pet(11L, dog())));
        stubSaveVisit();

        VisitResponse response = service.checkIn(new CheckInRequest(11L, null, null, false, DOCTOR));

        assertThat(response.doctorId()).isEqualTo(DOCTOR);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(((VisitCheckedInEvent) event.getValue()).doctorId()).isEqualTo(DOCTOR);
        assertThat(lastHistory().getNotes()).contains("hàng đợi Bác sĩ #" + DOCTOR);
    }

    @Test
    void checkIn_withAnInvalidDoctor_isRefusedBeforeAnythingIsSaved() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(30L, RoleCode.LAB_TECHNICIAN, "ACTIVE", null);

        assertError(ErrorCode.INVALID_DOCTOR, () -> service.checkIn(new CheckInRequest(11L, null, null, false, 30L)));
        verify(visitRepository, never()).saveAndFlush(any());
    }

    @Test
    void assignDoctor_requiresAnActiveVeterinarian() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(30L, RoleCode.LAB_TECHNICIAN, "ACTIVE", null);
        stubDoctor(31L, RoleCode.VETERINARIAN, "LOCKED", "P03");

        assertError(ErrorCode.INVALID_DOCTOR, () -> service.assignDoctor(100L, new AssignDoctorRequest(30L)));
        assertError(ErrorCode.INVALID_DOCTOR, () -> service.assignDoctor(100L, new AssignDoctorRequest(31L)));
        verify(lockService, never()).runLocked(anyCollection(), any());
    }

    @Test
    void assignDoctor_onlyForWaitingVisits_andNotTheSameDoctorTwice() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.assignDoctor(100L, new AssignDoctorRequest(DOCTOR)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.assignDoctor(101L, new AssignDoctorRequest(DOCTOR)));
    }

    // ================================================================== khám giai đoạn 1 (UC-22, UC-23)

    @Test
    void startExam_byAssignedDoctor_recordsVitalSignsAndAddsExamFeeOnce() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findByCode("EXAM_GEN")).thenReturn(Optional.of(exam));
        stubSaveDetail();

        VisitResponse response = service.startExam(100L, new StartExamRequest(new BigDecimal("12.5"), new BigDecimal("38.6"), "Ăn kém"));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(response.weightAtVisit()).isEqualByComparingTo("12.5");
        assertThat(response.temperature()).isEqualByComparingTo("38.6");
        assertThat(response.clinicalNotes()).isEqualTo("Ăn kém");

        ArgumentCaptor<VisitServiceDetail> fee = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository).saveAndFlush(fee.capture());
        assertThat(fee.getValue().getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);   // T02: hoàn tất ở T04/T05/T09
        assertThat(fee.getValue().getQueueOrder()).isEqualTo(QueueOrders.of(CLOCK.instant()));
        // v3.0: dòng chỉ định không chứa giá; hóa đơn riêng PENDING được lập CÙNG giao dịch, snapshot đơn giá và thuế suất
        ArgumentCaptor<com.vetclinic.clinical.entity.ServiceInvoice> invoice =
                ArgumentCaptor.forClass(com.vetclinic.clinical.entity.ServiceInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoice.capture());
        assertThat(invoice.getValue().getUnitPrice()).isEqualByComparingTo("100000.00");
        assertThat(invoice.getValue().getVatRate()).isEqualByComparingTo("0.00");
        assertThat(invoice.getValue().getPaymentStatus()).isEqualTo(com.vetclinic.clinical.constant.PaymentStatus.PENDING);
        assertThat(invoice.getValue().getVisitServiceDetailId()).isEqualTo(500L);
        assertThat(invoice.getValue().getInvoiceCode()).isEqualTo(String.format("SINV-2026-%04d", invoice.getValue().getId()));   // sinh từ id, không count + 1
        assertThat(lastHistory().getFromStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
    }

    @Test
    void startExam_doesNotAddExamFeeAgainWhenAlreadyPresent() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING, DOCTOR)));
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findByCode("EXAM_GEN")).thenReturn(Optional.of(exam));
        when(detailRepository.existsByVisitAndServiceCode(100L, "EXAM_GEN")).thenReturn(true);

        service.startExam(100L, new StartExamRequest(new BigDecimal("12.5"), new BigDecimal("38.6"), null));

        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void startExam_rejectsDoctorWhoWasNotAssigned_orIsAlreadyExamining_orWrongStatus() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        StartExamRequest request = new StartExamRequest(new BigDecimal("12.5"), new BigDecimal("38.6"), null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING, OTHER_DOCTOR)));
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, DOCTOR)));
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.startExam(100L, request));
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.startExam(101L, request));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.startExam(102L, request));
    }

    private PetVisit visitWithVitals(long id, VisitStatus status) {
        PetVisit v = visit(id, status, DOCTOR);
        v.setWeightAtVisit(new BigDecimal("4.60"));
        v.setTemperature(new BigDecimal("39.2"));
        return v;
    }

    @Test
    void startExam_vitalSignsAreOptional_butTheExamFeeIsStillCreatedInProgress() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        when(serviceRepository.findByCode("EXAM_GEN")).thenReturn(Optional.of(service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00")));
        stubSaveDetail();

        service.startExam(100L, new StartExamRequest(null, null, null));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getWeightAtVisit()).isNull();   // cân nặng / thân nhiệt có thể nhập sau bằng PUT /clinical-info
        verify(detailRepository).saveAndFlush(any(VisitServiceDetail.class));
    }

    // ================================================================== sinh hiệu (PUT /clinical-info)

    @Test
    void updateClinicalInfo_changesOnlyTheGivenFields_whileExamining() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        service.updateClinicalInfo(100L, new com.vetclinic.clinical.dto.request.ClinicalInfoRequest(null, new BigDecimal("38.1"), "Hạ sốt"));

        assertThat(visit.getWeightAtVisit()).isEqualByComparingTo("4.60");   // giữ nguyên
        assertThat(visit.getTemperature()).isEqualByComparingTo("38.1");
        assertThat(visit.getClinicalNotes()).isEqualTo("Hạ sốt");
    }

    @Test
    void updateClinicalInfo_refusedWhenNotInProgress_orNotTheAssignedDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));   // đã khóa
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        var request = new com.vetclinic.clinical.dto.request.ClinicalInfoRequest(null, null, "x");

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.updateClinicalInfo(100L, request));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.updateClinicalInfo(101L, request));
    }

    // ================================================================== chỉ định cận lâm sàng (T04)

    @Test
    void orderParaclinical_createsPendingLinesAndInvoices_completesTheExam_andReleasesTheDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        ClinicService xray = service(7L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(5L, 7L))).thenReturn(List.of(cbc, xray));
        VisitServiceDetail examLine = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"),
                ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByVisitIdOrderByIdAsc(100L)).thenReturn(List.of(examLine));
        stubSaveDetail();

        VisitResponse response = service.orderParaclinical(100L,
                new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(5L, 7L), "Nghi viêm tụy"));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING_RESULTS);
        assertThat(examLine.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // dịch vụ khám của GĐ 1 hoàn tất
        ArgumentCaptor<VisitServiceDetail> lines = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository, org.mockito.Mockito.times(2)).saveAndFlush(lines.capture());
        assertThat(lines.getAllValues()).extracting(VisitServiceDetail::getStatus)
                .containsOnly(ServiceDetailStatus.PENDING);   // tự vào hàng đợi Lab / CĐHA
        assertThat(lines.getAllValues()).extracting(VisitServiceDetail::getNotes).containsOnly("Nghi viêm tụy");
        ArgumentCaptor<com.vetclinic.clinical.entity.ServiceInvoice> invoices =
                ArgumentCaptor.forClass(com.vetclinic.clinical.entity.ServiceInvoice.class);
        verify(invoiceRepository, org.mockito.Mockito.times(2)).saveAndFlush(invoices.capture());
        assertThat(invoices.getAllValues()).extracting(i -> i.getUnitPrice().toPlainString()).containsExactly("250000.00", "180000.00");
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.WAITING_RESULTS);
    }

    @Test
    void orderParaclinical_requiresWeightAndTemperature_CLIN_1308() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(5L))).thenReturn(List.of(cbc));
        var request = new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(5L), null);

        PetVisit noVitals = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(noVitals));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.orderParaclinical(100L, request));

        PetVisit zeroTemp = visitWithVitals(101L, VisitStatus.IN_PROGRESS);
        zeroTemp.setTemperature(BigDecimal.ZERO);
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(zeroTemp));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.orderParaclinical(101L, request));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderParaclinical_refusedWhileAnInRoomServiceIsStillOpen_CLIN_1309() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(5L))).thenReturn(List.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visitWithVitals(100L, VisitStatus.IN_PROGRESS)));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.IN_ROOM, ServiceDetailStatus.OPEN)).thenReturn(1L);

        assertError(ErrorCode.IN_ROOM_SERVICE_PENDING,
                () -> service.orderParaclinical(100L, new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(5L), null)));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderParaclinical_rejectsNonParaclinicalUnknownLockedAndPhaseTwo() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        // Dịch vụ tại phòng không đi qua cận lâm sàng
        when(serviceRepository.findByIdInAndActiveTrue(List.of(2L))).thenReturn(List.of(service(2L, "VAC_7B", ServiceCategory.VACCINATION, "150000.00")));
        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.orderParaclinical(100L, new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(2L), null)));
        // Dịch vụ không tồn tại / đã ngừng
        when(serviceRepository.findByIdInAndActiveTrue(List.of(99L))).thenReturn(List.of());
        assertError(ErrorCode.SERVICE_NOT_FOUND,
                () -> service.orderParaclinical(100L, new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(99L), null)));

        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(5L))).thenReturn(List.of(cbc));
        var request = new com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest(List.of(5L), null);
        // Hồ sơ đã khóa
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visitWithVitals(101L, VisitStatus.WAITING_PAYMENT)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.orderParaclinical(101L, request));
        // Giai đoạn 2: đã từng qua WAITING_RESULTS
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visitWithVitals(102L, VisitStatus.IN_PROGRESS)));
        when(historyRepository.existsByVisitIdAndToStatus(102L, VisitStatus.WAITING_RESULTS)).thenReturn(true);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.orderParaclinical(102L, request));
        // Bác sĩ khác
        when(visitRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(visit(103L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.orderParaclinical(103L, request));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    // ================================================================== dịch vụ tại phòng (FR-30)

    @Test
    void orderInRoomServices_createsPendingLinesWithInvoices_andKeepsTheVisitInProgress() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        ClinicService vaccine = service(2L, "VAC_7B", ServiceCategory.VACCINATION, "150000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(2L))).thenReturn(List.of(vaccine));
        stubSaveDetail();

        VisitResponse response = service.orderInRoomServices(100L, new OrderServicesRequest(List.of(new ServiceOrderItem(2L, 2, "Mũi 1"))));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        ArgumentCaptor<VisitServiceDetail> line = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository).saveAndFlush(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(ServiceDetailStatus.PENDING);   // chờ "Đã thực hiện"
        assertThat(line.getValue().getQuantity()).isEqualTo(2);
        ArgumentCaptor<com.vetclinic.clinical.entity.ServiceInvoice> invoice =
                ArgumentCaptor.forClass(com.vetclinic.clinical.entity.ServiceInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoice.capture());
        assertThat(invoice.getValue().getUnitPrice()).isEqualByComparingTo("150000.00");
        verify(historyRepository, never()).save(any());   // không đổi trạng thái ca
    }

    @Test
    void orderInRoomServices_rejectsTheExamFeeAndParaclinicalServices_andUnknownOnes() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(1L))).thenReturn(List.of(service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00")));
        when(serviceRepository.findByIdInAndActiveTrue(List.of(5L))).thenReturn(List.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(serviceRepository.findByIdInAndActiveTrue(List.of(99L))).thenReturn(List.of());

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.orderInRoomServices(100L, new OrderServicesRequest(List.of(new ServiceOrderItem(1L, 1, null)))));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.orderInRoomServices(100L, new OrderServicesRequest(List.of(new ServiceOrderItem(5L, 1, null)))));
        assertError(ErrorCode.SERVICE_NOT_FOUND, () -> service.orderInRoomServices(100L, new OrderServicesRequest(List.of(new ServiceOrderItem(99L, 1, null)))));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderInRoomServices_refusedOnALockedVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(4L))).thenReturn(List.of(service(4L, "DEWORM", ServiceCategory.DEWORMING, "50000.00")));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION,
                () -> service.orderInRoomServices(100L, new OrderServicesRequest(List.of(new ServiceOrderItem(4L, 1, null)))));   // BR-27: hồ sơ đã khóa
    }

    // ================================================================== bắt đầu Giai đoạn 2 (T07)

    @Test
    void startPhase2_movesResultsReadyToInProgress_forTheAssignedDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.RESULTS_READY);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        VisitResponse response = service.startPhase2(100L);

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getFromStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(DOCTOR);
    }

    @Test
    void startPhase2_refusedForOtherDoctorWrongStateOrABusyDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.startPhase2(100L));   // chưa có đủ kết quả

        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.RESULTS_READY, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.startPhase2(101L));

        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.RESULTS_READY, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.startPhase2(102L));
    }

    // ================================================================== chốt ca (T05 / T09, UC-33)

    @Test
    void closeVisit_withoutDiagnosis_isRefusedAndNothingChanges() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        for (String blank : new String[]{null, "", "   "}) {
            assertError(ErrorCode.DIAGNOSIS_REQUIRED, () -> service.closeVisit(100L, new CloseVisitRequest(blank, null, null, null)));
        }
        verify(visitRepository, never()).findByIdForUpdate(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void closeVisit_locksTheRecord_completesTheExamFee_andPublishesNothing() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"),
                ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByVisitIdOrderByIdAsc(100L)).thenReturn(List.of(exam));

        VisitResponse response = service.closeVisit(100L,
                new CloseVisitRequest("Viêm dạ dày ruột", "Truyền dịch 3 ngày", "Ăn cháo loãng", "Tỉnh táo"));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(response.locked()).isTrue();
        assertThat(response.diagnosis()).isEqualTo("Viêm dạ dày ruột");
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // hóa đơn của dịch vụ khám giữ PENDING cho thu ngân
        verify(publisher, never()).publishEvent(any(Object.class));   // v3.0: không còn event "đóng ca" sang billing
    }

    @Test
    void closeVisit_isRefusedWhileParaclinicalServicesAreUnfinished_orByAnotherDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        CloseVisitRequest request = new CloseVisitRequest("Chẩn đoán", null, null, null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.closeVisit(100L, request));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.closeVisit(101L, request));
    }

    @Test
    void closeVisit_requiresVitalSigns_CLIN_1308_andNoOpenInRoomService_CLIN_1309() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        CloseVisitRequest request = new CloseVisitRequest("Chẩn đoán", null, null, null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.closeVisit(100L, request));

        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visitWithVitals(101L, VisitStatus.IN_PROGRESS)));
        lenient().when(detailRepository.countByVisitAndCategoriesAndStatuses(101L, ServiceCategory.IN_ROOM, ServiceDetailStatus.OPEN)).thenReturn(1L);
        assertError(ErrorCode.IN_ROOM_SERVICE_PENDING, () -> service.closeVisit(101L, request));
        verify(historyRepository, never()).save(any());
    }

    // ================================================================== khách bỏ về (EX-11: T08, T12)

    @Test
    void abandon_fromResultsReady_closesTheVisitAsCompleted_T08_andNotifiesWithoutCreatingInvoices() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.RESULTS_READY, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        VisitResponse response = service.abandon(100L);

        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(RECEPTIONIST);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        VisitCompletedEvent completed = (VisitCompletedEvent) event.getValue();
        assertThat(completed.abandoned()).isTrue();
        assertThat(completed.visitId()).isEqualTo(100L);
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void abandon_fromWaitingResults_T12_cancelsPendingParaclinicalWithInvoice_butKeepsTheDoneOnesAsDebt() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail done = detail(2L, 100L, service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail pending = detail(3L, 100L, service(6L, "IMG_US_AB", ServiceCategory.IMAGING, "200000.00"), ServiceDetailStatus.PENDING);
        when(detailRepository.findByVisitIdOrderByIdAsc(100L)).thenReturn(List.of(exam, done, pending));
        com.vetclinic.clinical.entity.ServiceInvoice invoice = com.vetclinic.clinical.entity.ServiceInvoice.builder()
                .id(50L).visitServiceDetailId(3L).unitPrice(new BigDecimal("200000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(3L)).thenReturn(Optional.of(invoice));

        VisitResponse response = service.abandon(100L);

        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(pending.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(invoice.getPaymentStatus()).isEqualTo(com.vetclinic.clinical.constant.PaymentStatus.CANCELLED);
        assertThat(done.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // đã làm: giữ hóa đơn PENDING làm công nợ
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        verify(invoiceRepository, never()).findByVisitServiceDetailId(2L);
        verify(invoiceRepository, never()).findByVisitServiceDetailId(1L);
    }

    @Test
    void abandon_refusedWhileAParaclinicalServiceIsInProgress_CLIN_1304() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL,
                List.of(ServiceDetailStatus.IN_PROGRESS))).thenReturn(1L);

        assertError(ErrorCode.PARACLINICAL_SERVICE_IN_PROGRESS, () -> service.abandon(100L));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void abandon_onlyAfterResultsAreAwaitedOrReady_andOtherDoctorOrOtherRolesCannotDoIt() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.abandon(100L));

        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.RESULTS_READY, DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.abandon(101L));

        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.RESULTS_READY, DOCTOR)));
        assertError(ErrorCode.ACCESS_DENIED, () -> service.abandon(102L));
    }

    // ================================================================== hủy ca / chuyển viện (T11) và hủy tiếp nhận (T03)

    @Test
    void cancelExam_cancelsEveryOpenServiceWithItsInvoice_andTheVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"), ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail vaccine = detail(2L, 100L, service(2L, "VAC_7B", ServiceCategory.VACCINATION, "150000.00"), ServiceDetailStatus.PENDING);
        when(detailRepository.findByVisitIdOrderByIdAsc(100L)).thenReturn(List.of(exam, vaccine));
        com.vetclinic.clinical.entity.ServiceInvoice examInvoice = com.vetclinic.clinical.entity.ServiceInvoice.builder()
                .id(50L).visitServiceDetailId(1L).unitPrice(new BigDecimal("100000.00")).vatRate(BigDecimal.ZERO).build();
        com.vetclinic.clinical.entity.ServiceInvoice vaccineInvoice = com.vetclinic.clinical.entity.ServiceInvoice.builder()
                .id(51L).visitServiceDetailId(2L).unitPrice(new BigDecimal("150000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(1L)).thenReturn(Optional.of(examInvoice));
        when(invoiceRepository.findByVisitServiceDetailId(2L)).thenReturn(Optional.of(vaccineInvoice));

        VisitResponse response = service.cancelExam(100L, new ReasonRequest("  Chuyển viện cấp cứu  "));

        assertThat(response.status()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(vaccine.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(examInvoice.getPaymentStatus()).isEqualTo(com.vetclinic.clinical.constant.PaymentStatus.CANCELLED);
        assertThat(vaccineInvoice.getPaymentStatus()).isEqualTo(com.vetclinic.clinical.constant.PaymentStatus.CANCELLED);
        assertThat(lastHistory().getNotes()).isEqualTo("Chuyển viện cấp cứu");   // lý do ghi nguyên văn vào lịch sử
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.CANCELLED);
    }

    @Test
    void cancelExam_refusedInPhaseTwo_afterAnInRoomServiceWasDone_orByAnotherDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        ReasonRequest reason = new ReasonRequest("Khách xin chuyển viện");
        // Giai đoạn 2: đã có kết quả cận lâm sàng cần thu phí
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        lenient().when(historyRepository.existsByVisitIdAndToStatus(100L, VisitStatus.WAITING_RESULTS)).thenReturn(true);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(100L, reason));
        // Đã thực hiện một dịch vụ tại phòng: không hủy, phải chốt để thu phí
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(101L, ServiceCategory.IN_ROOM,
                List.of(ServiceDetailStatus.COMPLETED))).thenReturn(1L);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(101L, reason));
        // Bác sĩ khác / ca đã khóa
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.cancelExam(102L, reason));
        when(visitRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(103L, reason));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void cancel_waitingVisit_byReceptionist_T03() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        VisitResponse response = service.cancel(100L, new ReasonRequest("  Khách bận việc xin về  "));

        assertThat(response.status()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(response.locked()).isTrue();
        // Không còn cột cancel_reason: lý do ghi nguyên văn vào notes của dòng lịch sử, VisitTimeline suy ra khi hiển thị
        assertThat(lastHistory().getNotes()).isEqualTo("Khách bận việc xin về");
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.CANCELLED);
    }

    @Test
    void cancel_isOnlyForTheReceptionist_andOnlyWhileWaiting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(100L, new ReasonRequest("x")));   // Bác sĩ dùng cancel-exam

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(101L, new ReasonRequest("x")));
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(102L, new ReasonRequest("x")));   // khách bỏ về = abandon
        when(visitRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(103L, new ReasonRequest("x")));
    }

    // ================================================================== Mở lại hồ sơ (T14)

    @Test
    void reopen_byTheAssignedDoctor_movesBackToInProgress_withoutTouchingBilling() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));

        VisitResponse response = service.reopen(100L, new ReasonRequest("  Nhập sai liều thuốc "));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(response.locked()).isFalse();            // hồ sơ được mở khóa để sửa
        PetVisitStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(history.getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(history.getChangedByUserId()).isEqualTo(DOCTOR);
        assertThat(history.getNotes()).contains("Nhập sai liều thuốc");
        verify(invoiceRepository, never()).save(any());   // hóa đơn dịch vụ giữ nguyên (PAID/PENDING)
    }

    @Test
    void reopen_byAdmin_isAllowedToo() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));

        assertThat(service.reopen(100L, new ReasonRequest("Điều chỉnh theo yêu cầu")).status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void reopen_refusedForOtherDoctorsOtherRolesWrongStatusOrABusyDoctor() {
        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.reopen(100L, new ReasonRequest("x")));

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.reopen(100L, new ReasonRequest("x")));

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(visit(101L, VisitStatus.COMPLETED, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.reopen(101L, new ReasonRequest("x")));   // đã hoàn tất: khóa vĩnh viễn
        when(visitRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.reopen(102L, new ReasonRequest("x")));

        when(visitRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.reopen(103L, new ReasonRequest("x")));
        when(visitRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        assertError(ErrorCode.VISIT_NOT_FOUND, () -> service.reopen(404L, new ReasonRequest("x")));
        verify(historyRepository, never()).save(any());
    }

    // ================================================================== dịch vụ tính tiền (tạm, billing v2 còn gọi tới 3.B13)

    @Test
    void billableServices_onlyIncludesCompletedLines() {
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(visitRepository.existsById(100L)).thenReturn(true);
        when(detailRepository.findByVisitIdAndStatusOrderByIdAsc(100L, ServiceDetailStatus.COMPLETED))
                .thenReturn(List.of(detail(1L, 100L, exam, ServiceDetailStatus.COMPLETED)));

        var lines = service.billableServices(100L);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).code()).isEqualTo("EXAM_GEN");
        assertThat(lines.get(0).subtotal()).isEqualByComparingTo("100000.00");
    }

    @Test
    void billableServices_usesTheInvoiceSnapshotPrice_notTheCurrentCatalogPrice() {
        // Admin đã tăng giá từ 100.000 lên 120.000 SAU khi chỉ định: hóa đơn giữ giá snapshot lúc chỉ định (BR-25)
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "120000.00");
        VisitServiceDetail line = detail(1L, 100L, exam, ServiceDetailStatus.COMPLETED);
        line.setQuantity(2);
        when(visitRepository.existsById(100L)).thenReturn(true);
        when(detailRepository.findByVisitIdAndStatusOrderByIdAsc(100L, ServiceDetailStatus.COMPLETED)).thenReturn(List.of(line));
        when(invoiceRepository.findByVisitServiceDetailIdIn(List.of(1L))).thenReturn(List.of(
                com.vetclinic.clinical.entity.ServiceInvoice.builder().id(50L).visitServiceDetailId(1L)
                        .unitPrice(new BigDecimal("100000.00")).vatRate(BigDecimal.ZERO).build()));

        var lines = service.billableServices(100L);

        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("100000.00");
        assertThat(lines.get(0).subtotal()).isEqualByComparingTo("200000.00");   // = số lượng × đơn giá snapshot (tính khi hiển thị)
    }

    // ================================================================== fixtures

    private void stubSaveVisit() {
        when(visitRepository.saveAndFlush(any(PetVisit.class))).thenAnswer(inv -> {
            PetVisit saved = inv.getArgument(0);
            saved.setId(100L);
            return saved;
        });
    }

    private void stubSaveDetail() {
        when(detailRepository.saveAndFlush(any(VisitServiceDetail.class))).thenAnswer(inv -> {
            VisitServiceDetail saved = inv.getArgument(0);
            saved.setId(500L);
            return saved;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsPagedVisits_newestFirst_andClampsPageSize() {
        PetVisit visit = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        when(visitRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(visit), org.springframework.data.domain.PageRequest.of(0, 100), 1));

        var page = service.search(VisitStatus.WAITING_PAYMENT, LocalDate.of(2026, 10, 30), DOCTOR, null, "vis-", -3, 5000);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).status()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(page.getTotalElements()).isEqualTo(1);
        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(visitRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();          // trang âm bị đưa về 0
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);      // kích thước tối đa 100
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    private void stubDoctor(long id, RoleCode role, String status, String room) {
        when(authClient.getUser(id)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(id, "BS " + id, "0911111111", role, room, status)));
    }

    private static AppointmentSummaryResponse appointment(String status, LocalDate date, Long petId) {
        return new AppointmentSummaryResponse(7L, "APT-20261030-00007", petId, 5L, date, null, null, "Khám tổng quát", status);
    }

    private PetVisitStatusHistory lastHistory() {
        ArgumentCaptor<PetVisitStatusHistory> captor = ArgumentCaptor.forClass(PetVisitStatusHistory.class);
        verify(historyRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
