package com.vetclinic.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * billing-pharmacy-service -> notification-service: hóa đơn thuốc đã PAID (đồng thời đã trừ kho trong cùng giao dịch).
 * KHÔNG làm thay đổi trạng thái đợt khám. Routing key {@code pharmacy.medicine_invoice_paid}.
 */
public record MedicineInvoicePaidEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân
        UUID invoiceId,
        String invoiceCode,
        UUID prescriptionId,
        UUID visitId,
        BigDecimal payableAmount // chỉ để hiển thị trong thông báo; số tiền không được lưu ở CSDL
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.PHARMACY_MEDICINE_INVOICE_PAID;
    }
}
