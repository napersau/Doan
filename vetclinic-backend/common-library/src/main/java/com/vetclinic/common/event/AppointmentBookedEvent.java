package com.vetclinic.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * appointment-service -> notification-service: gửi thông báo / email mã đặt lịch cho chủ nuôi (v3.0, thay AppointmentCreatedEvent).
 * Routing key {@code appointment.booked}. Kiến trúc v3.0 §5.2.
 */
public record AppointmentBookedEvent(
        UUID eventId,
        Instant occurredAt,
        Long actorUserId,
        Long appointmentId,
        String appointmentCode,
        Long ownerId,            // lấy từ pets.owner_id qua clinical-service (appointments không còn cột owner_id)
        Long petId,
        LocalDate appointmentDate,
        LocalTime startTime,
        String status            // PENDING (đặt online) hoặc CONFIRMED (lễ tân đặt)
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.APPOINTMENT_BOOKED;
    }
}
