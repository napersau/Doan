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
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Không @Transactional: board() gọi OpenFeign; các truy vấn đọc tự chạy trong transaction riêng của repository. */
@Service
@RequiredArgsConstructor
public class DoctorQueueServiceImpl implements DoctorQueueService {

    private final PetVisitRepository visitRepository;
    private final AuthClient authClient;
    private final VisitAssembler assembler;

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
}
