package com.vetclinic.appointment.service;

import com.vetclinic.appointment.client.AuthClient;
import com.vetclinic.appointment.client.PetClient;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.appointment.dto.response.UserSummaryResponse;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hoàn thiện response của lịch hẹn bằng các thông tin KHÔNG còn nằm trong bảng appointments (lược đồ v3.0), mỗi loại bằng
 * MỘT lời gọi theo lô (không N+1):
 * <ul>
 *   <li>thú cưng ở clinical-service: tên thú cưng và <b>ownerId</b> (appointments không còn cột owner_id);</li>
 *   <li>chủ nuôi ở auth-service: họ tên, số điện thoại;</li>
 *   <li>appointment_status_histories (DB của chính service này): người/lý do/thời điểm duyệt và hủy, lần cập nhật cuối
 *       (appointments không còn confirmed_*, cancelled_*, cancel_reason, updated_at).</li>
 * </ul>
 * Gọi ở tầng controller, ngoài mọi transaction. Chỉ là phần hiển thị: service kia tạm sập thì để null, không làm hỏng danh sách.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentEnricher {

    private final AuthClient authClient;
    private final PetClient petClient;
    private final AppointmentStatusHistoryRepository historyRepository;

    public AppointmentResponse enrich(AppointmentResponse appointment) {
        return enrichAll(List.of(appointment)).get(0);
    }

    public List<AppointmentResponse> enrichAll(List<AppointmentResponse> appointments) {
        if (appointments.isEmpty()) {
            return appointments;
        }
        Map<Long, PetSummaryResponse> pets = pets(appointments.stream().map(AppointmentResponse::petId).collect(Collectors.toSet()));
        Map<Long, UserSummaryResponse> owners = owners(pets.values().stream()
                .map(PetSummaryResponse::ownerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, List<AppointmentStatusHistory>> histories = histories(
                appointments.stream().map(AppointmentResponse::id).collect(Collectors.toSet()));

        return appointments.stream().map(a -> {
            PetSummaryResponse pet = pets.get(a.petId());
            Long ownerId = pet == null ? null : pet.ownerId();
            UserSummaryResponse owner = ownerId == null ? null : owners.get(ownerId);
            AppointmentResponse result = a.withOwnerId(ownerId)
                    .withNames(pet == null ? null : pet.name(), owner == null ? null : owner.fullName(),
                            owner == null ? null : owner.phoneNumber());
            return withAudit(result, histories.getOrDefault(a.id(), List.of()));
        }).toList();
    }

    /**
     * Suy ra các trường kiểm toán từ lịch sử (đã sắp theo id tăng dần): lần CONFIRMED đầu tiên là lần duyệt (hoặc lễ tân tạo
     * lịch), lần CANCELLED cuối là lần hủy; lý do hủy nằm ở {@code notes}; {@code changedByUserId = null} là hệ thống.
     */
    private static AppointmentResponse withAudit(AppointmentResponse response, List<AppointmentStatusHistory> history) {
        if (history.isEmpty()) {
            return response;
        }
        AppointmentStatusHistory confirmed = history.stream()
                .filter(h -> h.getToStatus() == AppointmentStatus.CONFIRMED && h.getFromStatus() != AppointmentStatus.CONFIRMED)
                .findFirst().orElse(null);
        AppointmentStatusHistory cancelled = null;
        for (AppointmentStatusHistory h : history) {
            if (h.getToStatus() == AppointmentStatus.CANCELLED && h.getFromStatus() != AppointmentStatus.CANCELLED) {
                cancelled = h;
            }
        }
        return response.withAudit(
                cancelled == null ? null : cancelled.getNotes(),
                confirmed == null ? null : confirmed.getChangedByUserId(),
                confirmed == null ? null : confirmed.getCreatedAt(),
                cancelled == null ? null : cancelled.getChangedByUserId(),
                cancelled == null ? null : cancelled.getCreatedAt(),
                history.get(history.size() - 1).getCreatedAt());
    }

    private Map<Long, List<AppointmentStatusHistory>> histories(Collection<Long> appointmentIds) {
        try {
            return historyRepository.findByAppointmentIdInOrderByIdAsc(appointmentIds).stream()
                    .collect(Collectors.groupingBy(AppointmentStatusHistory::getAppointmentId));
        } catch (RuntimeException e) {
            log.warn("Không đọc được lịch sử trạng thái của lịch hẹn: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, PetSummaryResponse> pets(Set<Long> ids) {
        try {
            ApiResponse<List<PetSummaryResponse>> response = petClient.getPets(List.copyOf(ids));
            List<PetSummaryResponse> list = response == null || response.getData() == null ? List.of() : response.getData();
            return list.stream().collect(Collectors.toMap(PetSummaryResponse::id, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("Không lấy được tên thú cưng từ clinical-service: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, UserSummaryResponse> owners(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            ApiResponse<List<UserSummaryResponse>> response = authClient.getUsers(List.copyOf(ids));
            List<UserSummaryResponse> list = response == null || response.getData() == null ? List.of() : response.getData();
            return list.stream().collect(Collectors.toMap(UserSummaryResponse::id, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("Không lấy được thông tin chủ nuôi từ auth-service: {}", e.getMessage());
            return Map.of();
        }
    }
}
