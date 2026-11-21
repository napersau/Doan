package com.vetclinic.appointment.dto.response;

import java.util.UUID;

public record PetSummaryResponse(Long id, UUID ownerId, String name) {
}
