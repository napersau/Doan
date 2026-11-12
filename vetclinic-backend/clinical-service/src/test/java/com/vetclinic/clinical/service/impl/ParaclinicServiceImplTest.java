package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.LabEvaluation;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.LabResultItemRequest;
import com.vetclinic.clinical.dto.request.LabResultRequest;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.dto.response.QueueItemResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.ImagingResult;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestReferenceRange;
import com.vetclinic.clinical.entity.LabTestResult;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.entity.VisitServiceDetailHistory;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestItemRepository;
import com.vetclinic.clinical.repository.LabTestReferenceRangeRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.ImagingStorage;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.clinical.service.VisitTimeline;
import com.vetclinic.common.event.DoctorPriorityCallbackEvent;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.IMAGING_TECH;
import static com.vetclinic.clinical.testsupport.TestData.LAB_TECH;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.cat;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParaclinicServiceImplTest {

    private static final ClinicService CBC = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
    private static final ClinicService ULTRASOUND = service(6L, "IMG_US_AB", ServiceCategory.IMAGING, "200000.00");

    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitServiceDetailHistoryRepository detailHistoryRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private LabTestItemRepository labTestItemRepository;
    @Mock
    private LabTestReferenceRangeRepository rangeRepository;
    @Mock
    private com.vetclinic.clinical.repository.ServiceLabTestItemRepository serviceLabItemRepository;
    @Mock
    private LabTestResultRepository labResultRepository;
    @Mock
    private ImagingResultRepository imagingResultRepository;
    @Mock
    private ImagingAttachmentRepository attachmentRepository;
    @Mock
    private ImagingStorage storage;
    @Mock
    private ApplicationEventPublisher publisher;
    @Mock
    private AuthClient authClient;

    private ParaclinicServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository,
                detailHistoryRepository, invoiceRepository, publisher, CLOCK);
        VisitAssembler assembler = new VisitAssembler(detailRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, invoiceRepository, new VisitTimeline(historyRepository));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ParaclinicServiceImpl(detailRepository, detailHistoryRepository, authClient, publisher, CLOCK,
                visitRepository,
                labTestItemRepository, rangeRepository, serviceLabItemRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, support, assembler, storage, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================== hàng đợi

    @Test
    void queue_keepsTheRepositoryOrder_exposesSkipCountAndTheLabStage() {
        PetVisit normal = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit emergency = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        emergency.setEmergency(true);
        PetVisit pushedBack = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);

        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d3 = detail(3L, 12L, CBC, ServiceDetailStatus.PENDING);
        d3.setSkipCount(2);   // đã bị "Đẩy lùi 1 bậc" hai lần: vẫn nằm trong hàng đợi, chỉ hiển thị số lần

        // Thứ tự do truy vấn quyết định ((cấp cứu AND skip_count = 0) DESC, queue_order ASC): service giữ nguyên, không tự sắp lại
        when(detailRepository.findQueue(ServiceCategory.LABORATORY, ServiceDetailStatus.OPEN)).thenReturn(List.of(d2, d1, d3));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(normal, emergency, pushedBack));
        when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(d1, d2, d3));
        // d1 đã lấy mẫu xong (đang phân tích); d2, d3 mới chỉ định / đã bị đẩy lùi
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                history(1L, "ORDERED"), history(2L, "ORDERED"), history(3L, "ORDERED"), history(1L, "START_PROCESSING"),
                history(3L, "SKIPPED"), history(1L, "SAMPLE_COLLECTED")));

        List<QueueItemResponse> queue = service.queue(ServiceCategory.LABORATORY);

        assertThat(queue).extracting(QueueItemResponse::serviceDetailId).containsExactly(2L, 1L, 3L);
        assertThat(queue).extracting(QueueItemResponse::skipCount).containsExactly(0, 0, 2);
        assertThat(queue.get(0).emergency()).isTrue();
        assertThat(queue).extracting(QueueItemResponse::stage).containsExactly("QUEUED", "ANALYZING", "QUEUED");
        assertThat(queue).extracting(QueueItemResponse::labStatus).containsOnlyNulls();   // huy hiệu liên phòng chỉ có ở hàng đợi CĐHA
    }

    private static VisitServiceDetailHistory history(long detailId, String action) {
        return VisitServiceDetailHistory.builder().visitServiceDetailId(detailId).action(action)
                .toStatus(ServiceDetailStatus.PENDING).changedByUserId(LAB_TECH).build();
    }

    @Test
    void imagingQueue_showsTheCrossRoomLabBadge_samplingThenSampleCollected_EX09() {
        PetVisit sampling = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit collected = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit noLab = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);
        VisitServiceDetail img1 = detail(1L, 10L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail img2 = detail(2L, 11L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail img3 = detail(3L, 12L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail lab1 = detail(4L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail lab2 = detail(5L, 11L, CBC, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findQueue(ServiceCategory.IMAGING, ServiceDetailStatus.OPEN)).thenReturn(List.of(img1, img2, img3));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(sampling, collected, noLab));
        when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(img1, img2, img3, lab1, lab2));
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                history(4L, "START_PROCESSING"), history(5L, "START_PROCESSING"), history(5L, "SAMPLE_COLLECTED")));

        List<QueueItemResponse> queue = service.queue(ServiceCategory.IMAGING);

        assertThat(queue).extracting(QueueItemResponse::labStatus).containsExactly("SAMPLING", "SAMPLE_COLLECTED", null);
        assertThat(queue).extracting(QueueItemResponse::stage).containsOnlyNulls();   // stage chỉ dành cho phòng Lab
    }

    @Test
    void queue_ofANonParaclinicalCategory_isRejected() {
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.queue(ServiceCategory.VACCINATION));
    }

    // ================================================================== Gọi số / Đẩy lùi (BR-34)

    private List<PetVisit> stubQueue(ServiceCategory category, List<VisitServiceDetail> queue, PetVisit... visits) {
        when(detailRepository.findQueue(category, List.of(ServiceDetailStatus.PENDING))).thenReturn(queue);
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(visits));
        lenient().when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(queue);
        lenient().when(authClient.getUser(LAB_TECH)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(LAB_TECH, "KTV Minh", "0922222222", RoleCode.LAB_TECHNICIAN, "P201", "ACTIVE")));
        return List.of(visits);
    }

    private PatientCalledEvent[] publishedCalls(int expected) {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(expected)).publishEvent(events.capture());
        return events.getAllValues().stream().map(PatientCalledEvent.class::cast).toArray(PatientCalledEvent[]::new);
    }

    @Test
    void callNext_picksTheHeadOfThePendingQueue_publishesTheCall_andDoesNotChangeStatus() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        stubQueue(ServiceCategory.LABORATORY, List.of(d1, d2), visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR),
                visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR));
        when(visitRepository.findWithPetById(10L)).thenReturn(Optional.of(visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR)));

        QueueItemResponse called = service.callNext(ServiceCategory.LABORATORY);

        assertThat(called.serviceDetailId()).isEqualTo(1L);
        assertThat(d1.getStatus()).isEqualTo(ServiceDetailStatus.PENDING);   // Q3: gọi trước, đổi trạng thái sau (khi bấm "Bắt đầu")
        PatientCalledEvent event = publishedCalls(1)[0];
        assertThat(event.action()).isEqualTo("CALLED");
        assertThat(event.station()).isEqualTo("LAB");
        assertThat(event.serviceDetailId()).isEqualTo(1L);
        assertThat(event.roomNumber()).isEqualTo("P201");
        assertThat(event.doctorId()).isEqualTo(DOCTOR);
        verify(detailHistoryRepository, never()).save(any());
    }

    @Test
    void callNext_emptyQueue_CLIN_1303_andWrongRoleIsDenied() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        when(detailRepository.findQueue(ServiceCategory.LABORATORY, List.of(ServiceDetailStatus.PENDING))).thenReturn(List.of());
        assertError(ErrorCode.QUEUE_EMPTY, () -> service.callNext(ServiceCategory.LABORATORY));
        // Kỹ thuật viên Lab không gọi số phòng CĐHA, và ngược lại
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(ServiceCategory.IMAGING));
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(ServiceCategory.LABORATORY));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.callNext(ServiceCategory.VACCINATION));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void skip_inTheMiddle_pushesBackBetweenTheNextTwo_keepsPending_logsSkipped_andCallsTheNextOne() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d3 = detail(3L, 12L, CBC, ServiceDetailStatus.PENDING);
        long o2 = d2.getQueueOrder();
        long o3 = d3.getQueueOrder();
        stubQueue(ServiceCategory.LABORATORY, List.of(d1, d2, d3), visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR),
                visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR), visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR));
        when(detailRepository.findById(1L)).thenReturn(Optional.of(d1));
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d1));

        QueueItemResponse called = service.skip(1L);

        assertThat(d1.getSkipCount()).isEqualTo(1);
        assertThat(d1.getQueueOrder()).isEqualTo((o2 + o3) / 2);   // Q4: trung bình cộng của hai người kế tiếp
        assertThat(d1.getStatus()).isEqualTo(ServiceDetailStatus.PENDING);
        assertThat(called.serviceDetailId()).isEqualTo(2L);   // gọi ngay người kế tiếp
        ArgumentCaptor<VisitServiceDetailHistory> audit = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("SKIPPED");
        assertThat(audit.getValue().getFromStatus()).isEqualTo(ServiceDetailStatus.PENDING);
        assertThat(audit.getValue().getToStatus()).isEqualTo(ServiceDetailStatus.PENDING);
        PatientCalledEvent[] events = publishedCalls(2);
        assertThat(events[0].action()).isEqualTo("PUSHED_BACK");
        assertThat(events[0].serviceDetailId()).isEqualTo(1L);
        assertThat(events[0].skipCount()).isEqualTo(1);
        assertThat(events[1].action()).isEqualTo("CALLED");
        assertThat(events[1].serviceDetailId()).isEqualTo(2L);
    }

    @Test
    void skip_nobodyBehind_keepsThePosition_butStillCountsTheSkip() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail d1 = detail(1L, 10L, ULTRASOUND, ServiceDetailStatus.PENDING);
        long before = d1.getQueueOrder();
        stubQueue(ServiceCategory.IMAGING, List.of(d1), visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR));
        when(detailRepository.findById(1L)).thenReturn(Optional.of(d1));
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d1));

        QueueItemResponse result = service.skip(1L);

        assertThat(d1.getSkipCount()).isEqualTo(1);
        assertThat(d1.getQueueOrder()).isEqualTo(before);   // không còn ai phía sau: giữ nguyên vị trí
        assertThat(result.serviceDetailId()).isEqualTo(1L);
        PatientCalledEvent[] events = publishedCalls(1);
        assertThat(events[0].action()).isEqualTo("PUSHED_BACK");
        assertThat(events[0].station()).isEqualTo("IMAGING");
    }

    @Test
    void skip_emergencyHeadWhoseNextIsAnotherUnskippedEmergency_goesJustBeforeTheFirstRegular() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        PetVisit e1 = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        e1.setEmergency(true);
        PetVisit e2 = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        e2.setEmergency(true);
        PetVisit r1 = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);
        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d3 = detail(3L, 12L, CBC, ServiceDetailStatus.PENDING);
        stubQueue(ServiceCategory.LABORATORY, List.of(d1, d2, d3), e1, e2, r1);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(d1));
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d1));

        service.skip(1L);

        assertThat(d1.getQueueOrder()).isEqualTo(d3.getQueueOrder() - 1);   // ca cấp cứu bị lùi mất ưu tiên: xếp ngay trước ca thường đầu tiên
        assertThat(d1.getSkipCount()).isEqualTo(1);
    }

    @Test
    void skip_refusedWhenNotPending_orForTheWrongRoom() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail started = detail(1L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(started));
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(started));
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.skip(1L));   // đã vào phòng thì không đẩy lùi

        VisitServiceDetail imaging = detail(2L, 11L, ULTRASOUND, ServiceDetailStatus.PENDING);
        when(detailRepository.findById(2L)).thenReturn(Optional.of(imaging));
        assertError(ErrorCode.ACCESS_DENIED, () -> service.skip(2L));   // KTV Lab không đẩy lùi hàng đợi CĐHA

        VisitServiceDetail inRoom = detail(3L, 12L, DEWORM_SVC, ServiceDetailStatus.PENDING);
        when(detailRepository.findById(3L)).thenReturn(Optional.of(inRoom));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.skip(3L));   // dịch vụ tại phòng không có hàng đợi
        assertError(ErrorCode.SERVICE_DETAIL_NOT_FOUND, () -> service.skip(99L));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ================================================================== lấy mẫu (phòng Lab)

    private static final ClinicService DEWORM_SVC = service(4L, "DEWORM", ServiceCategory.DEWORMING, "50000.00");

    @Test
    void sampleCollected_logsTheActionWithoutChangingTheStatus() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(1L)))
                .thenReturn(List.of(history(1L, "ORDERED"), history(1L, "START_PROCESSING")));

        service.sampleCollected(1L);

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        ArgumentCaptor<VisitServiceDetailHistory> audit = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("SAMPLE_COLLECTED");
        assertThat(audit.getValue().getFromStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        assertThat(audit.getValue().getToStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
    }

    @Test
    void sampleCollected_refusedForTheWrongRoleKindStateOrWhenAlreadyCollected() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.sampleCollected(1L));

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        // Chưa vào phòng (PENDING)
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.sampleCollected(2L));
        // Ca không còn chờ kết quả
        stubLocks(detail(3L, 102L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.sampleCollected(3L));
        // Đã lấy mẫu rồi
        stubLocks(detail(4L, 103L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(4L)))
                .thenReturn(List.of(history(4L, "START_PROCESSING"), history(4L, "SAMPLE_COLLECTED")));
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.sampleCollected(4L));
        verify(detailHistoryRepository, never()).save(any());
    }

    // ================================================================== bắt đầu thực hiện

    @Test
    void start_movesPendingToInProgressAndWritesAudit() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);

        ServiceDetailResponse response = service.start(1L);

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetailHistory audit = lastDetailHistory();
        assertThat(audit.getAction()).isEqualTo("START_PROCESSING");
        assertThat(audit.getFromStatus()).isEqualTo(ServiceDetailStatus.PENDING);
        assertThat(audit.getChangedByUserId()).isEqualTo(LAB_TECH);
    }

    @Test
    void start_refusedForTheWrongRoomTechnician_orVisitNoLongerWaiting() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);

        // KTV phòng CĐHA không được thao tác chỉ định của phòng Lab
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.start(1L));

        // Đợt khám đã bị hủy: không thể bắt đầu
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.CANCELLED);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.start(2L));
    }

    // ================================================================== dịch vụ tại phòng (FR-30)

    private static final ClinicService DEWORM = service(4L, "DEWORM", ServiceCategory.DEWORMING, "50000.00");
    private static final ClinicService EXAM = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");

    @Test
    void perform_completesAnInRoomService_recordingStartThenCompleted() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);

        ServiceDetailResponse response = service.perform(1L);

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.COMPLETED);
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        ArgumentCaptor<VisitServiceDetailHistory> audit = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository, org.mockito.Mockito.times(2)).save(audit.capture());
        assertThat(audit.getAllValues()).extracting(VisitServiceDetailHistory::getAction).containsExactly("START_PROCESSING", "COMPLETED");
    }

    @Test
    void perform_refusedForTheWrongRoleDoctorServiceOrState() {
        // Kỹ thuật viên không ghi nhận dịch vụ tại phòng
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.perform(1L));

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        // Chỉ định cận lâm sàng không "Đã thực hiện" kiểu này
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.perform(2L));
        // Ca đã khóa
        stubLocks(detail(3L, 102L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.perform(3L));
        // Đã hoàn tất rồi
        stubLocks(detail(4L, 103L, DEWORM, ServiceDetailStatus.COMPLETED), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.perform(4L));
        // Bác sĩ khác không phải Bác sĩ được gán
        loginAs(com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(5L, 104L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.perform(5L));
    }

    @Test
    void cancel_ofAnInRoomService_byTheAssignedDoctor_cancelsItsInvoiceToo() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        ServiceInvoice invoice = ServiceInvoice.builder().id(50L).visitServiceDetailId(1L).unitPrice(new BigDecimal("50000.00"))
                .vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(1L)).thenReturn(Optional.of(invoice));

        service.cancel(1L, "Khách từ chối tẩy giun");

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(publisher, never()).publishEvent(any(Object.class));   // dịch vụ tại phòng không ảnh hưởng RESULTS_READY
    }

    @Test
    void cancel_ofAnInRoomService_refusedWhenTheVisitIsLocked_andTheExamFeeCannotBeCancelledAlone() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(1L, "x"));

        stubLocks(detail(2L, 101L, EXAM, ServiceDetailStatus.IN_PROGRESS), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(2L, "x"));   // dùng "Hủy ca / Chuyển viện"
    }

    // ================================================================== kết quả xét nghiệm (UC-25)

    @Test
    void submitLab_evaluatesAgainstTheSpeciesRangeAndSnapshotsIt() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(labTestItemRepository.findAllById(List.of(1L, 4L))).thenReturn(List.of(wbc, plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(4L, 1L)).thenReturn(Optional.of(range(plt, "200.00", "500.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(1L, new LabResultRequest(List.of(
                new LabResultItemRequest(1L, new BigDecimal("21.00"), null),     // Chó: 21 > 17 -> HIGH
                new LabResultItemRequest(4L, new BigDecimal("150.00"), "Tiểu cầu thấp"))));

        ArgumentCaptor<List<LabTestResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(labResultRepository).saveAll(saved.capture());
        LabTestResult highWbc = saved.getValue().get(0);
        // Không còn cột evaluation: nhãn tính từ giá trị so với khoảng tham chiếu đã snapshot (DANH_SACH_KIEU_ENUM mục 11)
        assertThat(LabEvaluation.of(highWbc.getResultValue(), highWbc.getMinValueSnapshot(), highWbc.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.HIGH);
        assertThat(highWbc.getMinValueSnapshot()).isEqualByComparingTo("6.00");   // snapshot (BR-24)
        assertThat(highWbc.getMaxValueSnapshot()).isEqualByComparingTo("17.00");
        assertThat(highWbc.getPerformedBy()).isEqualTo(LAB_TECH);
        LabTestResult lowPlt = saved.getValue().get(1);
        assertThat(LabEvaluation.of(lowPlt.getResultValue(), lowPlt.getMinValueSnapshot(), lowPlt.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.LOW);
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
    }

    @Test
    void submitLab_aConfiguredPackageRequiresEveryItem_andListsTheMissingOnes() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(serviceLabItemRepository.findItemIdsByServiceId(5L)).thenReturn(List.of(1L, 4L));   // gói CBC gồm WBC + PLT
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(labTestItemRepository.findAllById(Set.of(4L))).thenReturn(List.of(plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.vetclinic.common.exception.AppException.class,
                () -> service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null)))));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);   // SYS_1001
        assertThat(ex.getMessage()).contains("PLT");
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void submitLab_aCompletePackageIsAccepted() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(serviceLabItemRepository.findItemIdsByServiceId(5L)).thenReturn(List.of(1L, 4L));
        when(labTestItemRepository.findAllById(List.of(4L, 1L))).thenReturn(List.of(wbc, plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(4L, 1L)).thenReturn(Optional.of(range(plt, "200.00", "500.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(1L, new LabResultRequest(List.of(
                new LabResultItemRequest(4L, new BigDecimal("300.00"), null), new LabResultItemRequest(1L, new BigDecimal("9.00"), null))));

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        verify(labResultRepository).saveAll(anyList());
    }

    @Test
    void labResults_returnsTheSavedValuesOfALabDetail_andRejectsOtherKinds() {
        VisitServiceDetail lab = detail(1L, 100L, CBC, ServiceDetailStatus.COMPLETED);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(lab));
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestResult saved = LabTestResult.builder().id(9L).visitServiceDetailId(1L).labTestItem(wbc)
                .resultValue(new BigDecimal("21.00")).minValueSnapshot(new BigDecimal("6.00")).maxValueSnapshot(new BigDecimal("17.00"))
                .performedBy(LAB_TECH).build();
        when(labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(1L))).thenReturn(List.of(saved));

        var results = service.labResults(1L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).evaluation()).isEqualTo(LabEvaluation.HIGH);
        when(detailRepository.findById(2L)).thenReturn(Optional.of(detail(2L, 100L, ULTRASOUND, ServiceDetailStatus.COMPLETED)));
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.labResults(2L));
        assertError(ErrorCode.SERVICE_DETAIL_NOT_FOUND, () -> service.labResults(99L));
    }

    @Test
    void submitLab_usesTheRangeOfThePetsOwnSpecies() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        PetVisit catVisit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        catVisit.setPet(pet(12L, cat()));
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(catVisit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        // Mèo (species 2): WBC 5.5 - 19.5, nên 18.0 là bình thường (với Chó 6 - 17 thì là cao)
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 2L)).thenReturn(Optional.of(range(wbc, "5.50", "19.50")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("18.00"), null))));

        ArgumentCaptor<List<LabTestResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(labResultRepository).saveAll(saved.capture());
        LabTestResult catResult = saved.getValue().get(0);
        assertThat(LabEvaluation.of(catResult.getResultValue(), catResult.getMinValueSnapshot(), catResult.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.NORMAL);
        verify(rangeRepository, never()).findByLabTestItemIdAndSpeciesId(1L, 1L);
    }

    @Test
    void submitLab_lastOpenServiceMovesTheVisitToResultsReadyAndNotifiesTheDoctor() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        // Không còn chỉ định Lab / CĐHA nào dở dang
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null))));

        ArgumentCaptor<com.vetclinic.clinical.entity.PetVisitStatusHistory> history =
                ArgumentCaptor.forClass(com.vetclinic.clinical.entity.PetVisitStatusHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(VisitStatus.WAITING_RESULTS);
        assertThat(history.getValue().getToStatus()).isEqualTo(VisitStatus.RESULTS_READY);

        assertThat(history.getValue().getChangedByUserId()).isNull();   // T06: hệ thống tự chuyển

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(event.capture());
        ParaclinicalResultsReadyEvent ready = event.getAllValues().stream().filter(ParaclinicalResultsReadyEvent.class::isInstance)
                .map(ParaclinicalResultsReadyEvent.class::cast).findFirst().orElseThrow();
        assertThat(ready.visitId()).isEqualTo(100L);
        assertThat(ready.doctorId()).isEqualTo(DOCTOR);
        assertThat(ready.actorUserId()).isNull();
        // v2 còn phát song song tới Phase 5/7.1
        DoctorPriorityCallbackEvent callback = event.getAllValues().stream().filter(DoctorPriorityCallbackEvent.class::isInstance)
                .map(DoctorPriorityCallbackEvent.class::cast).findFirst().orElseThrow();
        assertThat(callback.doctorId()).isEqualTo(DOCTOR);
    }

    @Test
    void resultsReady_Q5_putsTheVisitAtTheHeadOfTheDoctorsQueue_andClearsItsSkipCount() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        visit.setSkipCount(2);
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);
        // Hàng đợi của bác sĩ (không tính ca này) có ca nhỏ nhất ở 5000
        when(visitRepository.findMinQueueOrder(DOCTOR, List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY), 100L)).thenReturn(5000L);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null))));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        assertThat(visit.getQueueOrder()).isEqualTo(4999L);   // MIN - 1: lên đầu hàng đợi (chỉ sau ca cấp cứu chưa bị lùi)
        assertThat(visit.getSkipCount()).isZero();
    }

    @Test
    void resultsReady_Q5_emptyDoctorQueue_keepsTheQueueOrder() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        long before = visit.getQueueOrder();
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);
        // MIN() trên hàng đợi trống trả về null (Mockito mặc định trả 0 cho Long nên phải nêu rõ)
        when(visitRepository.findMinQueueOrder(DOCTOR, List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY), 100L)).thenReturn(null);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null))));

        assertThat(visit.getQueueOrder()).isEqualTo(before);   // không có ca nào khác để vượt lên
    }

    @Test
    void submitLab_whileAnotherServiceIsStillOpen_keepsTheVisitWaiting() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null))));

        verify(historyRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void submitLab_rejectsInvalidSubmissions() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        LabResultRequest request = new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null)));

        // Chưa bấm "Bắt đầu thực hiện"
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.submitLabResults(1L, request));

        // Nhập hai lần cùng một chỉ số
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitLabResults(2L, new LabResultRequest(List.of(
                new LabResultItemRequest(1L, new BigDecimal("1.00"), null), new LabResultItemRequest(1L, new BigDecimal("2.00"), null)))));

        // Chỉ số không tồn tại
        stubLocks(detail(3L, 102L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of());
        assertError(ErrorCode.LAB_ITEM_NOT_FOUND, () -> service.submitLabResults(3L, request));

        // Chưa cấu hình khoảng tham chiếu cho loài này
        stubLocks(detail(4L, 103L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(labItem(1L, "WBC")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.empty());
        assertError(ErrorCode.LAB_REFERENCE_NOT_FOUND, () -> service.submitLabResults(4L, request));

        // Chỉ định siêu âm không nhập được như xét nghiệm; và KTV CĐHA không được nhập kết quả Lab
        stubLocks(detail(5L, 104L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.submitLabResults(5L, request));
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void labEvaluation_boundariesAreInclusive() {
        BigDecimal min = new BigDecimal("6.00");
        BigDecimal max = new BigDecimal("17.00");
        assertThat(LabEvaluation.of(new BigDecimal("6.00"), min, max)).isEqualTo(LabEvaluation.NORMAL);
        assertThat(LabEvaluation.of(new BigDecimal("17.00"), min, max)).isEqualTo(LabEvaluation.NORMAL);
        assertThat(LabEvaluation.of(new BigDecimal("5.99"), min, max)).isEqualTo(LabEvaluation.LOW);
        assertThat(LabEvaluation.of(new BigDecimal("17.01"), min, max)).isEqualTo(LabEvaluation.HIGH);
    }

    // ================================================================== hủy chỉ định (EX-08)

    @Test
    void cancel_theLastOpenService_movesTheVisitToResultsReady() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        ServiceDetailResponse response = service.cancel(1L, "  Thú cưng quá hung dữ  ");

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(lastDetailHistory().getReason()).isEqualTo("Thú cưng quá hung dữ");
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        verify(publisher).publishEvent(any(DoctorPriorityCallbackEvent.class));
        verify(publisher).publishEvent(any(ParaclinicalResultsReadyEvent.class));
    }

    @Test
    void cancel_whileOtherServicesAreOpen_keepsTheVisitWaiting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);   // Bác sĩ đổi hướng xử lý
        stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(1L, "Đổi phác đồ");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void cancel_refusedForFinishedServicesAndForTheWrongRoom() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.COMPLETED), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(1L, "x"));

        stubLocks(detail(2L, 101L, ULTRASOUND, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(2L, "x"));
    }

    @Test
    void cancel_alsoCancelsTheServiceInvoiceInTheSameTransaction_BR33() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice invoice = ServiceInvoice.builder().id(50L).visitServiceDetailId(1L)
                .unitPrice(new BigDecimal("250000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(1L)).thenReturn(Optional.of(invoice));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(1L, "Khách xin hủy");

        // Bất biến v3.0: dịch vụ CANCELLED <=> hóa đơn của chính dịch vụ đó CANCELLED
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void cancel_ofAServiceWhoseInvoiceIsAlreadyPaid_isRefused() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice paid = ServiceInvoice.builder().id(50L).visitServiceDetailId(1L).unitPrice(new BigDecimal("250000.00"))
                .vatRate(BigDecimal.ZERO).paymentStatus(PaymentStatus.PAID).build();
        when(invoiceRepository.findByVisitServiceDetailId(1L)).thenReturn(Optional.of(paid));

        assertError(ErrorCode.PAID_SERVICE_CANNOT_BE_CANCELLED, () -> service.cancel(1L, "Khách xin hủy"));   // hoàn tiền ngoài phạm vi
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    // ================================================================== kết quả CĐHA (UC-26)

    @Test
    void submitImaging_savesResultAndAttachments_thenMovesVisitToResultsReady() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(d));
        stubLocks(d, VisitStatus.WAITING_RESULTS);
        when(storage.store(any(), any())).thenReturn(new ImagingStorage.StoredFile("100/a.jpg", "sieu-am.jpg", 1234L, "image/jpeg"));
        when(imagingResultRepository.saveAndFlush(any(ImagingResult.class))).thenAnswer(inv -> {
            ImagingResult r = inv.getArgument(0);
            r.setId(700L);
            return r;
        });
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        ServiceDetailResponse response = service.submitImagingResult(1L, "Gan to nhẹ", "Nghi viêm gan",
                List.of(new MockMultipartFile("files", "sieu-am.jpg", "image/jpeg", new byte[]{1})));

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.COMPLETED);
        verify(attachmentRepository).saveAll(anyList());
        verify(publisher).publishEvent(any(DoctorPriorityCallbackEvent.class));
        verify(publisher).publishEvent(any(ParaclinicalResultsReadyEvent.class));
        verify(storage, never()).deleteQuietly(any());
    }

    @Test
    void submitImaging_whenDatabaseWriteFails_deletesTheFilesAlreadyStored() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS)));
        when(storage.store(any(), any())).thenReturn(new ImagingStorage.StoredFile("100/a.jpg", "a.jpg", 10L, "image/jpeg"));
        // Chỉ định bị xóa / không khóa được trong lúc xử lý -> transaction thất bại sau khi tệp đã lưu
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertError(ErrorCode.SERVICE_DETAIL_NOT_FOUND, () -> service.submitImagingResult(1L, "Mô tả", "Kết luận",
                List.of(new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1}))));

        verify(storage).deleteQuietly("100/a.jpg");   // không để lại tệp mồ côi
    }

    @Test
    void submitImaging_validatesInputBeforeTouchingTheDiskOrDatabase() {
        MockMultipartFile image = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.submitImagingResult(1L, "Mô tả", "Kết luận", List.of(image)));

        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitImagingResult(1L, "  ", "Kết luận", List.of(image)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitImagingResult(1L, "Mô tả", null, List.of(image)));
        assertError(ErrorCode.INVALID_IMAGING_FILE, () -> service.submitImagingResult(1L, "Mô tả", "Kết luận", List.of()));
        assertError(ErrorCode.INVALID_IMAGING_FILE, () -> service.submitImagingResult(1L, "Mô tả", "Kết luận", null));

        verify(storage, never()).store(any(), any());
        verify(detailRepository, never()).findByIdForUpdate(any());
    }

    // ================================================================== fixtures

    @Test
    void submitLab_rejectsAnItemThatDoesNotBelongToTheOrderedService() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(serviceLabItemRepository.findItemIdsByServiceId(5L)).thenReturn(List.of(1L, 4L));   // CBC chỉ gồm WBC, PLT
        LabTestItem glucose = labItem(9L, "GLU");
        when(labTestItemRepository.findAllById(List.of(9L))).thenReturn(List.of(glucose));

        assertError(ErrorCode.LAB_ITEM_NOT_IN_SERVICE, () -> service.submitLabResults(1L,
                new LabResultRequest(List.of(new LabResultItemRequest(9L, new BigDecimal("5.00"), null)))));
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void labForm_listsOnlyTheServicesItemsWithTheRangeOfThePetsSpecies() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.PENDING);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(d));
        when(visitRepository.findWithPetById(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem glucose = labItem(9L, "GLU");
        when(serviceLabItemRepository.findItemIdsByServiceId(5L)).thenReturn(List.of(1L));
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(glucose, wbc));
        LabTestReferenceRange dogRange = range(wbc, "6.00", "17.00");
        LabTestReferenceRange catRange = LabTestReferenceRange.builder().labTestItem(wbc).species(cat())
                .minValue(new BigDecimal("5.50")).maxValue(new BigDecimal("19.50")).build();
        when(rangeRepository.findByLabTestItemIdIn(List.of(1L))).thenReturn(List.of(dogRange, catRange));

        var form = service.labForm(1L);

        assertThat(form).extracting(i -> i.code()).containsExactly("WBC");
        assertThat(form.get(0).ranges()).hasSize(1);
        assertThat(form.get(0).ranges().get(0).minValue()).isEqualByComparingTo("6.00");   // visit() là thú cưng loài Chó
    }

    @Test
    void labForm_withoutConfigurationShowsEveryItem_andIsForTheLabOnly() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        when(detailRepository.findById(1L)).thenReturn(Optional.of(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING)));
        when(visitRepository.findWithPetById(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(labItem(1L, "WBC"), labItem(4L, "PLT")));

        assertThat(service.labForm(1L)).hasSize(2);

        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.labForm(1L));
    }

    /** Khóa chỉ định rồi khóa đợt khám, đúng thứ tự mà dịch vụ thật dùng. */
    private VisitServiceDetail stubLocks(VisitServiceDetail detail, VisitStatus visitStatus) {
        PetVisit visit = visit(detail.getVisitId(), visitStatus, DOCTOR);
        when(detailRepository.findByIdForUpdate(detail.getId())).thenReturn(Optional.of(detail));
        when(visitRepository.findByIdForUpdate(detail.getVisitId())).thenReturn(Optional.of(visit));
        return detail;
    }

    private static LabTestItem labItem(long id, String code) {
        return LabTestItem.builder().id(id).code(code).name(code).unit("10^9/L").build();
    }

    private static LabTestReferenceRange range(LabTestItem item, String min, String max) {
        return LabTestReferenceRange.builder().labTestItem(item).species(dog())
                .minValue(new BigDecimal(min)).maxValue(new BigDecimal(max)).build();
    }

    private VisitServiceDetailHistory lastDetailHistory() {
        ArgumentCaptor<VisitServiceDetailHistory> captor = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
