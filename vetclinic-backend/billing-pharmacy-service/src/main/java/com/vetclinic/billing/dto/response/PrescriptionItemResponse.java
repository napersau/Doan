package com.vetclinic.billing.dto.response;

/** Dòng thuốc trong đơn: chỉ định chuyên môn, không có giá. */
public record PrescriptionItemResponse(
        Long id,
        Long medicineId,
        String medicineCode,
        String medicineName,
        String activeIngredient,
        String unit,
        int quantity,
        String dosage,
        String frequency,
        int durationDays,
        String instructions
) {
}
