package com.vetclinic.billing.dto.response;

import com.vetclinic.billing.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * Hóa đơn thuốc. Các số tiền KHÔNG lưu ở CSDL, được tính theo công thức VIEW v_medicine_invoice_amounts:
 * payableAmount = Σ(quantity × unitPrice × (1 + vatRate)) − discountAmount. visitCode / petName / ownerId lấy từ clinical-service
 * (null nếu service đó tạm không phản hồi).
 */
public record MedicineInvoiceResponse(
        Long id,
        String invoiceCode,
        Long prescriptionId,
        UUID visitId,
        String visitCode,
        String petName,
        UUID ownerId,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        PaymentStatus paymentStatus,
        UUID cashierId,
        Instant paidAt,
        Instant createdAt,
        List<MedicineInvoiceItemResponse> items
) {
}
