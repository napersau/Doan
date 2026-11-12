package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.response.OperationsReportResponse;
import com.vetclinic.clinical.dto.response.RevenueReportResponse;

import java.time.LocalDate;

public interface ReportService {

    /**
     * FR-70 / UC-08: chỉ số vận hành theo ngày check-in (giờ phòng khám). Mặc định 30 ngày gần nhất, tối đa 366 ngày.
     */
    OperationsReportResponse operations(LocalDate fromDate, LocalDate toDate);

    /**
     * FR-69 (phần dịch vụ): doanh thu hóa đơn dịch vụ PAID theo {@code paid_at} (nhóm theo {@code services.category}) + công nợ hiện tại
     * (hóa đơn PENDING của ca COMPLETED). Doanh thu thuốc do billing-pharmacy-service cung cấp; Admin cộng hai nguồn.
     */
    RevenueReportResponse revenue(LocalDate fromDate, LocalDate toDate);
}
