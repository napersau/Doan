package com.vetclinic.billing.dto.response;

import java.time.Instant;

/** Một dòng trong danh sách đơn thuốc. */
public record PrescriptionSummaryResponse(
        Long id,
        Long visitId,
        String visitCode,
        String petName,
        int itemCount,
        Instant createdAt
) {
}
