package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.ServiceCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * FR-69, phần doanh thu DỊCH VỤ (clinical-service): hóa đơn dịch vụ {@code PAID} có {@code paid_at} trong khoảng, nhóm theo
 * {@code services.category}; hóa đơn CANCELLED không tính. Doanh thu thuốc nằm ở billing-pharmacy-service; Admin cộng hai nguồn ở
 * tầng ứng dụng. Công nợ = hóa đơn PENDING của ca COMPLETED (khách bỏ về), tính tại thời điểm xem báo cáo.
 */
public record RevenueReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<CategoryRevenue> byCategory,
        long paidInvoiceCount,
        BigDecimal totalPaid,
        Debt debt
) {

    public record CategoryRevenue(
            ServiceCategory category,
            long invoiceCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {
    }

    public record Debt(long visitCount, long invoiceCount, BigDecimal amount) {
    }
}
