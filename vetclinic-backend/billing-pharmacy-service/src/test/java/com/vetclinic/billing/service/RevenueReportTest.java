package com.vetclinic.billing.service;

import com.vetclinic.billing.config.BillingProperties;
import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.dto.response.RevenueReportResponse;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRevenueRow;
import com.vetclinic.billing.service.RevenuePeriods.Granularity;
import com.vetclinic.billing.service.impl.ReportServiceImpl;
import com.vetclinic.common.exception.ErrorCode;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Collections;

import static com.vetclinic.billing.testsupport.TestData.CLOCK;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueReportTest {

    @Mock
    private MedicineInvoiceRepository invoiceRepository;

    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReportServiceImpl(invoiceRepository, new BillingProperties("Asia/Ho_Chi_Minh"), CLOCK);
    }

    /** gross = trước VAT + VAT (đã chưa làm tròn như JPQL SUM trả về). */
    private static MedicineRevenueRow paid(String instant, String beforeVat, String vat, String discount) {
        BigDecimal b = new BigDecimal(beforeVat), v = new BigDecimal(vat);
        return new MedicineRevenueRow(Instant.parse(instant), new BigDecimal(discount), b, v, b.add(v));
    }

    private static List<Object[]> statusRows(Object[]... rows) {
        List<Object[]> list = new ArrayList<>();
        Collections.addAll(list, rows);
        return list;
    }

    // ================================================================== kỳ báo cáo

    @Test
    void periods_startOnTheCalendarBoundaryOfEachGranularity() {
        LocalDate wednesday = LocalDate.of(2026, 11, 18);
        assertThat(RevenuePeriods.start(wednesday, Granularity.DAY)).isEqualTo(wednesday);
        assertThat(RevenuePeriods.start(wednesday, Granularity.WEEK)).isEqualTo(LocalDate.of(2026, 11, 16));   // thứ Hai
        assertThat(RevenuePeriods.start(wednesday, Granularity.MONTH)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(RevenuePeriods.start(wednesday, Granularity.QUARTER)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(RevenuePeriods.start(LocalDate.of(2026, 2, 28), Granularity.QUARTER)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(RevenuePeriods.label(LocalDate.of(2026, 10, 1), Granularity.QUARTER)).isEqualTo("2026-Q4");
        assertThat(RevenuePeriods.label(LocalDate.of(2026, 11, 1), Granularity.MONTH)).isEqualTo("2026-11");
    }

    @Test
    void periods_between_includesEmptyPeriodsSoChartsAreContinuous() {
        assertThat(RevenuePeriods.between(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), Granularity.DAY)).hasSize(4);
        assertThat(RevenuePeriods.between(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 12, 3), Granularity.MONTH))
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));
        // 28/10 (thứ Tư) -> tuần bắt đầu 26/10; 03/11 (thứ Ba) -> tuần bắt đầu 02/11
        assertThat(RevenuePeriods.between(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3), Granularity.WEEK))
                .containsExactly(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2));
    }

    // ================================================================== báo cáo doanh thu thuốc

    @Test
    void revenue_groupsPaidMedicineInvoicesByClinicDay() {
        // 17:30 UTC ngày 28/10 = 00:30 ngày 29/10 giờ Việt Nam -> thuộc ngày 29/10
        when(invoiceRepository.findPaidRevenueRows(any(), any())).thenReturn(List.of(
                paid("2026-10-28T03:00:00Z", "150000", "7500", "0"),
                paid("2026-10-28T17:30:00Z", "90000", "4500", "10000"),
                paid("2026-10-29T03:00:00Z", "45000", "2250", "0")));
        when(invoiceRepository.countByStatus(any(), any())).thenReturn(statusRows(
                new Object[]{PaymentStatus.PAID, 3L}, new Object[]{PaymentStatus.PENDING, 2L}));

        RevenueReportResponse report = service.revenue(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30), Granularity.DAY);

        assertThat(report.periods()).extracting(RevenueReportResponse.Period::label)
                .containsExactly("2026-10-28", "2026-10-29", "2026-10-30");
        assertThat(report.periods().get(0).payableAmount()).isEqualByComparingTo("157500");
        assertThat(report.periods().get(1).invoiceCount()).isEqualTo(2);
        assertThat(report.periods().get(1).amountBeforeVat()).isEqualByComparingTo("135000");
        assertThat(report.periods().get(1).discountAmount()).isEqualByComparingTo("10000");
        assertThat(report.periods().get(1).payableAmount()).isEqualByComparingTo("131750");   // 94500 + 47250 - 10000
        assertThat(report.periods().get(2).invoiceCount()).isZero();      // ngày không có doanh thu vẫn có dòng
        assertThat(report.totals().invoiceCount()).isEqualTo(3);
        assertThat(report.totals().amountBeforeVat()).isEqualByComparingTo("285000");
        assertThat(report.totals().vatAmount()).isEqualByComparingTo("14250");
        assertThat(report.totals().discountAmount()).isEqualByComparingTo("10000");
        assertThat(report.totals().payableAmount()).isEqualByComparingTo("289250");   // 285000 + 14250 - 10000
        assertThat(report.byStatus()).containsEntry("PAID", 3L).containsEntry("PENDING", 2L)
                .containsEntry("CANCELLED", 0L);   // đủ ba trạng thái
    }

    @Test
    void revenue_queriesTheClinicDayBoundariesNotUtc() {
        when(invoiceRepository.findPaidRevenueRows(any(), any())).thenReturn(List.of());
        when(invoiceRepository.countByStatus(any(), any())).thenReturn(List.of());

        service.revenue(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28), Granularity.DAY);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(invoiceRepository).findPaidRevenueRows(start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-27T17:00:00Z"));   // 00:00 ngày 28 giờ VN
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-28T17:00:00Z"));     // loại trừ 00:00 ngày 29
    }

    @Test
    void revenue_groupsByMonthAndQuarter() {
        when(invoiceRepository.findPaidRevenueRows(any(), any())).thenReturn(List.of(
                paid("2026-10-15T03:00:00Z", "100000", "0", "0"),
                paid("2026-11-15T03:00:00Z", "200000", "0", "0")));
        when(invoiceRepository.countByStatus(any(), any())).thenReturn(List.of());

        RevenueReportResponse months = service.revenue(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), Granularity.MONTH);
        RevenueReportResponse quarter = service.revenue(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), Granularity.QUARTER);

        assertThat(months.periods()).extracting(RevenueReportResponse.Period::label).containsExactly("2026-10", "2026-11");
        assertThat(quarter.periods()).hasSize(1);
        assertThat(quarter.periods().get(0).label()).isEqualTo("2026-Q4");
        assertThat(quarter.periods().get(0).payableAmount()).isEqualByComparingTo("300000");
    }

    @Test
    void revenue_defaultsToTheLast30DaysUpToToday() {
        when(invoiceRepository.findPaidRevenueRows(any(), any())).thenReturn(List.of());
        when(invoiceRepository.countByStatus(any(), any())).thenReturn(List.of());

        RevenueReportResponse report = service.revenue(null, null, null);

        assertThat(report.toDate()).isEqualTo(LocalDate.of(2026, 10, 30));     // CLOCK = 30/10/2026 giờ VN
        assertThat(report.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(report.groupBy()).isEqualTo("DAY");
        assertThat(report.periods()).hasSize(30);
    }

    @Test
    void revenue_refusesInvertedOrTooLongRanges() {
        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.revenue(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), Granularity.DAY));
        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.revenue(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 30), Granularity.DAY));
    }
}
