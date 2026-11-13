package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorQueueResponse;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitTimeline;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.vetclinic.common.event.PatientCalledEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorQueueServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private ApplicationEventPublisher publisher;

    private DoctorQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitAssembler assembler = new VisitAssembler(mock(VisitServiceDetailRepository.class), mock(LabTestResultRepository.class),
                mock(ImagingResultRepository.class), mock(ImagingAttachmentRepository.class), mock(ServiceInvoiceRepository.class),
                new VisitTimeline(mock(PetVisitStatusHistoryRepository.class)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DoctorQueueServiceImpl(visitRepository, authClient, assembler, publisher, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static UserSummaryResponse doctor(long id, String room) {
        return new UserSummaryResponse(id, "BS " + id, "0911111111", RoleCode.VETERINARIAN, room, "ACTIVE");
    }

    @Test
    void board_hasTheUnassignedListAndOneQueuePerDoctor_withWaitingCountsRoomsAndASuggestion() {
        when(authClient.getActiveDoctors()).thenReturn(ApiResponse.success(List.of(
                doctor(DOCTOR, "P101"), doctor(OTHER_DOCTOR, "P102"))));
        when(visitRepository.findUnassignedWaiting()).thenReturn(List.of(
                visit(1L, VisitStatus.WAITING, null), visit(2L, VisitStatus.WAITING, null)));
        // Hàng đợi của DOCTOR: ca có kết quả (RESULTS_READY) đứng đầu, rồi ca WAITING; OTHER_DOCTOR chưa có ai
        when(visitRepository.findQueueOfDoctors(Set.of(DOCTOR, OTHER_DOCTOR))).thenReturn(List.of(
                visit(3L, VisitStatus.RESULTS_READY, DOCTOR), visit(4L, VisitStatus.WAITING, DOCTOR)));
        when(visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS))).thenReturn(Set.of(DOCTOR));

        QueueBoardResponse board = service.board();

        assertThat(board.unassigned()).extracting(VisitResponse::id).containsExactly(1L, 2L);
        DoctorQueueResponse first = board.doctors().get(0);
        assertThat(first.doctorId()).isEqualTo(DOCTOR);
        assertThat(first.status()).isEqualTo(DoctorStatus.BUSY);
        assertThat(first.waitingCount()).isEqualTo(2);
        assertThat(first.visits()).extracting(VisitResponse::id).containsExactly(3L, 4L);
        assertThat(first.visits()).extracting(VisitResponse::assignedRoom).containsOnly("P101");
        DoctorQueueResponse second = board.doctors().get(1);
        assertThat(second.status()).isEqualTo(DoctorStatus.AVAILABLE);
        assertThat(second.waitingCount()).isZero();
        assertThat(board.suggestedDoctorId()).isEqualTo(OTHER_DOCTOR);   // ít người chờ nhất
    }

    @Test
    void suggest_prefersTheFewestWaiting_thenAnAvailableDoctor_thenTheLowestId() {
        var busy = new DoctorQueueResponse(1L, "A", "P1", DoctorStatus.BUSY, 0, List.of());
        var free = new DoctorQueueResponse(2L, "B", "P2", DoctorStatus.AVAILABLE, 0, List.of());
        var longer = new DoctorQueueResponse(3L, "C", "P3", DoctorStatus.AVAILABLE, 4, List.of());

        assertThat(DoctorQueueServiceImpl.suggest(List.of(busy, free, longer))).isEqualTo(2L);   // hòa số chờ: ưu tiên bác sĩ rảnh
        assertThat(DoctorQueueServiceImpl.suggest(List.of(longer, busy))).isEqualTo(1L);          // ít người chờ hơn thắng dù đang bận
        assertThat(DoctorQueueServiceImpl.suggest(List.of())).isNull();
    }

    @Test
    void board_failsAsServiceUnavailableWhenAuthReturnsNothing() {
        when(authClient.getActiveDoctors()).thenReturn(null);

        assertError(ErrorCode.SERVICE_UNAVAILABLE, () -> service.board());
    }

    @Test
    void myQueue_isTheCurrentDoctorsWaitingAndResultsReadyVisits() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findDoctorVisits(DOCTOR, List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY))).thenReturn(List.of(
                visit(3L, VisitStatus.RESULTS_READY, DOCTOR), visit(4L, VisitStatus.WAITING, DOCTOR)));

        List<VisitResponse> queue = service.myQueue();

        assertThat(queue).extracting(VisitResponse::id).containsExactly(3L, 4L);
        verify(visitRepository).findDoctorVisits(DOCTOR, List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY));
    }

    // ================================================================== Gọi số / Đẩy lùi (BR-34)

    private static final List<VisitStatus> QUEUE_STATUSES = List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY);

    private void stubRoom(String room) {
        lenient().when(authClient.getUser(DOCTOR)).thenReturn(ApiResponse.success(doctor(DOCTOR, room)));
    }

    private static PetVisit queued(long id, VisitStatus status, long queueOrder) {
        PetVisit v = visit(id, status, DOCTOR);
        v.setQueueOrder(queueOrder);
        return v;
    }

    private List<PatientCalledEvent> publishedCalls() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().filter(PatientCalledEvent.class::isInstance).map(PatientCalledEvent.class::cast).toList();
    }

    @Test
    void callNext_picksTheHead_publishesCalled_andDoesNotChangeTheStatus() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        // Ca có kết quả đứng đầu hàng đợi (Q5), rồi ca WAITING
        PetVisit head = queued(3L, VisitStatus.RESULTS_READY, 100L);
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of(head, queued(4L, VisitStatus.WAITING, 200L)));

        VisitResponse response = service.callNext();

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.status()).isEqualTo(VisitStatus.RESULTS_READY);   // Q3: gọi trước, đổi trạng thái sau
        assertThat(response.assignedRoom()).isEqualTo("P101");
        verify(visitRepository, never()).save(any());
        PatientCalledEvent call = publishedCalls().get(0);
        assertThat(call.action()).isEqualTo("CALLED");
        assertThat(call.station()).isEqualTo("DOCTOR_ROOM");
        assertThat(call.visitId()).isEqualTo(3L);
        assertThat(call.roomNumber()).isEqualTo("P101");
        assertThat(call.routingKey()).isEqualTo("visit.patient_called");
    }

    @Test
    void callNext_refusedWhileTheDoctorIsExamining_andWhenTheQueueIsEmpty() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.callNext());   // CLIN_1302

        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(false);
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of());
        assertError(ErrorCode.QUEUE_EMPTY, () -> service.callNext());            // CLIN_1303
    }

    @Test
    void skip_pushesTheCalledVisitBehindTheNextOne_andCallsTheNext() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit a = queued(1L, VisitStatus.WAITING, 100L);
        PetVisit b = queued(2L, VisitStatus.WAITING, 200L);
        PetVisit c = queued(3L, VisitStatus.WAITING, 300L);
        when(visitRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(a));
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of(a, b, c));

        VisitResponse response = service.skip(1L);

        assertThat(a.getSkipCount()).isEqualTo(1);
        assertThat(a.getQueueOrder()).isEqualTo(250L);                 // giữa B (200) và C (300): ngay sau người kế tiếp
        assertThat(a.getStatus()).isEqualTo(VisitStatus.WAITING);      // trạng thái KHÔNG đổi
        assertThat(response.id()).isEqualTo(2L);                       // người kế tiếp được gọi ngay
        List<PatientCalledEvent> calls = publishedCalls();
        assertThat(calls).extracting(PatientCalledEvent::action).containsExactly("PUSHED_BACK", "CALLED");
        assertThat(calls).extracting(PatientCalledEvent::visitId).containsExactly(1L, 2L);
        assertThat(calls.get(0).skipCount()).isEqualTo(1);
    }

    @Test
    void skip_ofAResultsReadyVisit_keepsItResultsReady_EX10() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit ready = queued(1L, VisitStatus.RESULTS_READY, 100L);
        PetVisit next = queued(2L, VisitStatus.WAITING, 200L);
        when(visitRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ready));
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of(ready, next));

        service.skip(1L);

        assertThat(ready.getStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        assertThat(ready.getQueueOrder()).isEqualTo(201L);   // chỉ có một người phía sau: người đó + 1
    }

    @Test
    void skip_withNobodyBehind_keepsThePositionButCountsTheMiss() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit only = queued(1L, VisitStatus.WAITING, 100L);
        when(visitRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(only));
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of(only));

        VisitResponse response = service.skip(1L);

        assertThat(only.getQueueOrder()).isEqualTo(100L);   // giữ nguyên vị trí
        assertThat(only.getSkipCount()).isEqualTo(1);
        assertThat(response.id()).isEqualTo(1L);            // không có ai để gọi tiếp
        assertThat(publishedCalls()).extracting(PatientCalledEvent::action).containsExactly("PUSHED_BACK");
    }

    @Test
    void skip_ofAnEmergency_losesItsPriorityAndIsQueuedLikeARegularCase_EX04() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit emergency = queued(1L, VisitStatus.WAITING, 100L);
        emergency.setEmergency(true);
        PetVisit regular = queued(2L, VisitStatus.WAITING, 200L);
        when(visitRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(emergency));
        when(visitRepository.findDoctorVisits(DOCTOR, QUEUE_STATUSES)).thenReturn(List.of(emergency, regular));

        service.skip(1L);

        assertThat(emergency.getSkipCount()).isEqualTo(1);       // skip_count > 0: xếp như ca thường từ nay
        assertThat(emergency.getQueueOrder()).isEqualTo(201L);   // ngay sau ca thường kế tiếp
    }

    @Test
    void skip_isRefusedForOthersVisits_wrongStatus_orWhileExamining() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit others = visit(1L, VisitStatus.WAITING, OTHER_DOCTOR);
        PetVisit inProgress = visit(2L, VisitStatus.IN_PROGRESS, DOCTOR);
        PetVisit mine = queued(3L, VisitStatus.WAITING, 100L);
        when(visitRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(others));
        when(visitRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(inProgress));
        when(visitRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(mine));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.skip(1L));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.skip(2L));
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.skip(3L));
        verify(visitRepository, never()).save(any());
    }

    @Test
    void call_aVisitInTheMiddle_onlyAnnounces_neverReordersNorChangesTheStatus() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        PetVisit a = queued(1L, VisitStatus.WAITING, 100L);
        PetVisit c = queued(3L, VisitStatus.WAITING, 300L);
        when(visitRepository.findById(3L)).thenReturn(Optional.of(c));

        VisitResponse response = service.call(3L);   // khách đầu hàng vắng: gọi thẳng ca thứ ba

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.assignedRoom()).isEqualTo("P101");
        assertThat(c.getStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(c.getQueueOrder()).isEqualTo(300L);
        assertThat(a.getQueueOrder()).isEqualTo(100L);
        assertThat(c.getSkipCount()).isZero();   // không còn "bị lùi": gọi lại bao nhiêu lần cũng không mất ưu tiên
        verify(visitRepository, never()).save(any());
        List<PatientCalledEvent> calls = publishedCalls();
        assertThat(calls).extracting(PatientCalledEvent::action).containsExactly("CALLED");
        assertThat(calls.get(0).visitId()).isEqualTo(3L);
    }

    @Test
    void call_isRefusedForOthersVisits_wrongStatus_whileExamining_andUnknownVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom("P101");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit(1L, VisitStatus.WAITING, OTHER_DOCTOR)));
        when(visitRepository.findById(2L)).thenReturn(Optional.of(visit(2L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(visitRepository.findById(3L)).thenReturn(Optional.of(queued(3L, VisitStatus.WAITING, 100L)));
        when(visitRepository.findById(9L)).thenReturn(Optional.empty());
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.call(1L));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.call(2L));
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.call(3L));
        assertError(ErrorCode.VISIT_NOT_FOUND, () -> service.call(9L));
        assertThat(publishedCalls()).isEmpty();
    }
}
