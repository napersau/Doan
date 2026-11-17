package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.TicketIssuer;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.IMAGING_TECH;
import static com.vetclinic.clinical.testsupport.TestData.LAB_TECH;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BR-34 / SM-07: "Gọi" chỉ cập nhật last_called_at và phát thông báo, không đổi trạng thái hay thứ tự; "Bắt đầu" phiếu phòng. */
@ExtendWith(MockitoExtension.class)
class QueueTicketServiceImplTest {

    private static final ClinicService CBC = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
    private static final ClinicService XRAY = service(6L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00");

    @Mock
    private QueueTicketRepository ticketRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private TicketIssuer ticketIssuer;
    @Mock
    private VisitSupport support;
    @Mock
    private AuthClient authClient;
    @Mock
    private ApplicationEventPublisher publisher;

    private QueueTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(ticketRepository.saveAndFlush(any(QueueTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new QueueTicketServiceImpl(ticketRepository, visitRepository, detailRepository, ticketIssuer, support,
                authClient, publisher, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void stubRoom(long userId, RoleCode role, String room) {
        lenient().when(authClient.getUser(userId)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(userId, "User " + userId, "0900000000", role, room, "ACTIVE")));
    }

    private PatientCalledEvent publishedCall() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        return (PatientCalledEvent) event.getValue();
    }

    // ================================================================== Gọi

    @Test
    void callNext_callsTheHeadOfTheDoctorsTicketQueue_withoutChangingItsStatusOrOrder() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom(DOCTOR, RoleCode.VETERINARIAN, "P101");
        QueueTicket head = ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 4, 0, TicketStatus.WAITING);
        QueueTicket next = ticket(61L, QueueType.DOCTOR, 101L, DOCTOR, 5, 0, TicketStatus.WAITING);
        when(ticketRepository.findDoctorQueue(DOCTOR)).thenReturn(List.of(head, next));
        when(visitRepository.findWithPetById(100L)).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING, DOCTOR)));

        QueueTicketResponse called = service.callNext(QueueType.DOCTOR);

        assertThat(called.id()).isEqualTo(60L);
        assertThat(called.ticketNo()).isEqualTo(4);
        assertThat(called.roomNumber()).isEqualTo("P101");
        assertThat(head.getStatus()).isEqualTo(TicketStatus.WAITING);           // gọi trước, đổi trạng thái sau ("Bắt đầu khám")
        assertThat(head.getLastCalledAt()).isEqualTo(CLOCK.instant());
        assertThat(next.getLastCalledAt()).isNull();
        PatientCalledEvent event = publishedCall();
        assertThat(event.action()).isEqualTo("CALLED");
        assertThat(event.station()).isEqualTo("DOCTOR_ROOM");
        assertThat(event.ticketNo()).isEqualTo(4);
        assertThat(event.visitId()).isEqualTo(100L);
    }

    @Test
    void callNext_emptyQueue_CLIN_1303_whileExamining_CLIN_1302_andWrongRoleIsDenied() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(ticketRepository.findDoctorQueue(DOCTOR)).thenReturn(List.of());
        assertError(ErrorCode.QUEUE_EMPTY, () -> service.callNext(QueueType.DOCTOR));

        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);   // kể cả khi hàng đợi trống
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.callNext(QueueType.DOCTOR));

        // Bác sĩ không gọi hàng đợi phòng; KTV Lab không gọi hàng đợi CĐHA
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(QueueType.LABORATORY));
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(QueueType.IMAGING));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void call_aTicketInTheMiddle_whenTheHeadIsAbsent_onlyAnnounces_theOrderNeverChanges() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubRoom(LAB_TECH, RoleCode.LAB_TECHNICIAN, "P201");
        QueueTicket third = ticket(72L, QueueType.LABORATORY, 102L, null, 3, 0, TicketStatus.WAITING);
        when(ticketRepository.findById(72L)).thenReturn(Optional.of(third));
        when(visitRepository.findWithPetById(102L)).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));

        QueueTicketResponse called = service.call(72L);

        assertThat(called.ticketNo()).isEqualTo(3);
        assertThat(third.getStatus()).isEqualTo(TicketStatus.WAITING);
        assertThat(third.getPriority()).isZero();
        assertThat(third.getIssuedAt()).isEqualTo(ticket(72L, QueueType.LABORATORY, 102L, null, 3, 0, TicketStatus.WAITING).getIssuedAt());
        PatientCalledEvent event = publishedCall();
        assertThat(event.station()).isEqualTo("LAB");
        assertThat(event.roomNumber()).isEqualTo("P201");
        assertThat(event.ticketId()).isEqualTo(72L);
    }

    @Test
    void call_isRefusedForAnotherDoctorsTicket_aTicketNotWaiting_andAnUnknownTicket() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(ticketRepository.findById(60L)).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, OTHER_DOCTOR, 1, 0, TicketStatus.WAITING)));
        when(ticketRepository.findById(61L)).thenReturn(Optional.of(ticket(61L, QueueType.DOCTOR, 101L, DOCTOR, 2, 0, TicketStatus.IN_SERVICE)));

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.call(60L));
        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.call(61L));
        assertError(ErrorCode.TICKET_NOT_FOUND, () -> service.call(99L));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ================================================================== Bắt đầu phiếu phòng

    @Test
    void start_aRoomTicket_movesItToInService_andStartsEveryPendingServiceOfIt() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        QueueTicket t = ticket(80L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING);
        VisitServiceDetail us = detail(1L, 100L, XRAY, ServiceDetailStatus.PENDING);
        VisitServiceDetail xray = detail(2L, 100L, XRAY, ServiceDetailStatus.PENDING);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(ticketRepository.findById(80L)).thenReturn(Optional.of(t));
        when(ticketRepository.findByIdForUpdate(80L)).thenReturn(Optional.of(t));
        when(ticketIssuer.detailIdsOf(80L)).thenReturn(List.of(2L, 1L));
        when(detailRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(us));
        when(detailRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(xray));
        when(support.lock(100L)).thenReturn(visit);
        when(visitRepository.findWithPetById(100L)).thenReturn(Optional.of(visit));

        QueueTicketResponse started = service.start(80L);

        assertThat(started.status()).isEqualTo(TicketStatus.IN_SERVICE);
        assertThat(us.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        assertThat(xray.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        verify(support).recordDetail(1L, ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS, "START_PROCESSING", IMAGING_TECH, null);
        verify(support).recordDetail(2L, ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS, "START_PROCESSING", IMAGING_TECH, null);
    }

    @Test
    void start_isRefusedForADoctorTicket_theWrongRoom_aTicketAlreadyStarted_andAVisitNoLongerWaiting() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        when(ticketRepository.findById(60L)).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.start(60L));   // phiếu Bác sĩ bắt đầu bằng start-exam

        when(ticketRepository.findById(80L)).thenReturn(Optional.of(ticket(80L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING)));
        assertError(ErrorCode.ACCESS_DENIED, () -> service.start(80L));        // KTV Lab không bắt đầu phiếu CĐHA

        QueueTicket started = ticket(70L, QueueType.LABORATORY, 101L, null, 1, 0, TicketStatus.IN_SERVICE);
        when(ticketRepository.findById(70L)).thenReturn(Optional.of(started));
        when(ticketRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(started));
        when(ticketIssuer.detailIdsOf(70L)).thenReturn(List.of(3L));
        when(detailRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(detail(3L, 101L, CBC, ServiceDetailStatus.IN_PROGRESS)));
        when(support.lock(101L)).thenReturn(visit(101L, VisitStatus.WAITING_RESULTS, DOCTOR));
        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.start(70L));

        QueueTicket waiting = ticket(71L, QueueType.LABORATORY, 102L, null, 2, 0, TicketStatus.WAITING);
        when(ticketRepository.findById(71L)).thenReturn(Optional.of(waiting));
        when(ticketRepository.findByIdForUpdate(71L)).thenReturn(Optional.of(waiting));
        when(ticketIssuer.detailIdsOf(71L)).thenReturn(List.of(4L));
        when(detailRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(detail(4L, 102L, CBC, ServiceDetailStatus.PENDING)));
        when(support.lock(102L)).thenReturn(visit(102L, VisitStatus.COMPLETED, DOCTOR));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.start(71L));
        verify(support, never()).recordDetail(any(), any(), any(), any(), any(), any());
    }
}
