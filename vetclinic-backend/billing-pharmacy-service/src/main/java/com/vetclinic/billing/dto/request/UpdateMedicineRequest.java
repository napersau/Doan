package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Sửa thông tin danh mục (Admin). Không có mã thuốc (bất biến) và không có tồn kho (chỉ đổi qua nhập kho / cân chỉnh).
 * active = false là cách duy nhất để ngừng kinh doanh, vì thuốc đã phát sinh giao dịch thì không xóa cứng.
 * vatRate, minStockAlert và active bỏ trống = giữ nguyên. Đổi giá / VAT không ảnh hưởng hóa đơn đã lập (snapshot).
 */
public record UpdateMedicineRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String activeIngredient,
        @NotBlank @Size(max = 50) String dosageForm,
        @NotBlank @Size(max = 30) String unit,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
        @DecimalMin("0.00") @DecimalMax("1.00") @Digits(integer = 1, fraction = 2) BigDecimal vatRate,
        @Min(0) @Max(1_000_000) Integer minStockAlert,
        Boolean active
) {
}
