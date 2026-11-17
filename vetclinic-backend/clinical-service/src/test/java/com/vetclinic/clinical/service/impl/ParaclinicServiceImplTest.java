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
    private com.vetclinic.clinical.repository.QueueTicketRepository ticketRepository;
    @Mock
    private com.vetclinic.clinical.repository.QueueTicketItemRepository ticketItemRepository;
    @Mock
    private com.vetclinic.clinical.service.TicketIssuer ticketIssuer;

    private ParaclinicServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository,
                detailHistoryRepository, invoiceRepository, ticketIssuer, publisher, CLOCK);
        VisitAssembler assembler = new VisitAssembler(detailRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, invoiceRepository, new VisitTimeline(historyRepository), ticketRepository);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ParaclinicServiceImpl(detailRepository, detailHistoryRepository, ticketRepository, ticketItemRepository,
                ticketIssuer, visitRepository,
                labTestItemRepository, rangeRepository, serviceLabItemRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, support, assembler, storage, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================== hàng đợi (phiếu xếp hàng, 17/11/2026)

    private static com.vetclinic.clinical.entity.QueueTicket ticket(long id, com.vetclinic.clinical.constant.QueueType type,
                                                                    long visitId, int no, int priority,
                                                                    com.vetclinic.clinical.constant.TicketStatus status) {
        return com.vetclinic.clinical.entity.QueueTicket.builder().id(id).queueType(type).serviceDate(java.time.LocalDate.of(2026, 10, 30))
                .ticketNo(no).visitId(visitId).priority(priority).status(status)
                .issuedAt(java.time.Instant.parse("2026-10-30T02:00:00Z").plusSeconds(id)).build();
    }

    private static com.vetclinic.clinical.entity.QueueTicketItem item(long ticketId, long detailId) {
        return com.vetclinic.clinical.entity.QueueTicketItem.of(ticketId, detailId);
    }

    @Test
    void queue_followsTheTicketOrder_expandsEachTicketIntoItsOpenServices_andExposesTheLabStage() {
        PetVisit normal = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit emergency = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        emergency.setEmergency(true);
        PetVisit other = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);

        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d3 = detail(3L, 12L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d4 = detail(4L, 12L, CBC, ServiceDetailStatus.CANCELLED);   // dịch vụ đã hủy của phiếu: không hiện

        var lab = com.vetclinic.clinical.constant.QueueType.LABORATORY;
        var waiting = com.vetclinic.clinical.constant.TicketStatus.WAITING;
        // Thứ tự do truy vấn phiếu quyết định (priority DESC, issued_at, id): service giữ nguyên, không tự sắp lại
        when(ticketRepository.findRoomQueue(lab, com.vetclinic.clinical.constant.TicketStatus.ACTIVE)).thenReturn(List.of(
                ticket(71L, lab, 11L, 2, 2, waiting),
                ticket(70L, lab, 10L, 1, 0, com.vetclinic.clinical.constant.TicketStatus.IN_SERVICE),
                ticket(72L, lab, 12L, 3, 0, waiting)));
        when(ticketItemRepository.findByTicketIdIn(anyCollection())).thenReturn(List.of(
                item(70L, 1L), item(71L, 2L), item(72L, 3L), item(72L, 4L)));
        when(detailRepository.findAllById(anyCollection())).thenReturn(List.of(d1, d2, d3, d4));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(normal, emergency, other));
        when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(d1, d2, d3, d4));
        // d1 đã lấy mẫu xong (đang phân tích); d2, d3 mới chỉ định
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                history(1L, "ORDERED"), history(2L, "ORDERED"), history(3L, "ORDERED"), history(1L, "START_PROCESSING"),
                history(1L, "SAMPLE_COLLECTED")));

        List<QueueItemResponse> queue = service.queue(ServiceCategory.LABORATORY);

        assertThat(queue).extracting(QueueItemResponse::serviceDetailId).containsExactly(2L, 1L, 3L);
        assertThat(queue).extracting(QueueItemResponse::ticketNo).containsExactly(2, 1, 3);
        assertThat(queue).extracting(QueueItemResponse::ticketId).containsExactly(71L, 70L, 72L);
        assertThat(queue.get(0).emergency()).isTrue();
        assertThat(queue.get(0).ticketPriority()).isEqualTo(2);
        assertThat(queue).extracting(QueueItemResponse::stage).containsExactly("QUEUED", "ANALYZING", "QUEUED");
        assertThat(queue).extracting(QueueItemResponse::labStatus).containsOnlyNulls();   // huy hiệu liên phòng chỉ có ở hàng đợi CĐHA
    }

    @Test
    void queue_isEmptyWhenNoTicketHasBeenIssued_unpaidServicesNeverShowUp() {
        when(ticketRepository.findRoomQueue(com.vetclinic.clinical.constant.QueueType.IMAGING,
                com.vetclinic.clinical.constant.TicketStatus.ACTIVE)).thenReturn(List.of());
        assertThat(service.queue(ServiceCategory.IMAGING)).isEmpty();
        verify(detailRepository, never()).findAllById(anyCollection());
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
        var img = com.vetclinic.clinical.constant.QueueType.IMAGING;
        var waiting = com.vetclinic.clinical.constant.TicketStatus.WAITING;
        when(ticketRepository.findRoomQueue(img, com.vetclinic.clinical.constant.TicketStatus.ACTIVE)).thenReturn(List.of(
                ticket(80L, img, 10L, 1, 0, waiting), ticket(81L, img, 11L, 2, 0, waiting), ticket(82L, img, 12L, 3, 0, waiting)));
        when(ticketItemRepository.findByTicketIdIn(anyCollection())).thenReturn(List.of(item(80L, 1L), item(81L, 2L), item(82L, 3L)));
        when(detailRepository.findAllById(anyCollection())).thenReturn(List.of(img1, img2, img3));
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

        service.cancel(1L, "Khách từ chối tẩy giun", null);

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(publisher, never()).publishEvent(any(Object.class));   // dịch vụ tại phòng không ảnh hưởng RESULTS_READY
    }

    @Test
    void cancel_ofAnInRoomService_refusedWhenTheVisitIsLocked_andTheExamFeeCannotBeCancelledAlone() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(1L, "x", null));

        stubLocks(detail(2L, 101L, EXAM, ServiceDetailStatus.IN_PROGRESS), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(2L, "x", null));   // dùng "Hủy ca / Chuyển viện"
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
        verify(publisher, org.mockito.Mockito.times(1)).publishEvent(event.capture());   // chỉ visit.results_ready (v3.0)
        ParaclinicalResultsReadyEvent ready = event.getAllValues().stream().filter(ParaclinicalResultsReadyEvent.class::isInstance)
                .map(ParaclinicalResultsReadyEvent.class::cast).findFirst().orElseThrow();
        assertThat(ready.visitId()).isEqualTo(100L);
        assertThat(ready.doctorId()).isEqualTo(DOCTOR);
        assertThat(ready.actorUserId()).isNull();
    }

    @Test
    void resultsReady_Q6_issuesAPriorityDoctorTicket_andClosesTheRoomTicket() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(visit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of(1L))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId(1L, 1L)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        service.submitLabResults(1L, new LabResultRequest(List.of(new LabResultItemRequest(1L, new BigDecimal("10.00"), null))));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        verify(ticketIssuer).refreshRoomTicketOf(1L);       // phiếu phòng Lab -> DONE
        verify(ticketIssuer).issueResultsReady(visit);      // phiếu DOCTOR priority 1, không thu tiền
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

        ServiceDetailResponse response = service.cancel(1L, "  Thú cưng quá hung dữ  ", null);

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(lastDetailHistory().getReason()).isEqualTo("Thú cưng quá hung dữ");
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        verify(publisher).publishEvent(any(ParaclinicalResultsReadyEvent.class));
    }

    @Test
    void cancel_whileOtherServicesAreOpen_keepsTheVisitWaiting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);   // Bác sĩ đổi hướng xử lý
        stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(1L, "Đổi phác đồ", null);

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void cancel_refusedForFinishedServicesAndForTheWrongRoom() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.COMPLETED), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(1L, "x", null));

        stubLocks(detail(2L, 101L, ULTRASOUND, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(2L, "x", null));
    }

    @Test
    void cancel_alsoCancelsTheServiceInvoiceInTheSameTransaction_BR33() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice invoice = ServiceInvoice.builder().id(50L).visitServiceDetailId(1L)
                .unitPrice(new BigDecimal("250000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(1L)).thenReturn(Optional.of(invoice));
        when(detailRepository.countByVisitAndCategoriesAndStatuses(100L, ServiceCategory.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(1L, "Khách xin hủy", null);

        // Bất biến v3.0: dịch vụ CANCELLED <=> hóa đơn của chính dịch vụ đó CANCELLED
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(invoiceRepository).save(invoice);
    }

    private ServiceInvoice paidInvoice(long detailId, String price) {
        ServiceInvoice paid = ServiceInvoice.builder().id(50L).visitServiceDetailId(detailId).unitPrice(new BigDecimal(price))
                .vatRate(BigDecimal.ZERO).paymentStatus(PaymentStatus.PAID).build();
        when(invoiceRepository.findByVisitServiceDetailId(detailId)).thenReturn(Optional.of(paid));
        return paid;
    }

    @Test
    void cancel_ofAPaidParaclinicalService_byTheCustomer_keepsTheInvoicePaid_noRefund() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice paid = paidInvoice(1L, "250000.00");

        service.cancel(1L, "Khách xin về, không làm nữa", "CUSTOMER");

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);   // khách tự hủy: không hoàn tiền
        verify(invoiceRepository, never()).save(paid);
        verify(ticketIssuer).refreshRoomTicketOf(1L);
    }

    @Test
    void cancel_ofAPaidParaclinicalService_byTheClinic_refundsTheInvoice() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        ServiceInvoice paid = paidInvoice(1L, "200000.00");

        service.cancel(1L, "Máy siêu âm hỏng", "CLINIC");

        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(invoiceRepository).save(paid);
    }

    @Test
    void cancel_withoutAReasonType_defaultsToClinicForTheDoctor_andCustomerForTheTechnician() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice byDoctor = paidInvoice(1L, "250000.00");
        service.cancel(1L, "Đổi hướng điều trị", null);
        assertThat(byDoctor.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice byTech = paidInvoice(2L, "250000.00");
        service.cancel(2L, "Khách bỏ về", null);
        assertThat(byTech.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void cancel_ofACompletedInRoomService_isRefusedAsWrongState() {
        // Sau "Mở lại hồ sơ" (T14) dịch vụ tại phòng đã làm xong không hủy được nữa
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.COMPLETED), VisitStatus.IN_PROGRESS);

        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(1L, "Muốn hủy", null));
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
