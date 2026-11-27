# 12. CQRS: báo cáo có CSDL đọc riêng, dựng từ sự kiện

## 1. Vấn đề

Admin muốn xem "doanh thu tháng 11 theo nhóm dịch vụ và thuốc". Dữ liệu nằm rải ở:
- Hóa đơn dịch vụ → CSDL **clinical**.
- Hóa đơn thuốc → CSDL **billing**.
- Tên Bác sĩ → CSDL **auth**.

Không thể `JOIN` ba CSDL (file 05). Gọi Feign ba service, lấy hàng nghìn hóa đơn về rồi cộng trong Java thì chậm, và một service sập là
báo cáo hỏng. Tệ hơn, truy vấn báo cáo nặng chạy trên CSDL clinical sẽ **làm chậm việc khám bệnh**.

## 2. Lý thuyết

**CQRS** (Command Query Responsibility Segregation): **tách** phần **ghi** (command) và phần **đọc** (query) thành hai mô hình riêng.

```
  PHẦN GHI (nghiệp vụ)                          PHẦN ĐỌC (báo cáo)
  clinical_db, billing_db, auth_db              reporting_db
  chuẩn hóa, nhiều bảng, ràng buộc chặt          bảng PHẲNG, đã cộng sẵn, đọc cực nhanh
          │                                                ▲
          └──── sự kiện qua RabbitMQ ──────► listener ─────┘
                (đã thu tiền, đã hoàn tất ca...)   (projection: "chiếu" sự kiện thành dòng báo cáo)
```

- **Projection** (chiếu): mỗi sự kiện tới thì ghi / cộng dồn vào bảng đọc.
- Bảng đọc được thiết kế **theo câu hỏi** của báo cáo, không theo chuẩn hóa: có thể lặp dữ liệu (tên Bác sĩ, tên nhóm dịch vụ ngay trên dòng
  doanh thu) để khỏi `JOIN`.
- Báo cáo **trễ** vài trăm mili giây so với thực tế (eventual consistency). Với báo cáo doanh thu thì hoàn toàn chấp nhận được.

Ví dụ đời thường: kế toán không chạy xuống từng quầy lật sổ mỗi khi giám đốc hỏi doanh thu. Mỗi quầy thu xong gửi một **phiếu báo**;
kế toán ghi vào **sổ tổng hợp** của mình. Giám đốc hỏi thì chỉ cần mở sổ tổng hợp.

## 3. Trong dự án

### 3.1. CSDL đọc `pet_clinic_reporting_db` (5 bảng)

| Bảng | Một dòng là | Phục vụ báo cáo |
|---|---|---|
| `visit_facts` | Một đợt khám (thú cưng, Bác sĩ, ngày, trạng thái hiện tại) | Thống kê lượt khám |
| `revenue_facts` | Một dòng doanh thu (dịch vụ hoặc thuốc, kèm tên, nhóm, Bác sĩ). Hoàn tiền = dòng **đổi dấu** | Chi tiết doanh thu |
| `daily_revenue_summaries` | Tổng tiền theo **ngày × loại × nhóm** (đã cộng sẵn) | Doanh thu theo ngày / theo nhóm |
| `doctor_daily_performances` | Số ca, doanh thu của **một Bác sĩ trong một ngày** | Hiệu suất Bác sĩ |
| `processed_events` | Chống xử lý trùng (file 10) | – |

### 3.2. Lúc chạy bình thường, reporting KHÔNG gọi service nào

reporting chỉ **nghe** 8 sự kiện, mỗi sự kiện một queue + DLQ riêng
([ReportingListeners.java](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/event/ReportingListeners.java)):

| Sự kiện | Tác dụng lên bảng đọc |
|---|---|
| `VisitCheckedInEvent` | Thêm dòng `visit_facts` |
| `VisitCompletedEvent`, `VisitCancelledEvent` | Cập nhật trạng thái `visit_facts`, cộng hiệu suất Bác sĩ |
| `ServiceInvoicePaidEvent` | Thêm dòng doanh thu dịch vụ (SALE), cộng bảng tổng hợp |
| `ServiceInvoiceRefundedEvent` | Thêm dòng **đổi dấu** (REFUND), trừ bảng tổng hợp |
| `MedicineInvoicePaidEvent` | Thêm dòng doanh thu thuốc |
| `UserProfileUpdatedEvent`, `PetUpdatedEvent` | Cập nhật tên Bác sĩ / thú cưng trên các dòng |

Mỗi listener đi qua [ReportingEventProcessor.java](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/event/ReportingEventProcessor.java):

```java
public boolean serviceInvoicePaid(ServiceInvoicePaidEvent e) {
    return gate.shared(() -> idempotentConsumer.run(e.eventId(), ReportingListeners.INVOICE_SERVICE_PAID,
            () -> projection.onServiceInvoicePaid(e)));
}
```

`processed_events` + dòng fact + bảng tổng hợp ghi **cùng một giao dịch**: hoặc tất cả, hoặc không gì cả.

### 3.3. Projection: biến sự kiện thành dòng báo cáo

[ReportingProjectionService.onServiceInvoicePaid()](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/service/ReportingProjectionService.java) (rút gọn):

