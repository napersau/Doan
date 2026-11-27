# 10. Idempotent Consumer: nhận một sự kiện hai lần, chỉ xử lý một lần

## 1. Vấn đề

File 09 cho thấy Outbox bảo đảm sự kiện tới **ít nhất một lần**, nghĩa là có lúc tới **hai lần**. Ngoài ra RabbitMQ cũng giao lại khi:
- Listener xử lý xong nhưng mất kết nối trước khi kịp báo "đã nhận" (ack).
- Listener lỗi rồi được thử lại (retry).

Nếu billing nhận `PrescriptionCreatedEvent` hai lần → **hai hóa đơn thuốc** cho một đơn. Reporting nhận `ServiceInvoicePaidEvent` hai lần
→ **doanh thu gấp đôi**. Đây là ngoại lệ **EX-19** "sự kiện nhận hai lần".

## 2. Lý thuyết

Một thao tác là **idempotent** nếu làm một lần hay nhiều lần **kết quả như nhau**. Ví dụ: bấm nút gọi thang máy 5 lần thì thang vẫn chỉ
đến một lần.

**Idempotent Consumer**: bên nhận nhớ "sự kiện nào đã xử lý rồi" (theo `eventId`). Gặp lại thì bỏ qua.

Điều kiện để làm đúng:
1. Mỗi sự kiện có **mã duy nhất** (`eventId`, sinh một lần lúc tạo sự kiện, gửi lại vẫn giữ nguyên mã).
2. Việc "ghi nhớ đã xử lý" và "thay đổi nghiệp vụ" nằm **cùng một giao dịch**: cùng thành công hoặc cùng thất bại.

Vì sao điều kiện 2 quan trọng?
- Ghi nhớ **trước**, xử lý **sau** mà xử lý lỗi → đã đánh dấu xong nhưng thực ra chưa làm → mất việc.
- Xử lý **trước**, ghi nhớ **sau** mà ghi nhớ lỗi → lần sau xử lý lại → làm hai lần.

## 3. Trong dự án

### 3.1. Bảng `processed_events`

Có trong 4 CSDL nhận sự kiện (appointment, clinical, billing, reporting):

```sql
CREATE TABLE processed_events (
    event_id     BINARY(16)   NOT NULL,     -- eventId của sự kiện
    consumer     VARCHAR(100) NOT NULL,     -- tên listener đã xử lý
    processed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer)        -- khóa chính KÉP
);
```

**Vì sao khóa chính gồm cả `consumer`?** Một service có thể có **nhiều listener** cho cùng một sự kiện. Ví dụ reporting có 8 listener; nếu
một sự kiện được hai listener khác nhau trong cùng service xử lý, mỗi listener phải được ghi nhận riêng.

### 3.2. `IdempotentConsumer`: một hàm dùng chung

[IdempotentConsumer.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/processed/IdempotentConsumer.java):

```java
@Transactional(rollbackFor = Exception.class)                // MỘT giao dịch bao cả kiểm tra + hành động + ghi nhớ
public <T> T call(UUID eventId, String consumer, Supplier<T> action) {
    ProcessedEventId id = new ProcessedEventId(eventId, consumer);
    if (repository.existsById(id)) {
        log.info("Bỏ qua sự kiện {} đã được {} xử lý", eventId, consumer);
        return null;                                          // ĐÃ XỬ LÝ → bỏ qua
    }
    T result = action.get();                                  // làm việc nghiệp vụ
    repository.saveAndFlush(new ProcessedEvent(id, clock.instant()));   // ghi nhớ; flush ngay để trùng khóa thì lỗi NGAY
    return result;
}
```

**Trường hợp khó: hai bản cùng một sự kiện tới đồng thời.** Cả hai cùng `existsById` → `false` (chưa ai ghi), cùng chạy hành động.
Nhưng khi `saveAndFlush`, bản thứ hai **vấp khóa chính** (đã có dòng `(eventId, consumer)`) → ném lỗi → **cả giao dịch của bản thứ hai
rollback**, kể cả hành động nghiệp vụ nó vừa làm. RabbitMQ thử lại bản đó → lần này `existsById = true` → bỏ qua. Kết quả: đúng một lần.

Để chắc chắn có lỗi trùng khóa, entity [ProcessedEvent](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/processed/ProcessedEvent.java)
luôn báo `isNew() = true`, buộc Spring Data làm `INSERT` (thay vì `merge` = SELECT rồi UPDATE, sẽ không bao giờ trùng khóa).

