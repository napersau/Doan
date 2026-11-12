package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.ServiceCategory;

import java.math.BigDecimal;
import java.time.Instant;

/** Một hóa đơn dịch vụ kèm số tiền tính khi hiển thị (theo công thức của VIEW v_service_invoice_amounts, không lưu). */
public record ServiceInvoiceResponse(
        Long id,
        String invoiceCode,
        Long visitServiceDetailId,
        Long serviceId,
        String serviceName,
        ServiceCategory category,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal discountAmount,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        BigDecimal payableAmount,
        PaymentStatus paymentStatus,
        Long cashierId,
        Instant paidAt
) {
}
