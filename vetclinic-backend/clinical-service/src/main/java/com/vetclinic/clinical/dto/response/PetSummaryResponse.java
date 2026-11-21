package com.vetclinic.clinical.dto.response;

import java.util.UUID;

/** Cho appointment-service kiểm tra thú cưng tồn tại và đúng chủ nuôi. */
public record PetSummaryResponse(
        Long id,
        UUID ownerId,
        String name
) {
}
