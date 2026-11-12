package com.vetclinic.billing.repository;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một hóa đơn thuốc đã thanh toán, chỉ các tổng cần cho báo cáo (không nạp entity). Các tổng chưa làm tròn: ReportServiceImpl
 * làm tròn HALF_UP 2 chữ số theo đúng VIEW v_medicine_invoice_amounts.
 */
public record MedicineRevenueRow(
        Instant paidAt,
        BigDecimal discountAmount,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        BigDecimal grossAmount
) {
}
