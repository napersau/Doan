package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service, appointment-service.
 * appointment-service dùng sự kiện này để chuyển lịch hẹn sang CHECKED_IN (idempotent), vì
 * check-in không được ghi chéo sang DB của service khác trong cùng một transaction.
 */
public record VisitCheckedInEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        Long visitId,
        String visitCode,
        UUID petId,
        UUID appointmentId,   // null nếu khách vãng lai (walk-in)
        boolean emergency,
        UUID doctorId         // Bác sĩ được xếp ngay khi tiếp nhận (hàng đợi riêng của Bác sĩ); null = vào danh sách chờ phân công
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_CHECKED_IN;
    }
}
