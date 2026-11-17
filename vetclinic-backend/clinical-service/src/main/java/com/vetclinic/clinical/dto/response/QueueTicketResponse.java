package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;

import java.time.Instant;

/** Kết quả thao tác trên một phiếu xếp hàng (Gọi / Gọi số tiếp theo / Bắt đầu): phiếu + ca khám + số phòng của người gọi. */
public record QueueTicketResponse(
        Long id,
        QueueType queueType,
        int ticketNo,
        int priority,
        TicketStatus status,
        Instant issuedAt,
        Instant lastCalledAt,
        Long visitId,
        String visitCode,
        String petName,
        Long doctorId,
        String roomNumber
) {
}
