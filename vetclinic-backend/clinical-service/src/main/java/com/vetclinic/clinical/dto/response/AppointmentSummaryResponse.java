package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;

public record AppointmentSummaryResponse(
        Long id,
        String appointmentCode,
        Long petId,
        UUID ownerId,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        String status
) {
}
