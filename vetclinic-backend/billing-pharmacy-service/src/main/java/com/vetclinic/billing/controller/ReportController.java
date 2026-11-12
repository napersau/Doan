package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.response.RevenueReportResponse;
import com.vetclinic.billing.service.ReportService;
import com.vetclinic.billing.service.RevenuePeriods;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/billing/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /** FR-69, phần doanh thu THUỐC (hóa đơn thuốc PAID theo ngày / tuần / tháng / quý). Phần dịch vụ ở clinical-service /clinical/reports/revenue. */
    @GetMapping("/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RevenueReportResponse>> revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "DAY") RevenuePeriods.Granularity groupBy) {
        return ResponseEntity.ok(ApiResponse.success(reportService.revenue(fromDate, toDate, groupBy)));
    }
}
