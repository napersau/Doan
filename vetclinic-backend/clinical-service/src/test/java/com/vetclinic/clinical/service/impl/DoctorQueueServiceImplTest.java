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
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorQueueServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private AuthClient authClient;

    private DoctorQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitAssembler assembler = new VisitAssembler(mock(VisitServiceDetailRepository.class), mock(LabTestResultRepository.class),
                mock(ImagingResultRepository.class), mock(ImagingAttachmentRepository.class), mock(ServiceInvoiceRepository.class),
                new VisitTimeline(mock(PetVisitStatusHistoryRepository.class)));
        service = new DoctorQueueServiceImpl(visitRepository, authClient, assembler);
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
}
