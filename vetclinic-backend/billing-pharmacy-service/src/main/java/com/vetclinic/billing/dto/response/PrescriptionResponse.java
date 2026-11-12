package com.vetclinic.billing.dto.response;

import com.vetclinic.billing.constant.PaymentStatus;

import java.time.Instant;
import java.util.List;

/**
 * Đơn thuốc v3.0: không giá, không trạng thái. visitCode / petName lấy từ clinical-service, null nếu service đó tạm không phản hồi.
 * medicineInvoiceId / medicineInvoiceStatus: hóa đơn thuốc của đơn (null = chưa lập, khách chưa mua hoặc mua ngoài).
 * stockWarnings chỉ có ở phản hồi lưu đơn (EX-13): thuốc kê vượt tồn kho khả dụng, không chặn lưu.
 */
public record PrescriptionResponse(
        Long id,
        Long visitId,
        String visitCode,
        String petName,
        String doctorNotes,
        Instant createdAt,
        Long medicineInvoiceId,
        PaymentStatus medicineInvoiceStatus,
        List<PrescriptionItemResponse> items,
        List<String> stockWarnings
) {
}
