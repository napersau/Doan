package com.vetclinic.clinical.repository;


import java.math.BigDecimal;

/** Dòng dữ liệu tính tiền (JPQL constructor expression) để cộng doanh thu / công nợ ở tầng ứng dụng. */
public record InvoiceAmountRow(
        String category,          // mã nhóm dịch vụ ({@code service_categories.code})
        short categoryOrder,      // {@code service_categories.display_order}: thứ tự hiển thị trong báo cáo
        Long visitId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal discountAmount
) {
}
