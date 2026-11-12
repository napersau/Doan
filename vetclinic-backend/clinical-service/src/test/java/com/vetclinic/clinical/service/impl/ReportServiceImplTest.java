package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.OperationsReportResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.RevenueReportResponse;
import com.vetclinic.clinical.repository.InvoiceAmountRow;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private AuthClient authClient;

    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ReportServiceImpl(visitRepository, detailRepository, invoiceRepository, authClient, CLOCK, tm);
    }

    private static InvoiceAmountRow row(ServiceCategory c, long visitId, int qty, String unit, String vat, String discount) {
        return new InvoiceAmountRow(c, visitId, qty, new BigDecimal(unit), new BigDecimal(vat), new BigDecimal(discount));
    }

    @Test
    void revenue_groupsPaidInvoicesByServiceCategory_roundsPerInvoiceLikeTheView_andSumsTheDebt() {
        when(invoiceRepository.findPaidBetween(any(), any())).thenReturn(List.of(
                row(ServiceCategory.EXAMINATION, 1L, 1, "100000.00", "0.00", "0.00"),
                row(ServiceCategory.LABORATORY, 1L, 1, "250000.00", "0.00", "0.00"),
                // 200.000 x 1,05 - giảm 10.000 = 200.000 (VAT 10.000)
                row(ServiceCategory.LABORATORY, 2L, 1, "200000.00", "0.05", "10000.00"),
                row(ServiceCategory.VACCINATION, 3L, 2, "150000.00", "0.00", "0.00")));
        when(invoiceRepository.findDebt()).thenReturn(List.of(
                row(ServiceCategory.LABORATORY, 7L, 1, "250000.00", "0.00", "0.00"),
                row(ServiceCategory.IMAGING, 7L, 1, "180000.00", "0.00", "30000.00"),
                row(ServiceCategory.EXAMINATION, 8L, 1, "100000.00", "0.00", "0.00")));

        RevenueReportResponse report = service.revenue(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30));

        assertThat(report.paidInvoiceCount()).isEqualTo(4);
        assertThat(report.totalPaid()).isEqualByComparingTo("850000.00");   // 100.000 + 250.000 + 200.000 + 300.000
        RevenueReportResponse.CategoryRevenue lab = report.byCategory().stream()
                .filter(c -> c.category() == ServiceCategory.LABORATORY).findFirst().orElseThrow();
        assertThat(lab.invoiceCount()).isEqualTo(2);
        assertThat(lab.amountBeforeVat()).isEqualByComparingTo("450000.00");
        assertThat(lab.vatAmount()).isEqualByComparingTo("10000.00");
        assertThat(lab.discountAmount()).isEqualByComparingTo("10000.00");
        assertThat(lab.payableAmount()).isEqualByComparingTo("450000.00");
        assertThat(report.byCategory()).extracting(RevenueReportResponse.CategoryRevenue::category)
                .containsExactly(ServiceCategory.EXAMINATION, ServiceCategory.VACCINATION, ServiceCategory.LABORATORY);   // chỉ nhóm có doanh thu, theo thứ tự khai báo enum
        // Công nợ: 250.000 + (180.000 - 30.000) + 100.000 của 2 ca
        assertThat(report.debt().visitCount()).isEqualTo(2);
        assertThat(report.debt().invoiceCount()).isEqualTo(3);
        assertThat(report.debt().amount()).isEqualByComparingTo("500000.00");
    }

    @Test
    void revenue_usesTheClinicsLocalDayBoundariesAndTheSameRangeRules() {
        when(invoiceRepository.findPaidBetween(any(), any())).thenReturn(List.of());
        when(invoiceRepository.findDebt()).thenReturn(List.of());

        RevenueReportResponse report = service.revenue(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(invoiceRepository).findPaidBetween(start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-29T17:00:00Z"));   // 00:00 giờ VN
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-30T17:00:00Z"));
        assertThat(report.byCategory()).isEmpty();
        assertThat(report.totalPaid()).isEqualByComparingTo("0");
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.revenue(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.revenue(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void operations_splitsRoutineFromParaclinicalAndSummarizesEachDoctor() {
        when(visitRepository.countByStatusBetween(any(), any())).thenReturn(List.of(
                new Object[]{VisitStatus.COMPLETED, 6L}, new Object[]{VisitStatus.CANCELLED, 1L}, new Object[]{VisitStatus.WAITING, 3L}));
        when(visitRepository.countEmergencyBetween(any(), any())).thenReturn(2L);
        when(detailRepository.countVisitsWithCategoriesBetween(any(), any(), any())).thenReturn(4L);
        when(visitRepository.countByDoctorAndStatusBetween(any(), any())).thenReturn(List.of(
                new Object[]{21L, VisitStatus.COMPLETED, 4L}, new Object[]{21L, VisitStatus.CANCELLED, 1L},
                new Object[]{22L, VisitStatus.COMPLETED, 2L}));
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(
                new UserSummaryResponse(21L, "BS Lan", "0911111111", RoleCode.VETERINARIAN, "P01", "ACTIVE"))));

        OperationsReportResponse report = service.operations(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30));

        assertThat(report.totalVisits()).isEqualTo(10);
        assertThat(report.completedVisits()).isEqualTo(6);
        assertThat(report.cancelledVisits()).isEqualTo(1);
        assertThat(report.emergencyVisits()).isEqualTo(2);
        assertThat(report.paraclinicalVisits()).isEqualTo(4);
        assertThat(report.routineVisits()).isEqualTo(6);
        assertThat(report.byStatus()).containsEntry("WAITING", 3L).containsEntry("IN_PROGRESS", 0L);   // đủ mọi trạng thái
        assertThat(report.doctors()).extracting(OperationsReportResponse.DoctorLoad::doctorId).containsExactly(21L, 22L);   // nhiều ca nhất trước
        assertThat(report.doctors().get(0).assignedVisits()).isEqualTo(5);
        assertThat(report.doctors().get(0).completedVisits()).isEqualTo(4);
        assertThat(report.doctors().get(0).doctorName()).isEqualTo("BS Lan");
        assertThat(report.doctors().get(1).doctorName()).isNull();   // auth không trả thì để trống, báo cáo vẫn đủ
    }

    @Test
    void operations_usesClinicDayBoundariesAndParaclinicalCategories() {
        when(visitRepository.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(visitRepository.countByDoctorAndStatusBetween(any(), any())).thenReturn(List.of());

        service.operations(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(visitRepository).countByStatusBetween(start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-27T17:00:00Z"));   // 00:00 ngày 28 giờ VN
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-10-28T17:00:00Z"));
        verify(detailRepository).countVisitsWithCategoriesBetween(ServiceCategory.PARACLINICAL, start.getValue(), end.getValue());
        verify(authClient, never()).getUsers(any());   // không có bác sĩ thì không gọi auth
    }

    @Test
    void operations_defaultsToLast30DaysAndRefusesBadRanges() {
        when(visitRepository.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(visitRepository.countByDoctorAndStatusBetween(any(), any())).thenReturn(List.of());

        OperationsReportResponse report = service.operations(null, null);

        assertThat(report.toDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(report.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(report.totalVisits()).isZero();
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.operations(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.operations(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 30)));
    }
}
