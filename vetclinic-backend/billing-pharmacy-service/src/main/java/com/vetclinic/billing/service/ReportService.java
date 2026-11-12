package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.response.RevenueReportResponse;

import java.time.LocalDate;

public interface ReportService {

    /**
     * FR-69, phần doanh thu thuốc: hóa đơn thuốc PAID theo ngày thu tiền (giờ phòng khám), nhóm theo ngày / tuần / tháng / quý.
     * Hóa đơn CANCELLED không tính.
     */
    RevenueReportResponse revenue(LocalDate fromDate, LocalDate toDate, RevenuePeriods.Granularity groupBy);
}
