package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.QueueTicketService;
import com.vetclinic.clinical.service.TicketIssuer;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Không @Transactional ở mức class: tra số phòng qua OpenFeign NGOÀI transaction, việc ghi chạy trong TransactionTemplate
 * (READ_COMMITTED, cùng thứ tự khóa với các thao tác cận lâm sàng: chỉ định -> đợt khám -> phiếu).
 */
@Service
public class QueueTicketServiceImpl implements QueueTicketService {

    private static final String ACTION_CALLED = "CALLED";

    private final QueueTicketRepository ticketRepository;
    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final TicketIssuer ticketIssuer;
    private final VisitSupport support;
    private final AuthClient authClient;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate tx;

    public QueueTicketServiceImpl(QueueTicketRepository ticketRepository, PetVisitRepository visitRepository,
                                  VisitServiceDetailRepository detailRepository, TicketIssuer ticketIssuer, VisitSupport support,
                                  AuthClient authClient, ApplicationEventPublisher eventPublisher, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.ticketRepository = ticketRepository;
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.ticketIssuer = ticketIssuer;
        this.support = support;
        this.authClient = authClient;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ================================================================== Gọi (Q4, Q5)

    @Override
    public QueueTicketResponse callNext(QueueType type) {
        UUID userId = SecurityUtils.getCurrentUserId();
        assertCanServe(type);
        String room = roomOf(userId);   // Feign NGOÀI transaction
        return tx.execute(s -> {
            // Q3: Bác sĩ đang khám dở thì chưa gọi thêm (CLIN_1302), xét trước khi xét hàng đợi trống (CLIN_1303)
            if (type == QueueType.DOCTOR && visitRepository.existsByDoctorIdAndStatus(userId, VisitStatus.IN_PROGRESS)) {
                throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
            }
            List<QueueTicket> queue = type == QueueType.DOCTOR
                    ? ticketRepository.findDoctorQueue(userId)
                    : ticketRepository.findRoomQueue(type, List.of(TicketStatus.WAITING));
            if (queue.isEmpty()) {
                throw new AppException(ErrorCode.QUEUE_EMPTY);
            }
            return doCall(queue.get(0), userId, room);
        });
    }

    @Override
    public QueueTicketResponse call(Long ticketId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        QueueType type = ticketRepository.findById(ticketId).map(QueueTicket::getQueueType)
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        assertCanServe(type);
        String room = roomOf(userId);   // Feign NGOÀI transaction
        return tx.execute(s -> {
            QueueTicket ticket = ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
            if (type == QueueType.DOCTOR && !userId.equals(ticket.getDoctorId())) {
                throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);   // CLIN_1311: phiếu của hàng đợi Bác sĩ khác
            }
            return doCall(ticket, userId, room);
        });
    }

    private QueueTicketResponse doCall(QueueTicket ticket, UUID userId, String room) {
        if (ticket.getStatus() != TicketStatus.WAITING) {
            throw new AppException(ErrorCode.TICKET_NOT_WAITING);   // CLIN_1313
        }
        if (ticket.getQueueType() == QueueType.DOCTOR
                && visitRepository.existsByDoctorIdAndStatus(userId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);   // BR-12: đang khám dở thì chưa gọi thêm
        }
        ticket.setLastCalledAt(clock.instant());   // chỉ để hiển thị "Đã gọi HH:mm"; thứ tự và trạng thái không đổi
        ticketRepository.saveAndFlush(ticket);
        PetVisit visit = visitRepository.findWithPetById(ticket.getVisitId())
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        eventPublisher.publishEvent(new PatientCalledEvent(UUID.randomUUID(), clock.instant(), userId, ACTION_CALLED,
                station(ticket.getQueueType()), visit.getId(), visit.getVisitCode(), visit.getPet().getId(),
                visit.getDoctorId(), ticket.getId(), ticket.getTicketNo(), room));   // gửi sau commit
        return toResponse(ticket, visit, room);
    }

