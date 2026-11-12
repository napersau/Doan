package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hóa đơn dịch vụ (v3.0): nằm cùng CSDL với chỉ định và đợt khám nên "thu tiền + hoàn tất ca" là MỘT giao dịch cục bộ
 * (không còn sự kiện liên service). Không có API hủy hóa đơn lẻ: muốn bỏ một khoản thì hủy dịch vụ (BR-33).
 */
public interface ServiceInvoiceService {

    /** UC-34: bảng kê hóa đơn của một đợt khám, tổng cần thu = tổng hóa đơn PENDING. */
    InvoiceStatementResponse statement(Long visitId);

    /**
     * EX-14: giảm giá trên MỘT hóa đơn, không vượt quá quantity x unit_price x (1 + vat_rate) của chính hóa đơn đó (BILL_1402). Chỉ khi hóa
     * đơn còn PENDING (BILL_1403 / BILL_1408) và ca đã chốt (WAITING_PAYMENT) hoặc còn công nợ (COMPLETED) (BILL_1409).
     */
    ServiceInvoiceResponse discount(Long invoiceId, BigDecimal discountAmount);

    /**
     * UC-35 / T10: thu các hóa đơn đã chọn (được thu một phần). Ca phải WAITING_PAYMENT, hoặc COMPLETED còn công nợ (BILL_1409); mọi id phải
     * thuộc ca (BILL_1401) và đang PENDING (BILL_1403 đã thu, BILL_1408 đã hủy). Cuối giao dịch: không còn hóa đơn PENDING và ca đang
     * WAITING_PAYMENT thì ca -> COMPLETED + lịch sử, rồi phát VisitCompletedEvent. Ca COMPLETED do khách bỏ về: chỉ thu công nợ.
     */
    InvoiceStatementResponse confirmPayment(Long visitId, List<Long> invoiceIds);

    /** Danh sách ca COMPLETED còn hóa đơn PENDING (công nợ). */
    List<VisitResponse> visitsWithDebt();
}
