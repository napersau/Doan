package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.constant.ServiceCategory;

import java.math.BigDecimal;

/** Dòng dữ liệu tính tiền (JPQL constructor expression) để cộng doanh thu / công nợ ở tầng ứng dụng. */
public record InvoiceAmountRow(
        ServiceCategory category,
        Long visitId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal discountAmount
) {
}
