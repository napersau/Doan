package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Bác sĩ cập nhật sinh hiệu và ghi chú lâm sàng khi ca đang khám. Trường nào null thì giữ nguyên. */
public record ClinicalInfoRequest(
        @DecimalMin(value = "0.1", message = "Cân nặng phải lớn hơn 0.1 kg") @DecimalMax(value = "150.0", message = "Cân nặng không hợp lý (tối đa 150 kg)") BigDecimal weightAtVisit,
        @DecimalMin(value = "35.0", message = "Thân nhiệt đo được quá thấp (tối thiểu 35°C)") @DecimalMax(value = "43.0", message = "Thân nhiệt đo được quá cao (tối đa 43°C)") BigDecimal temperature,
        @Size(max = 1000, message = "Ghi chú lâm sàng không được vượt quá 1000 ký tự") String clinicalNotes
) {
}
