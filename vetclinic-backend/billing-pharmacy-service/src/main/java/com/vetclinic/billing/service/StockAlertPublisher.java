package com.vetclinic.billing.service;

import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.common.event.MedicineLowStockEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Cảnh báo tồn kho chạm ngưỡng ({@code pharmacy.low_stock}, v3.0). Chỉ phát sự kiện nội bộ; DomainEventRabbitPublisher gửi sang
 * RabbitMQ sau khi transaction của nơi gọi commit, nên giao dịch bị rollback thì không có cảnh báo giả.
 */
@Component
@RequiredArgsConstructor
public class StockAlertPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** medicine phải là bản vừa refresh (tồn kho thật sau khi trừ). */
    public void publishIfLow(Medicine medicine, Long actorUserId) {
        if (medicine.getStockQuantity() <= medicine.getMinStockAlert()) {
            eventPublisher.publishEvent(new MedicineLowStockEvent(UUID.randomUUID(), clock.instant(), actorUserId,
                    medicine.getId(), medicine.getCode(), medicine.getName(), medicine.getStockQuantity(),
                    medicine.getMinStockAlert()));
        }
    }
}
