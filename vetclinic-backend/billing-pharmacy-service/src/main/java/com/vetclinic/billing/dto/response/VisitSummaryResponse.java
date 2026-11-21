package com.vetclinic.billing.dto.response;

import java.util.UUID;
import com.vetclinic.billing.constant.VisitStatus;

/** Bản rút gọn đợt khám do clinical-service trả về qua endpoint nội bộ. */
public record VisitSummaryResponse(
        Long id,
        String visitCode,
        VisitStatus status,
        UUID petId,
        String petName,
        UUID ownerId,
        UUID doctorId
) {
}