    // ================================================================== Bắt đầu phiếu phòng XN / CĐHA

    @Override
    public QueueTicketResponse start(Long ticketId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        QueueType type = ticketRepository.findById(ticketId).map(QueueTicket::getQueueType)
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
        if (type == QueueType.DOCTOR) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Phiếu phòng Bác sĩ bắt đầu bằng \"Bắt đầu khám\" / \"Bắt đầu Giai đoạn 2\" trên hồ sơ ca");
        }
        assertCanServe(type);
        return tx.execute(s -> {
            // Khóa theo cùng thứ tự với các thao tác cận lâm sàng khác: các chỉ định -> đợt khám -> phiếu
            List<UUID> detailIds = ticketIssuer.detailIdsOf(ticketId).stream().sorted().toList();
            List<VisitServiceDetail> details = detailIds.stream()
                    .map(id -> detailRepository.findByIdForUpdate(id)
                            .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND)))
                    .toList();
            QueueTicket peek = ticketRepository.findById(ticketId).orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
            PetVisit visit = support.lock(peek.getVisitId());
            QueueTicket ticket = ticketRepository.findByIdForUpdate(ticketId)
                    .orElseThrow(() -> new AppException(ErrorCode.TICKET_NOT_FOUND));
            if (ticket.getStatus() != TicketStatus.WAITING) {
                throw new AppException(ErrorCode.TICKET_NOT_WAITING);
            }
            if (visit.getStatus() != VisitStatus.WAITING_RESULTS) {
                throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Đợt khám không còn ở trạng thái chờ kết quả cận lâm sàng");
            }
            boolean started = false;
            for (VisitServiceDetail d : details) {
                if (d.getStatus() == ServiceDetailStatus.PENDING) {
                    d.setStatus(ServiceDetailStatus.IN_PROGRESS);
                    detailRepository.save(d);
                    support.recordDetail(d.getId(), ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS,
                            "START_PROCESSING", userId, null);
                    started = true;
                }
            }
            if (!started) {
                throw new AppException(ErrorCode.TICKET_NOT_WAITING, "Phiếu không còn dịch vụ nào chờ thực hiện");
            }
            ticket.setStatus(TicketStatus.IN_SERVICE);
            ticketRepository.saveAndFlush(ticket);   // @Version: hai người cùng bấm "Bắt đầu" thì người đến sau nhận SYS_1005
            return toResponse(ticket, visitRepository.findWithPetById(visit.getId()).orElse(visit), null);
        });
    }

    // ================================================================== helper

    /** Bác sĩ chỉ thao tác hàng đợi Bác sĩ; KTV chỉ thao tác hàng đợi phòng mình. */
    private static void assertCanServe(QueueType type) {
        RoleCode role = SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        boolean ok = switch (type) {
            case DOCTOR -> role == RoleCode.VETERINARIAN;
            case LABORATORY -> role == RoleCode.LAB_TECHNICIAN;
            case IMAGING -> role == RoleCode.IMAGING_TECHNICIAN;
        };
        if (!ok) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static String station(QueueType type) {
        return switch (type) {
            case DOCTOR -> "DOCTOR_ROOM";
            case LABORATORY -> "LAB";
            case IMAGING -> "IMAGING";
        };
    }

    private static QueueTicketResponse toResponse(QueueTicket t, PetVisit v, String room) {
        return new QueueTicketResponse(t.getId(), t.getQueueType(), t.getTicketNo(), t.getPriority(), t.getStatus(),
                t.getIssuedAt(), t.getLastCalledAt(), v.getId(), v.getVisitCode(), v.getPet() == null ? null : v.getPet().getName(),
                t.getDoctorId(), room);
    }

    /** Số phòng của người gọi để hiển thị; không tra được thì bỏ trống, không làm hỏng việc gọi số. */
    private String roomOf(UUID userId) {
        try {
            ApiResponse<UserSummaryResponse> response = authClient.getUser(userId);
            return response == null || response.getData() == null ? null : response.getData().roomNumber();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
