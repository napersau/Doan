package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import java.util.List;

/**
 * Đơn thuốc của một đợt khám do billing-pharmacy-service trả về (chỉ phần cần cho bệnh án và hướng dẫn dùng thuốc). Từ v3.0 đơn thuốc
 * không có giá và không có trạng thái (chỉ định chuyên môn), nên xem được cả khi khách mua ngoài.
 */
public record PrescriptionSummary(
        Long id,
        UUID visitId,
        String doctorNotes,
        List<Item> items
) {

    public record Item(
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
}
