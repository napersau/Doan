package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: gọi / gọi lại một PHIẾU XẾP HÀNG ở phòng khám Bác sĩ / phòng Xét nghiệm / phòng CĐHA
 * (BR-34, SM-07 Q4). Routing key {@code visit.patient_called}. Hệ thống chỉ cập nhật queue_tickets.last_called_at.
 * Gọi số KHÔNG đổi trạng thái ca khám; ca chỉ đổi trạng thái khi bệnh nhân vào phòng và nhân viên bấm "Bắt đầu".
 */
public record PatientCalledEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        String action,           // CALLED = mời vào phòng (giá trị duy nhất hiện nay)
        String station,          // DOCTOR_ROOM | LAB | IMAGING
        Long visitId,
        String visitCode,
        Long petId,
        UUID doctorId,           // Bác sĩ của hàng đợi (null nếu gọi ở Lab/CĐHA cho ca chưa có bác sĩ)
        Long ticketId,           // phiếu xếp hàng được gọi (queue_tickets.id)
        Integer ticketNo,        // số in trên phiếu (đánh lại từ 1 mỗi ngày theo loại hàng đợi)
        String roomNumber        // số phòng hiển thị cho khách (users.room_number của bác sĩ/KTV), có thể null
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_PATIENT_CALLED;
    }
}
