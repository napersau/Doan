package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorStatusResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.DoctorStatusService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Không @Transactional: board() gọi OpenFeign, các truy vấn đọc tự chạy trong transaction riêng của repository. */
@Service
@RequiredArgsConstructor
public class DoctorStatusServiceImpl implements DoctorStatusService {

    private final PetVisitRepository visitRepository;
    private final com.vetclinic.clinical.repository.QueueTicketRepository ticketRepository;
    private final AuthClient authClient;

    @Override
    public DoctorStatus statusOf(Long doctorId) {
        return visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)
                ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
    }

    @Override
    public List<DoctorStatusResponse> board() {
        ApiResponse<List<UserSummaryResponse>> response = authClient.getActiveDoctors();
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        Set<Long> busy = visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS));
        Map<Long, Integer> waiting = new HashMap<>();
        for (Object[] row : ticketRepository.countWaitingByDoctor()) {   // số phiếu DOCTOR đang chờ (ca chưa nộp phí khám không tính)
            waiting.put((Long) row[0], ((Long) row[1]).intValue());
        }

        return response.getData().stream().map(d -> {
            DoctorStatus status = busy.contains(d.id()) ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
            return new DoctorStatusResponse(d.id(), d.fullName(), d.roomNumber(), status, waiting.getOrDefault(d.id(), 0));
        }).toList();
    }
}
