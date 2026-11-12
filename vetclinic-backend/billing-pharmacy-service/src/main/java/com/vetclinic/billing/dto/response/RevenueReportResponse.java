package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * FR-69, phần doanh thu THUỐC (billing-pharmacy-service): hóa đơn thuốc {@code PAID} theo {@code paid_at} (giờ phòng khám); hóa đơn
 * CANCELLED không tính. Doanh thu dịch vụ nằm ở clinical-service; Admin cộng hai nguồn ở tầng ứng dụng (CLAUDE.md §10 #5).
 * payableAmount = amountBeforeVat + vatAmount - discountAmount. byStatus = số hóa đơn LẬP trong kỳ theo trạng thái (PENDING = chưa thu).
 */
public record RevenueReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        String groupBy,
        Totals totals,
        List<Period> periods,
        Map<String, Long> byStatus
) {

    public record Totals(
            long invoiceCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {
    }

    public record Period(
            String label,
            LocalDate periodStart,
            long invoiceCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {
    }
}
