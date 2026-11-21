package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: ca khám đã COMPLETED (thu đủ hóa đơn dịch vụ - T10, hoặc khách bỏ về - T08/T12).
 * CHỈ để thông báo (phiếu thu điện tử, bệnh án đã có trên Portal); không dùng để đổi trạng thái ở service khác.
 * Routing key {@code visit.completed}.
 */
public record VisitCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân (T10) hoặc người thao tác "Khách bỏ về"
        UUID visitId,
        String visitCode,
        UUID petId,
        boolean abandoned        // true: khách bỏ về, hóa đơn dịch vụ còn lại giữ PENDING làm công nợ
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_COMPLETED;
    }
}
