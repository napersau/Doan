package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.entity.QueueTicket;

import java.time.Instant;

/** Phiếu xếp hàng gắn kèm một ca trên màn hình hàng đợi (số in trên phiếu, mức ưu tiên, giờ gọi gần nhất). */
public record TicketInfo(
        Long id,
        QueueType queueType,
        int ticketNo,
        int priority,
        TicketStatus status,
        Instant issuedAt,
        Instant lastCalledAt
) {
    public static TicketInfo of(QueueTicket t) {
        return t == null ? null : new TicketInfo(t.getId(), t.getQueueType(), t.getTicketNo(), t.getPriority(), t.getStatus(),
                t.getIssuedAt(), t.getLastCalledAt());
    }
}