```java
public void onServiceInvoicePaid(ServiceInvoicePaidEvent e) {
    LocalDate date = businessDate(e.paidAt());               // ngày nghiệp vụ theo GIỜ VIỆT NAM (không phải UTC)
    for (ServiceInvoicePaidEvent.Line line : e.lines()) {
        if (revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(line.invoiceId(), line.serviceId(), RevenueEntryType.SALE)) {
            continue;                                          // lớp chống trùng thứ hai
        }
        BigDecimal before = money(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
        BigDecimal net = money(line.payableAmount());
        write(RevenueFact.builder()
                .revenueType(RevenueType.SERVICE).entryType(RevenueEntryType.SALE)
                .itemName(line.serviceName()).categoryName(line.categoryName())     // lặp tên ngay trên dòng: khỏi JOIN
                .doctorId(doctorId).doctorName(doctorName)
                .businessDate(date).amountBeforeVat(before).netAmount(net) ...
                .build());                                     // write() ghi revenue_facts + cộng daily_revenue_summaries
    }
}
```

Toàn bộ dữ liệu lấy từ **nội dung sự kiện**. Đó là lý do sự kiện `ServiceInvoicePaidEvent` mang đủ tên dịch vụ, nhóm, số tiền từng dòng (file 08).

Cộng dồn bảng tổng hợp bằng **một câu SQL nguyên tử**
([DailyRevenueSummaryRepository](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/repository/DailyRevenueSummaryRepository.java)):

```sql
INSERT INTO daily_revenue_summaries (business_date, revenue_type, category_code, ..., net_amount)
VALUES (:businessDate, :revenueType, :categoryCode, ..., :netAmount) AS new
ON DUPLICATE KEY UPDATE
    line_count = daily_revenue_summaries.line_count + new.line_count,
    net_amount = daily_revenue_summaries.net_amount + new.net_amount, ...
```

Chưa có dòng của ngày đó thì thêm; đã có thì cộng vào. Hai sự kiện đến cùng lúc vẫn cộng đúng, không mất số.

### 3.4. API báo cáo: đọc MỘT CSDL

[ReportController.java](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/controller/ReportController.java), chỉ `ADMIN`:

| Endpoint | Đọc bảng |
|---|---|
| `GET /api/v1/reports/revenue/daily` | `daily_revenue_summaries` |
| `GET /api/v1/reports/revenue/by-category` | `daily_revenue_summaries` |
| `GET /api/v1/reports/doctors/performance` | `doctor_daily_performances` |
| `GET /api/v1/reports/visits/summary` | `visit_facts` |

Không gọi Feign, không `JOIN` chéo. auth, clinical, billing có sập thì báo cáo **vẫn xem được** (số liệu tới thời điểm sập).

### 3.5. Dựng lại báo cáo từ outbox

Lỡ dữ liệu báo cáo sai (ví dụ do lỗi code đã sửa) thì sao? Các service nguồn vẫn giữ **outbox 7 ngày** (file 09): đó chính là "lịch sử sự
kiện". [ReportRebuildService.java](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/service/ReportRebuildService.java)
(`POST /api/v1/reports/rebuild?days=3`, chỉ ADMIN):

1. Đọc outbox của auth, clinical, billing qua Feign `GET /internal/outbox/events`
   ([OutboxClients](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/client/OutboxClients.java)). Service nào lỗi
   thì **dừng trước khi xóa** gì.
2. Xóa dữ liệu báo cáo từ ngày đầu cửa sổ.
3. Sắp mọi sự kiện theo thời điểm xảy ra, **phát lại** bằng đúng bộ xử lý của listener (không gửi lại RabbitMQ).

Đây là lợi ích lớn của việc lưu sự kiện: **dữ liệu đọc luôn dựng lại được từ nguồn sự kiện.**

## 4. Thử tận tay

```sql
-- Doanh thu theo ngày và nhóm (đã cộng sẵn, đọc tức thì)
SELECT business_date, revenue_type, category_name, line_count, net_amount
FROM pet_clinic_reporting_db.daily_revenue_summaries ORDER BY business_date DESC LIMIT 10;

-- Đối chiếu: tổng thu dịch vụ bên clinical (qua VIEW) phải khớp tổng SERVICE bên reporting
SELECT SUM(payable_amount) FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE payment_status = 'PAID';
SELECT SUM(net_amount) FROM pet_clinic_reporting_db.revenue_facts WHERE revenue_type = 'SERVICE';
```

Con số có thể lệch nếu có hóa đơn đã hoàn tiền (reporting có dòng REFUND âm) hoặc dữ liệu cũ trước khi reporting chạy. Kịch bản
[scripts/reporting_check.py](../scripts/reporting_check.py) kiểm tra khớp trên dữ liệu sạch.

Thí nghiệm "báo cáo độc lập":
```bash
docker compose stop clinical-service billing-pharmacy-service
# Đăng nhập admin, mở trang Báo cáo: vẫn hiện đủ số liệu
docker compose start clinical-service billing-pharmacy-service
```

## 5. Tự kiểm tra

1. CQRS tách cái gì ra khỏi cái gì? Lợi ích cụ thể trong dự án?
2. "Projection" là gì? Cho ví dụ với `ServiceInvoicePaidEvent`.
3. Vì sao bảng báo cáo được phép lặp tên Bác sĩ, tên nhóm dịch vụ (không chuẩn hóa)?
4. `INSERT ... ON DUPLICATE KEY UPDATE` giải quyết vấn đề gì khi nhiều sự kiện tới cùng lúc?
5. Hoàn tiền được thể hiện trong báo cáo thế nào?
6. Dựng lại báo cáo lấy dữ liệu từ đâu? Vì sao chỉ dựng lại được tối đa vài ngày gần nhất?
