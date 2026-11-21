package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorQueueResponse;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.TicketInfo;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicket;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.QueueTicketRepository;
import com.vetclinic.clinical.service.DoctorQueueService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Không @Transactional ở mức class: board() gọi OpenFeign lấy danh sách Bác sĩ NGOÀI transaction, phần đọc CSDL chạy trong một
 * transaction chỉ đọc riêng.
 */
@Service
public class DoctorQueueServiceImpl implements DoctorQueueService {

    private final PetVisitRepository visitRepository;
    private final QueueTicketRepository ticketRepository;
    private final AuthClient authClient;
    private final VisitAssembler assembler;
    private final TransactionTemplate readTx;

    public DoctorQueueServiceImpl(PetVisitRepository visitRepository, QueueTicketRepository ticketRepository,
                                  AuthClient authClient, VisitAssembler assembler, PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.ticketRepository = ticketRepository;
        this.authClient = authClient;
        this.assembler = assembler;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public QueueBoardResponse board() {
        ApiResponse<List<UserSummaryResponse>> response = authClient.getActiveDoctors();
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        List<UserSummaryResponse> doctors = response.getData();
        Set<UUID> doctorIds = doctors.stream().map(UserSummaryResponse::id).collect(Collectors.toSet());

        return readTx.execute(s -> {
            List<QueueTicket> unassigned = ticketRepository.findDoctorQueue(null);
            List<QueueTicket> assigned = doctorIds.isEmpty() ? List.of() : ticketRepository.findDoctorQueues(doctorIds);
            Set<UUID> busy = visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS));

            // Mục "chờ thu phí khám": ca vừa tiếp đón chưa có phiếu DOCTOR (chưa nộp phí); không hiện ở hàng đợi Bác sĩ
            List<PetVisit> waitingVisits = visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING);
            Set<Long> withTicket = waitingVisits.isEmpty() ? Set.of()
                    : ticketRepository.findByVisitIdInAndQueueTypeAndStatusIn(
                            waitingVisits.stream().map(PetVisit::getId).toList(), QueueType.DOCTOR, TicketStatus.ACTIVE)
                    .stream().map(QueueTicket::getVisitId).collect(Collectors.toSet());
            List<PetVisit> awaitingPayment = waitingVisits.stream().filter(v -> !withTicket.contains(v.getId())).toList();

            Map<Long, VisitResponse> byVisit = visitsOf(unassigned, assigned, awaitingPayment);
            // groupingBy giữ nguyên thứ tự phiếu bên trong mỗi Bác sĩ
            Map<UUID, List<QueueTicket>> byDoctor = assigned.stream().collect(Collectors.groupingBy(QueueTicket::getDoctorId));

            List<DoctorQueueResponse> queues = doctors.stream().map(d -> {
                List<VisitResponse> visits = withTickets(byDoctor.getOrDefault(d.id(), List.of()), byVisit).stream()
                        .map(v -> v.withAssignedRoom(d.roomNumber())).toList();
                DoctorStatus status = busy.contains(d.id()) ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
                return new DoctorQueueResponse(d.id(), d.fullName(), d.roomNumber(), status, visits.size(), visits);
            }).toList();

            return new QueueBoardResponse(withTickets(unassigned, byVisit), queues, suggest(queues),
                    awaitingPayment.stream().map(v -> byVisit.get(v.getId())).toList());
        });
    }

    /** Gợi ý Bác sĩ ÍT PHIẾU CHỜ NHẤT; hòa thì ưu tiên Bác sĩ đang rảnh, rồi theo id. Chỉ là gợi ý, Lễ tân quyết định. */
    static UUID suggest(List<DoctorQueueResponse> queues) {
        return queues.stream()
                .min(Comparator.comparingInt(DoctorQueueResponse::waitingCount)
                        .thenComparingInt(q -> q.status() == DoctorStatus.BUSY ? 1 : 0)
                        .thenComparing(DoctorQueueResponse::doctorId))
                .map(DoctorQueueResponse::doctorId).orElse(null);
    }

    @Override
    public List<VisitResponse> myQueue() {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        return readTx.execute(s -> {
            List<QueueTicket> tickets = ticketRepository.findDoctorQueue(doctorId);
            return withTickets(tickets, visitsOf(tickets, List.of(), List.of()));
        });
    }

    // ================================================================== helper

    private Map<Long, VisitResponse> visitsOf(List<QueueTicket> a, List<QueueTicket> b, List<PetVisit> extra) {
        Set<Long> ids = new LinkedHashSet<>();
        a.forEach(t -> ids.add(t.getVisitId()));
        b.forEach(t -> ids.add(t.getVisitId()));
        List<PetVisit> visits = new ArrayList<>(ids.isEmpty() ? List.of() : visitRepository.findWithPetByIdIn(ids));
        extra.stream().filter(v -> !ids.contains(v.getId())).forEach(visits::add);
        return assembler.summaries(visits).stream().collect(Collectors.toMap(VisitResponse::id, Function.identity()));
    }

    /** Mỗi phiếu thành một dòng hàng đợi: ca khám kèm số phiếu, mức ưu tiên, giờ gọi gần nhất (giữ thứ tự phiếu). */
    private static List<VisitResponse> withTickets(List<QueueTicket> tickets, Map<Long, VisitResponse> byVisit) {
        return tickets.stream()
                .filter(t -> byVisit.containsKey(t.getVisitId()))
                .map(t -> byVisit.get(t.getVisitId()).withTicket(TicketInfo.of(t)))
                .toList();
    }
}