### 3.3. Dùng trong listener

Mọi listener có ghi CSDL đều bọc nghiệp vụ trong `idempotentConsumer.run(...)`.
[PrescriptionCreatedListener.java](../vetclinic-backend/billing-pharmacy-service/src/main/java/com/vetclinic/billing/event/PrescriptionCreatedListener.java):

```java
public static final String CONSUMER = "billing.prescription-created";

@RabbitListener(queues = BillingRabbitConfig.PRESCRIPTION_CREATED_QUEUE)
public void onPrescriptionCreated(PrescriptionCreatedEvent event) {
    idempotentConsumer.run(event.eventId(), CONSUMER, () -> invoiceService.createDraft(event));
}
```

**Phòng thủ nhiều lớp:** billing còn có `medicine_invoices.prescription_id UNIQUE`, nên dù có lọt qua lớp 1 thì CSDL vẫn không cho hai
hóa đơn cùng một đơn thuốc. Reporting cũng kiểm tra "dòng doanh thu của hóa đơn này đã có chưa" trước khi ghi
([ReportingProjectionService](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/service/ReportingProjectionService.java)).

### 3.4. Các kiểu chống trùng khác trong dự án

Không phải chỗ nào cũng dùng bảng `processed_events`. Chọn cách phù hợp với từng nơi:

| Nơi | Cách chống trùng | Vì sao |
|---|---|---|
| appointment, clinical, billing, reporting | Bảng `processed_events` + giao dịch | Có CSDL, thay đổi dữ liệu nghiệp vụ |
| notification-service | Bộ nhớ: [ProcessedEvents.java](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/service/ProcessedEvents.java), giữ 5000 khóa gần nhất | Không có CSDL; lỡ gửi trùng thông báo sau khi khởi động lại thì không sai dữ liệu |
| Phát phiếu xếp hàng (clinical) | Khóa Redis `queue:dedupe:...` ngay trong script Lua (file 11) | Phiếu nằm ở Redis, không ở MySQL |
| Bảng tổng hợp báo cáo | `INSERT ... ON DUPLICATE KEY UPDATE` cùng giao dịch với `processed_events` | Cộng dồn số liệu theo ngày |

notification tách hai khóa `push:<eventId>` và `mail:<eventId>`: đẩy WebSocket xong mà gửi email lỗi thì lần thử lại chỉ gửi lại email,
không đẩy thông báo lần hai.

```java
processed.runOnce("push:" + event.eventId(), () -> { dispatcher.toUser(...); });
processed.runOnce("mail:" + event.eventId(), () -> { emailService.send(...); });
```

## 4. Thử tận tay

```sql
-- Mỗi đơn thuốc chỉ có đúng một dòng xử lý và đúng một hóa đơn thuốc
SELECT BIN_TO_UUID(event_id) AS event_id, consumer, processed_at
FROM pet_clinic_billing_pharmacy_db.processed_events
WHERE consumer = 'billing.prescription-created' ORDER BY processed_at DESC LIMIT 5;

-- Không có đơn thuốc nào có 2 hóa đơn (kết quả phải rỗng)
SELECT prescription_id, COUNT(*) FROM pet_clinic_billing_pharmacy_db.medicine_invoices
GROUP BY prescription_id HAVING COUNT(*) > 1;

-- Các listener của reporting
SELECT consumer, COUNT(*) FROM pet_clinic_reporting_db.processed_events GROUP BY consumer;
```

Kịch bản [e2e/v4_resilience.mjs](../e2e/v4_resilience.mjs) có phần EX-19: gửi lại cùng một sự kiện lên RabbitMQ và kiểm tra không phát
sinh hóa đơn / doanh thu thứ hai.

## 5. Tự kiểm tra

1. "Idempotent" nghĩa là gì? Cho ví dụ đời thường.
2. Vì sao khóa chính `processed_events` gồm cả `consumer`?
3. Vì sao việc ghi `processed_events` phải cùng giao dịch với thay đổi nghiệp vụ? Điều gì xảy ra nếu tách ra?
4. Hai bản của một sự kiện tới cùng lúc. Mô tả từng bước để thấy chỉ một bản được xử lý.
5. notification không có CSDL, nó chống trùng thế nào? Chấp nhận rủi ro gì?
