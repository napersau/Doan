package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorQueueResponse;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.DoctorQueueService;
import com.vetclinic.clinical.service.QueueOrders;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Không @Transactional ở mức class: board() và việc tra phòng gọi OpenFeign (NGOÀI transaction); các truy vấn đọc tự chạy trong
 * transaction riêng của repository, còn việc ghi (skip) chạy trong transactionTemplate.
 */
@Service
public class DoctorQueueServiceImpl implements DoctorQueueService {

    private final PetVisitRepository visitRepository;
    private final AuthClient authClient;
    private final VisitAssembler assembler;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DoctorQueueServiceImpl(PetVisitRepository visitRepository, AuthClient authClient, VisitAssembler assembler,
                                  ApplicationEventPublisher eventPublisher, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.authClient = authClient;
        this.assembler = assembler;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public QueueBoardResponse board() {
        ApiResponse<List<UserSummaryResponse>> response = authClient.getActiveDoctors();
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        List<UserSummaryResponse> doctors = response.getData();
        Set<Long> doctorIds = doctors.stream().map(UserSummaryResponse::id).collect(Collectors.toSet());

        List<PetVisit> unassigned = visitRepository.findUnassignedWaiting();
        List<PetVisit> assigned = doctorIds.isEmpty() ? List.of() : visitRepository.findQueueOfDoctors(doctorIds);
        Set<Long> busy = visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS));

        List<PetVisit> all = new ArrayList<>(unassigned);
        all.addAll(assigned);
        Map<Long, VisitResponse> byId = assembler.summaries(all).stream()
                .collect(Collectors.toMap(VisitResponse::id, Function.identity()));
        // groupingBy giữ nguyên thứ tự hàng đợi bên trong mỗi Bác sĩ
        Map<Long, List<PetVisit>> byDoctor = assigned.stream().collect(Collectors.groupingBy(PetVisit::getDoctorId));

        List<DoctorQueueResponse> queues = doctors.stream().map(d -> {
            List<VisitResponse> visits = byDoctor.getOrDefault(d.id(), List.of()).stream()
                    .map(v -> byId.get(v.getId()).withAssignedRoom(d.roomNumber())).toList();
            DoctorStatus status = busy.contains(d.id()) ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
            return new DoctorQueueResponse(d.id(), d.fullName(), d.roomNumber(), status, visits.size(), visits);
        }).toList();

