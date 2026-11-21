package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.entity.QueueTicketItem;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.QueueTicketItemRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.QueueTicketIssuedEvent;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SM-07 / BR-35: thu tiền xong mới phát phiếu; ca cấp cứu và ca có kết quả CLS là ngoại lệ; dịch vụ cùng phòng gộp một phiếu. */
@ExtendWith(MockitoExtension.class)
class TicketIssuerTest {

    private static final ClinicService EXAM = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
    private static final ClinicService CBC = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
    private static final ClinicService US = service(6L, "IMG_US", ServiceCategory.IMAGING, "250000.00");
    private static final ClinicService XRAY = service(7L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00");
    private static final ClinicService VACCINE = service(8L, "VAC_5IN1", "VACCINATION", "200000.00");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Mock
    private QueueTicketRepository ticketRepository;
    @Mock
    private QueueTicketItemRepository itemRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private ApplicationEventPublisher publisher;

    private TicketIssuer issuer;
    private long nextId = 90L;

    @BeforeEach
    void setUp() {
        issuer = new TicketIssuer(ticketRepository, itemRepository, detailRepository, publisher, CLOCK);
        lenient().when(ticketRepository.saveAndFlush(any(QueueTicket.class))).thenAnswer(inv -> {
            QueueTicket t = inv.getArgument(0);
            t.setId(nextId++);
            return t;
        });
    }

    private QueueTicket savedTicket() {
        ArgumentCaptor<QueueTicket> saved = ArgumentCaptor.forClass(QueueTicket.class);
        verify(ticketRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void paidExamFee_issuesTheNextDoctorTicketOfTheDay_forTheChosenDoctor_andAnnouncesIt() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(ticketRepository.findMaxTicketNo(QueueType.DOCTOR, TODAY)).thenReturn(7);

        issuer.issueForPaidServices(v, List.of(detail(1L, 100L, EXAM, ServiceDetailStatus.PENDING)), RECEPTIONIST);

        QueueTicket t = savedTicket();
        assertThat(t.getQueueType()).isEqualTo(QueueType.DOCTOR);
        assertThat(t.getTicketNo()).isEqualTo(8);   // số lớn nhất trong ngày + 1
        assertThat(t.getDoctorId()).isEqualTo(DOCTOR);
        assertThat(t.getPriority()).isEqualTo(QueueTicket.PRIORITY_NORMAL);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.WAITING);
        assertThat(t.getIssuedBy()).isEqualTo(RECEPTIONIST);
        assertThat(t.getServiceDate()).isEqualTo(TODAY);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        QueueTicketIssuedEvent issued = (QueueTicketIssuedEvent) event.getValue();
        assertThat(issued.queueType()).isEqualTo("DOCTOR");
        assertThat(issued.ticketNo()).isEqualTo(8);
        assertThat(issued.visitId()).isEqualTo(100L);
    }

    @Test
    void emergency_getsPriority2_andTheFirstTicketOfTheDayIsNumber1() {
        PetVisit v = visit(100L, VisitStatus.WAITING, null);
        v.setEmergency(true);
        when(ticketRepository.findMaxTicketNo(QueueType.DOCTOR, TODAY)).thenReturn(null);

        issuer.issueForPaidServices(v, List.of(detail(1L, 100L, EXAM, ServiceDetailStatus.PENDING)), null);

        QueueTicket t = savedTicket();
        assertThat(t.getTicketNo()).isEqualTo(1);
        assertThat(t.getPriority()).isEqualTo(QueueTicket.PRIORITY_EMERGENCY);
        assertThat(t.getDoctorId()).isNull();   // chưa phân công: vào danh sách chờ phân công
    }

    @Test
    void examFeePaidAfterTheExam_orWhenAWaitingTicketExists_issuesNothing() {
        // Ca cấp cứu khám trước, thu phí khám sau: không phát phiếu nữa
        issuer.issueForPaidServices(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR),
                List.of(detail(1L, 100L, EXAM, ServiceDetailStatus.COMPLETED)), RECEPTIONIST);
        // Đã có phiếu DOCTOR còn hiệu lực
        when(ticketRepository.findFirstByVisitIdAndQueueTypeAndStatusIn(101L, QueueType.DOCTOR, TicketStatus.ACTIVE))
                .thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 101L, DOCTOR, 1, 2, TicketStatus.WAITING)));
        issuer.issueForPaidServices(visit(101L, VisitStatus.WAITING, DOCTOR),
                List.of(detail(2L, 101L, EXAM, ServiceDetailStatus.PENDING)), RECEPTIONIST);

        verify(ticketRepository, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void paidParaclinicalServices_ofTheSameRoom_shareOneTicket_andEachRoomGetsItsOwn() {
        PetVisit v = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        VisitServiceDetail lab = detail(2L, 100L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail us = detail(3L, 100L, US, ServiceDetailStatus.PENDING);
        VisitServiceDetail xray = detail(4L, 100L, XRAY, ServiceDetailStatus.PENDING);
        when(ticketRepository.findMaxTicketNo(any(), any())).thenReturn(null);
        // phiếu CĐHA phát cho siêu âm, X-quang gộp vào phiếu đang chờ đó
        QueueTicket imaging = ticket(91L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING);
        when(ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(100L, QueueType.LABORATORY, TicketStatus.WAITING))
                .thenReturn(Optional.empty());
        when(ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(100L, QueueType.IMAGING, TicketStatus.WAITING))
                .thenReturn(Optional.empty(), Optional.of(imaging));

        issuer.issueForPaidServices(v, List.of(lab, us, xray), RECEPTIONIST);

        ArgumentCaptor<QueueTicket> saved = ArgumentCaptor.forClass(QueueTicket.class);
        verify(ticketRepository, Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(QueueTicket::getQueueType).containsExactly(QueueType.LABORATORY, QueueType.IMAGING);
        assertThat(saved.getAllValues()).extracting(QueueTicket::getDoctorId).containsOnlyNulls();
        ArgumentCaptor<QueueTicketItem> items = ArgumentCaptor.forClass(QueueTicketItem.class);
        verify(itemRepository, Mockito.times(3)).save(items.capture());
        assertThat(items.getAllValues()).extracting(QueueTicketItem::getVisitServiceDetailId).containsExactly(2L, 3L, 4L);
        assertThat(items.getAllValues().get(2).getTicketId()).isEqualTo(91L);   // X-quang vào chung phiếu với siêu âm
    }

    @Test
    void inRoomServices_andParaclinicalServicesAlreadyTicketedOrNoLongerPending_getNoTicket() {
        PetVisit v = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        VisitServiceDetail ticketed = detail(2L, 100L, CBC, ServiceDetailStatus.PENDING);
        when(itemRepository.findByVisitServiceDetailId(2L)).thenReturn(Optional.of(QueueTicketItem.of(70L, 2L)));

        issuer.issueForPaidServices(v, List.of(detail(1L, 100L, VACCINE, ServiceDetailStatus.COMPLETED), ticketed,
                detail(3L, 100L, US, ServiceDetailStatus.CANCELLED)), RECEPTIONIST);

        verify(ticketRepository, never()).saveAndFlush(any());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void resultsReady_issuesAPriority1DoctorTicket_forTheMainDoctor_withoutPayment() {
        when(ticketRepository.findMaxTicketNo(QueueType.DOCTOR, TODAY)).thenReturn(12);

        QueueTicket t = issuer.issueResultsReady(visit(100L, VisitStatus.RESULTS_READY, DOCTOR));

        assertThat(t.getPriority()).isEqualTo(QueueTicket.PRIORITY_RESULTS_READY);
        assertThat(t.getDoctorId()).isEqualTo(DOCTOR);
        assertThat(t.getTicketNo()).isEqualTo(13);
        assertThat(t.getIssuedBy()).isNull();   // hệ thống phát
    }

    @Test
    void startDoctorTicket_requiresAWaitingTicket_CLIN_1314() {
        when(ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(100L, QueueType.DOCTOR, TicketStatus.WAITING))
                .thenReturn(Optional.empty());
        assertError(ErrorCode.NO_QUEUE_TICKET, () -> issuer.startDoctorTicket(100L, DOCTOR));

        QueueTicket waiting = ticket(60L, QueueType.DOCTOR, 101L, null, 1, 0, TicketStatus.WAITING);
        when(ticketRepository.findFirstByVisitIdAndQueueTypeAndStatus(101L, QueueType.DOCTOR, TicketStatus.WAITING))
                .thenReturn(Optional.of(waiting));
        when(ticketRepository.save(waiting)).thenReturn(waiting);
        issuer.startDoctorTicket(101L, DOCTOR);
        assertThat(waiting.getStatus()).isEqualTo(TicketStatus.IN_SERVICE);
        assertThat(waiting.getDoctorId()).isEqualTo(DOCTOR);
    }

    @Test
    void cancelActiveTickets_closesEveryWaitingOrInServiceTicketOfTheVisit() {
        QueueTicket a = ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING);
        QueueTicket b = ticket(70L, QueueType.LABORATORY, 100L, null, 1, 0, TicketStatus.IN_SERVICE);
        when(ticketRepository.findByVisitIdAndStatusIn(100L, TicketStatus.ACTIVE)).thenReturn(List.of(a, b));

        issuer.cancelActiveTickets(100L);

        assertThat(a.getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(b.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void refreshRoomTicket_inServiceBecomesDoneWhenNothingIsOpen_waitingBecomesCancelledWhenNothingIsPending() {
        QueueTicket inService = ticket(80L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.IN_SERVICE);
        when(itemRepository.findByVisitServiceDetailId(3L)).thenReturn(Optional.of(QueueTicketItem.of(80L, 3L)));
        when(ticketRepository.findById(80L)).thenReturn(Optional.of(inService));
        when(itemRepository.findByTicketIdIn(List.of(80L))).thenReturn(List.of(QueueTicketItem.of(80L, 3L), QueueTicketItem.of(80L, 4L)));
        when(detailRepository.findAllById(anyCollection())).thenReturn(List.of(
                detail(3L, 100L, US, ServiceDetailStatus.COMPLETED), detail(4L, 100L, XRAY, ServiceDetailStatus.CANCELLED)));
        issuer.refreshRoomTicketOf(3L);
        assertThat(inService.getStatus()).isEqualTo(TicketStatus.DONE);

        QueueTicket waiting = ticket(81L, QueueType.LABORATORY, 101L, null, 2, 0, TicketStatus.WAITING);
        when(itemRepository.findByVisitServiceDetailId(5L)).thenReturn(Optional.of(QueueTicketItem.of(81L, 5L)));
        when(ticketRepository.findById(81L)).thenReturn(Optional.of(waiting));
        when(itemRepository.findByTicketIdIn(List.of(81L))).thenReturn(List.of(QueueTicketItem.of(81L, 5L)));
        when(detailRepository.findAllById(List.of(5L))).thenReturn(List.of(detail(5L, 101L, CBC, ServiceDetailStatus.CANCELLED)));
        issuer.refreshRoomTicketOf(5L);
        assertThat(waiting.getStatus()).isEqualTo(TicketStatus.CANCELLED);   // khách hủy trước khi vào phòng
    }
}
