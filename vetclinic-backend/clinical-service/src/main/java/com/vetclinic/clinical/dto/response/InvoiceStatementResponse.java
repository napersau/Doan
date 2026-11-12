package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.VisitStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bảng kê hóa đơn dịch vụ của một đợt khám (UC-34). totalPending = tổng cần thu (các hóa đơn PENDING); totalPaid = đã thu;
 * hóa đơn CANCELLED không tính vào tổng nào.
 */
public record InvoiceStatementResponse(
        Long visitId,
        String visitCode,
        VisitStatus visitStatus,
        List<ServiceInvoiceResponse> invoices,
        BigDecimal totalPending,
        BigDecimal totalPaid
) {
}