        return new QueueBoardResponse(unassigned.stream().map(v -> byId.get(v.getId())).toList(), queues, suggest(queues));
    }

    /** Gợi ý Bác sĩ ÍT NGƯỜI CHỜ NHẤT; hòa thì ưu tiên Bác sĩ đang rảnh, rồi theo id. Chỉ là gợi ý, Lễ tân quyết định. */
    static Long suggest(List<DoctorQueueResponse> queues) {
        return queues.stream()
                .min(Comparator.comparingInt(DoctorQueueResponse::waitingCount)
                        .thenComparingInt(q -> q.status() == DoctorStatus.BUSY ? 1 : 0)
                        .thenComparing(DoctorQueueResponse::doctorId))
                .map(DoctorQueueResponse::doctorId).orElse(null);
    }

    @Override
    public List<VisitResponse> myQueue() {
        return assembler.summaries(visitRepository.findDoctorVisits(SecurityUtils.getCurrentUserId(),
                List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY)));
    }

    // ================================================================== Gọi số / Đẩy lùi (BR-34)

    @Override
    public VisitResponse callNext() {
        Long doctorId = SecurityUtils.getCurrentUserId();
        String room = roomOf(doctorId);   // Feign NGOÀI transaction
        return transactionTemplate.execute(tx -> {
            if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
                throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);   // BR-12: đang khám dở thì chưa được gọi thêm
            }
            List<PetVisit> queue = visitRepository.findDoctorVisits(doctorId, WAITING_AND_READY);
            if (queue.isEmpty()) {
                throw new AppException(ErrorCode.QUEUE_EMPTY);
            }
            PetVisit head = queue.get(0);
            publishCalled(head, ACTION_CALLED, doctorId, room);   // ca CHƯA đổi trạng thái (Q3)
            return assembler.summary(head).withAssignedRoom(room);
        });
    }

    @Override
    public VisitResponse skip(Long visitId) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        String room = roomOf(doctorId);
        return transactionTemplate.execute(tx -> {
            PetVisit visit = visitRepository.findByIdForUpdate(visitId)   // khóa dòng + @Version chống đua
                    .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
            if (visit.getDoctorId() == null || !visit.getDoctorId().equals(doctorId)) {
                throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);
            }
            if (visit.getStatus() != VisitStatus.WAITING && visit.getStatus() != VisitStatus.RESULTS_READY) {
                throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "Chỉ đẩy lùi được ca đang chờ trong hàng đợi (WAITING hoặc RESULTS_READY)");
            }
            if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
                throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
            }
            List<PetVisit> queue = visitRepository.findDoctorVisits(doctorId, WAITING_AND_READY);
            int index = indexOf(queue, visitId);
            Long newOrder = QueueOrders.afterPushBack(queue.stream()
                    .map(v -> new QueueOrders.Entry(v.getQueueOrder(), v.isEmergency() && v.getSkipCount() == 0)).toList(), index);

            visit.setSkipCount(visit.getSkipCount() + 1);   // không giới hạn số lần; ca cấp cứu bị lùi xếp như ca thường
            if (newOrder != null) {
                visit.setQueueOrder(newOrder);
            }
            visitRepository.save(visit);   // trạng thái không đổi nên không ghi pet_visit_status_histories
            publishCalled(visit, ACTION_PUSHED_BACK, doctorId, room);   // báo chủ nuôi bị đẩy lùi

            if (newOrder == null) {
                return assembler.summary(visit).withAssignedRoom(room);   // không còn ai phía sau: giữ nguyên vị trí
            }
            PetVisit next = queue.get(index + 1);   // người kế tiếp lên đầu và được gọi ngay
            publishCalled(next, ACTION_CALLED, doctorId, room);
            return assembler.summary(next).withAssignedRoom(room);
        });
    }

    @Override
    public VisitResponse call(Long visitId) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        String room = roomOf(doctorId);   // Feign NGOÀI transaction
        return transactionTemplate.execute(tx -> {
            PetVisit visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
            if (visit.getDoctorId() == null || !visit.getDoctorId().equals(doctorId)) {
                throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);
            }
            if (visit.getStatus() != VisitStatus.WAITING && visit.getStatus() != VisitStatus.RESULTS_READY) {
                throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "Chỉ gọi được ca đang chờ trong hàng đợi (WAITING hoặc RESULTS_READY)");
            }
            if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
                throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);   // BR-12: đang khám dở thì chưa được gọi thêm
            }
            publishCalled(visit, ACTION_CALLED, doctorId, room);   // không đổi trạng thái, không đổi thứ tự hàng đợi
            return assembler.summary(visit).withAssignedRoom(room);
        });
    }

    private static int indexOf(List<PetVisit> queue, Long visitId) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId().equals(visitId)) {
                return i;
            }
        }
        throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Ca này không nằm trong hàng đợi của bạn");
    }

    private void publishCalled(PetVisit v, String action, Long doctorId, String room) {
        eventPublisher.publishEvent(new PatientCalledEvent(UUID.randomUUID(), clock.instant(), doctorId, action,
                "DOCTOR_ROOM", v.getId(), v.getVisitCode(), v.getPet().getId(), doctorId, null, room, v.getSkipCount()));
    }

    /** Số phòng của chính Bác sĩ để hiển thị khi gọi số; không tra được thì bỏ trống, không làm hỏng việc gọi số. */
    private String roomOf(Long doctorId) {
        try {
            ApiResponse<UserSummaryResponse> response = authClient.getUser(doctorId);
            return response == null || response.getData() == null ? null : response.getData().roomNumber();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final List<VisitStatus> WAITING_AND_READY = List.of(VisitStatus.WAITING, VisitStatus.RESULTS_READY);
    private static final String ACTION_CALLED = "CALLED";
    private static final String ACTION_PUSHED_BACK = "PUSHED_BACK";
}
