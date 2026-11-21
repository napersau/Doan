package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.PetGender;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PetResponse(
        Long id,
        UUID ownerId,
        Long speciesId,
        String speciesCode,
        String speciesName,
        String petCode,
        String name,
        String breed,
        PetGender gender,
        LocalDate dateOfBirth,
        String color,
        BigDecimal weight,
        boolean neutered,
        String identifyingCharacteristics,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Lược đồ v3.0 bỏ cột pets.weight: cân nặng hiện tại = weight_at_visit của đợt khám gần nhất (PetServiceImpl gắn vào đây),
     * null nếu thú cưng chưa từng được cân.
     */
    public PetResponse withWeight(BigDecimal weight) {
        return new PetResponse(id, ownerId, speciesId, speciesCode, speciesName, petCode, name, breed, gender, dateOfBirth,
                color, weight, neutered, identifyingCharacteristics, notes, createdAt, updatedAt);
    }
}
