package com.vetclinic.billing.dto.response;

import java.util.UUID;

/** Dòng thuốc trong đơn: chỉ định chuyên môn, không có giá. */
public record PrescriptionItemResponse(
        Long id,
        UUID medicineId,
        String medicineCode,
        String medicineName,
        String activeIngredient,
        String unit,
        int quantity,
        String dosage,
        String frequency,
        int durationDays,
        String instructions,
        int stockQuantity,    // tồn kho hiện tại (để lễ tân thấy khi bán; không dùng để chặn kê đơn)
        int minStockAlert     // ngưỡng tồn tối thiểu: dưới mức này hệ thống cảnh báo Admin
) {
}
