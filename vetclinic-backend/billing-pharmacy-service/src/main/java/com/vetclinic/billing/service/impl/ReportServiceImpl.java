package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.config.BillingProperties;
import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.dto.response.RevenueReportResponse;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRevenueRow;
import com.vetclinic.billing.service.MedicineInvoiceAmounts;
import com.vetclinic.billing.service.ReportService;
import com.vetclinic.billing.service.RevenuePeriods;
import com.vetclinic.billing.service.RevenuePeriods.Granularity;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final int DEFAULT_DAYS = 30;
    private static final long MAX_DAYS = 366;

    private final MedicineInvoiceRepository invoiceRepository;
    private final BillingProperties properties;
    private final Clock clock;

    /** Cộng dồn tiền của một nhóm hóa đơn (đã làm tròn từng hóa đơn như VIEW v_medicine_invoice_amounts). */
    private static final class Sum {
        long count;
        BigDecimal beforeVat = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;

        void add(MedicineRevenueRow row) {
            count++;
            beforeVat = beforeVat.add(MedicineInvoiceAmounts.money(row.amountBeforeVat()));
            vat = vat.add(MedicineInvoiceAmounts.money(row.vatAmount()));
            discount = discount.add(row.discountAmount());
            payable = payable.add(MedicineInvoiceAmounts.money(row.grossAmount().subtract(row.discountAmount())));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RevenueReportResponse revenue(LocalDate fromDate, LocalDate toDate, Granularity groupBy) {
        ZoneId zone = properties.zoneId();
        LocalDate to = toDate != null ? toDate : LocalDate.now(clock);
        LocalDate from = fromDate != null ? fromDate : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khoảng báo cáo tối đa " + MAX_DAYS + " ngày");
        }
        Granularity granularity = groupBy == null ? Granularity.DAY : groupBy;
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, Sum> buckets = new TreeMap<>();
        RevenuePeriods.between(from, to, granularity).forEach(p -> buckets.put(p, new Sum()));
        Sum total = new Sum();
        for (MedicineRevenueRow row : invoiceRepository.findPaidRevenueRows(start, end)) {
            LocalDate paidDay = row.paidAt().atZone(zone).toLocalDate();
            buckets.computeIfAbsent(RevenuePeriods.start(paidDay, granularity), k -> new Sum()).add(row);
            total.add(row);
        }

        List<RevenueReportResponse.Period> periods = buckets.entrySet().stream().map(e -> new RevenueReportResponse.Period(
                RevenuePeriods.label(e.getKey(), granularity), e.getKey(), e.getValue().count, e.getValue().beforeVat,
                e.getValue().vat, e.getValue().discount, e.getValue().payable)).toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (PaymentStatus status : PaymentStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (Object[] row : invoiceRepository.countByStatus(start, end)) {
            byStatus.put(((PaymentStatus) row[0]).name(), (Long) row[1]);
        }

        return new RevenueReportResponse(from, to, granularity.name(),
                new RevenueReportResponse.Totals(total.count, total.beforeVat, total.vat, total.discount, total.payable),
                periods, byStatus);
    }
}
