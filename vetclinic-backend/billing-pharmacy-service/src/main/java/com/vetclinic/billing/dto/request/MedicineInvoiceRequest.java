package com.vetclinic.billing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lập (POST .../medicine-invoices) hoặc sửa (PUT .../medicine-invoices/{id}/items) hóa đơn thuốc.
 * Khi lập: mỗi dòng quantity >= 1, discountAmount bỏ trống = 0. Khi sửa: quantity = 0 là bỏ dòng khỏi hóa đơn,
 * discountAmount bỏ trống = giữ nguyên mức giảm hiện tại.
 */
public record MedicineInvoiceRequest(
        @NotEmpty @Size(max = 20) List<@Valid Line> items,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount
) {

    public record Line(
            @NotNull Long prescriptionItemId,
            @NotNull @Min(0) @Max(10_000) Integer quantity
    ) {
    }
}
