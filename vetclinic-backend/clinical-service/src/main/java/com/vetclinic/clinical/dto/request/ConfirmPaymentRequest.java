package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Được thu một phần: chỉ các hóa đơn có id trong danh sách. */
public record ConfirmPaymentRequest(
        @NotEmpty(message = "Phải chọn ít nhất một hóa đơn để thu")
        @Size(max = 100, message = "Tối đa 100 hóa đơn mỗi lần thu")
        List<@NotNull(message = "Mã hóa đơn không được để trống") Long> invoiceIds
) {
}
