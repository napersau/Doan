package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: gọi số ở phòng khám Bác sĩ / phòng Xét nghiệm / phòng CĐHA, hoặc báo chủ nuôi
 * bị "Đẩy lùi 1 bậc" khi gọi không thấy (BR-34, Q3, Q4). Routing key {@code visit.patient_called}.
 * Gọi số KHÔNG đổi trạng thái ca khám; ca chỉ đổi trạng thái khi bệnh nhân vào phòng và nhân viên bấm "Bắt đầu".
 */
public record PatientCalledEvent(
        UUID eventId,
        Instant occurredAt,
        Long actorUserId,
        String action,           // CALLED = mời vào phòng; PUSHED_BACK = bị đẩy lùi 1 bậc vì vắng mặt
        String station,          // DOCTOR_ROOM | LAB | IMAGING
        Long visitId,
        String visitCode,
        Long petId,
        Long doctorId,           // Bác sĩ của hàng đợi (null nếu gọi ở Lab/CĐHA cho ca chưa có bác sĩ)
        Long serviceDetailId,    // chỉ có khi station = LAB | IMAGING
        String roomNumber,       // số phòng hiển thị cho khách (users.room_number của bác sĩ/KTV), có thể null
        int skipCount            // số lần đã bị đẩy lùi (hiển thị)
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_PATIENT_CALLED;
    }
}
