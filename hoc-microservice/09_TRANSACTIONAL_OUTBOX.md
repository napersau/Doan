# 09. Transactional Outbox: đã lưu CSDL thì chắc chắn gửi được sự kiện

> Đây là kỹ thuật quan trọng và "ăn điểm" nhất của dự án. Đọc chậm.

## 1. Vấn đề: "ghi kép" (dual write)

Thu ngân thu tiền. Code phải làm **hai** việc ở **hai** hệ thống khác nhau:
1. Ghi CSDL: hóa đơn → `PAID`.
2. Gửi RabbitMQ: `ServiceInvoicePaidEvent` (để phát phiếu xếp hàng, cộng doanh thu).

Thử mọi cách sắp xếp:

**Cách A: gửi RabbitMQ bên trong giao dịch, trước commit**
```java
@Transactional
void thuTien() {
    invoice.setStatus(PAID);
    rabbitTemplate.send(event);      // đã gửi đi rồi
    ... một lỗi xảy ra ở đây → giao dịch ROLLBACK
}
```
→ Hóa đơn **không** được thu, nhưng sự kiện **đã gửi**: khách được phát phiếu khám dù chưa trả tiền. **Sai.**

**Cách B: commit xong mới gửi**
```java
thuTienVaCommit();                   // hóa đơn PAID đã lưu
rabbitTemplate.send(event);          // RabbitMQ đang sập / service crash đúng lúc này
```
→ Tiền đã thu, nhưng sự kiện **mất**: khách trả tiền mà **không bao giờ có phiếu khám**, báo cáo thiếu doanh thu. **Sai.**

Không có cách nào "ghi CSDL + gửi RabbitMQ" thành **một** thao tác nguyên tử, vì chúng là hai hệ thống, không chung giao dịch.
Đây chính là ngoại lệ **EX-18** trong tài liệu: "RabbitMQ / Redis gián đoạn sau khi thu tiền".

## 2. Lý thuyết: Transactional Outbox

Ý tưởng: **đừng gửi RabbitMQ trong nghiệp vụ. Ghi sự kiện vào một BẢNG của chính CSDL đó, trong CÙNG giao dịch.** Một tiến trình
riêng đọc bảng và gửi đi sau.

```
 ┌───────────── MỘT giao dịch MySQL ─────────────┐
 │  UPDATE service_invoices SET status='PAID'     │
 │  INSERT INTO outbox_events (..., 'PENDING')    │   ← cùng commit hoặc cùng rollback
 └────────────────────────────────────────────────┘
                     │
                     ▼  (mỗi 500 ms)
           OutboxRelay đọc các dòng PENDING
                     │ gửi RabbitMQ, chờ RabbitMQ xác nhận "đã nhận"
                     ▼
           UPDATE outbox_events SET status='PUBLISHED'
```

- Giao dịch rollback → dòng outbox cũng biến mất → **không gửi** sự kiện sai.
- Giao dịch commit → dòng outbox **chắc chắn** nằm trong CSDL → RabbitMQ sập thì relay **thử lại mãi** tới khi gửi được.

Ví dụ đời thường: thu ngân không chạy đi báo từng phòng. Thu tiền xong thì **ghi vào sổ "việc cần báo"** cùng lúc với ghi sổ thu.
Một nhân viên khác cứ vài phút xem sổ, đi báo, báo xong thì gạch. Nhân viên đó nghỉ ốm thì sổ vẫn còn, mai báo tiếp.

### Cái giá: at-least-once (ít nhất một lần)

Relay gửi xong, RabbitMQ nhận rồi, nhưng relay **crash trước khi kịp đánh dấu `PUBLISHED`** → lần sau gửi **lại** sự kiện đó. Nên Outbox
bảo đảm sự kiện tới **ít nhất một lần**, có thể **hai lần**. Bên nhận phải chịu được việc nhận trùng → **file 10**.

## 3. Trong dự án

### 3.1. Bảng `outbox_events`

Có trong 4 CSDL phát sự kiện (auth, appointment, clinical, billing). Cấu trúc ([docs/schema_init.sql](../docs/schema_init.sql)):

| Cột | Ý nghĩa |
|---|---|
| `id` BIGINT tự tăng | Thứ tự phát sinh, gửi theo thứ tự này |
| `event_id` BINARY(16) UNIQUE | `eventId` của sự kiện (bên nhận dùng chống trùng) |
| `aggregate_type`, `aggregate_id` | Sự kiện thuộc thực thể nào (`VISIT` + id đợt khám) |
| `event_type` | Tên lớp sự kiện, ví dụ `ServiceInvoicePaidEvent` |
| `routing_key` | Gửi với routing key nào |
| `payload` JSON | Toàn bộ nội dung sự kiện |
| `status` | `PENDING` → `PUBLISHED` (hoặc `FAILED` sau 10 lần lỗi) |
| `attempt_count`, `next_attempt_at`, `last_error` | Phục vụ thử lại, lùi dần |
| `created_at`, `published_at` | Thời điểm ghi, thời điểm gửi được |

### 3.2. Bật Outbox: một annotation

```java
@EnableOutbox              // trên lớp Application của auth, appointment, clinical, billing
@SpringBootApplication(scanBasePackages = "com.vetclinic")
public class ClinicalServiceApplication { ... }
```

[EnableOutbox.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/outbox/EnableOutbox.java) nạp entity
`OutboxEvent`, `OutboxWriter`, `OutboxRelay` và bật `@EnableScheduling`. Code nằm hết trong `common-library`, 4 service dùng chung.

### 3.3. Ghi: `OutboxWriter`

[OutboxWriter.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/outbox/OutboxWriter.java):

```java
@Transactional(propagation = Propagation.MANDATORY)    // BẮT BUỘC đang có giao dịch, không có thì báo lỗi lập trình
public OutboxEvent write(String aggregateType, UUID aggregateId, String routingKey, BaseEvent event) {
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return repository.save(OutboxEvent.builder()
            .eventId(event.eventId())
            .aggregateType(aggregateType)
            .aggregateId(aggregateId)
            .eventType(event.getClass().getSimpleName())
            .routingKey(routingKey)
            .payload(objectMapper.writeValueAsString(event))   // sự kiện → JSON
            .status(OutboxStatus.PENDING)
            .attemptCount(0)
            .nextAttemptAt(now)
            .createdAt(now)
            .build());
}
```

`Propagation.MANDATORY` là chốt an toàn: lỡ ai gọi `write(...)` ngoài giao dịch thì chương trình báo lỗi ngay, không âm thầm ghi một
dòng outbox "mồ côi" tách khỏi nghiệp vụ.

**Dùng trong nghiệp vụ thu tiền**: [ServiceInvoiceServiceImpl.doConfirmPayment()](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/service/impl/ServiceInvoiceServiceImpl.java):

```java
// toàn bộ hàm chạy trong tx.execute(...) = MỘT giao dịch
for (UUID id : wanted) {
    ServiceInvoice invoice = byInvoiceId.get(id);
    invoice.setPaymentStatus(PaymentStatus.PAID);
    invoice.setCashierId(cashierId);
    invoice.setPaidAt(now);
    invoiceRepository.saveAndFlush(invoice);
    paidLines.add(lineOf(invoice, detail));
}
if (!paidLines.isEmpty()) {
    // sự kiện đi qua outbox CÙNG giao dịch; QueueIssuerListener sẽ phát phiếu
    outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new ServiceInvoicePaidEvent(UuidV7.generate(clock), now, cashierId,
            visit.getId(), visit.getVisitCode(), ..., paidLines));
}
if (!anyPending && visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
    support.transition(visit, VisitStatus.COMPLETED, cashierId, "Đã thu đủ hóa đơn dịch vụ, hoàn tất đợt khám");
    outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCompletedEvent(...));   // 2 sự kiện, cùng giao dịch
}
```

Bây giờ hiểu vì sao clinical **tự nghe sự kiện của chính mình** để phát phiếu (file 08): phát phiếu cần Redis, mà Redis không chung
giao dịch với MySQL. Nếu phát phiếu ngay trong hàm thu tiền thì lại gặp đúng bài toán ghi kép. Đưa việc phát phiếu ra sau sự kiện
(đã nằm an toàn trong outbox) thì "**đã thu tiền là chắc chắn có phiếu**", kể cả khi Redis sập đúng lúc đó.

Các nơi khác dùng `outboxWriter.write`: đặt lịch (`AppointmentBookedEvent`), tiếp đón (`VisitCheckedInEvent`), thu tiền thuốc +
trừ kho (`MedicineInvoicePaidEvent`), khóa tài khoản (`UserStatusChangedEvent` qua
[UserEventPublisher](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/service/UserEventPublisher.java))...

### 3.4. Gửi: `OutboxRelay`

[OutboxRelay.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/outbox/OutboxRelay.java):

```java
@Scheduled(fixedDelayString = "${vetclinic.outbox.fixed-delay-ms:500}")   // 500 ms chạy một lần
@Transactional(isolation = Isolation.READ_COMMITTED)
public void publishPending() { publishBatch(); }

public int publishBatch() {
    List<OutboxEvent> batch = repository.lockNextBatch(clock.instant(), properties.getBatchSize());   // tối đa 100 dòng
    for (OutboxEvent event : batch) {
        try {
            send(event);                              // gửi + CHỜ RabbitMQ xác nhận
            event.markPublished(clock.instant());     // → PUBLISHED
        } catch (Exception ex) {
            event.markFailedAttempt(describe(ex), clock.instant(), properties.getMaxAttempts());
            break;   // GIỮ THỨ TỰ: sự kiện này lỗi thì không gửi các sự kiện phía sau
        }
    }
}
```

Câu truy vấn lấy lô ([OutboxEventRepository.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/outbox/OutboxEventRepository.java)):

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING' AND next_attempt_at <= :now
ORDER BY id
LIMIT :limit
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE SKIP LOCKED`: khóa các dòng vừa chọn; dòng nào bản khác đang khóa thì **bỏ qua**. Nếu sau này chạy 2 bản clinical-service,
hai relay lấy hai lô khác nhau, không gửi trùng, không chờ nhau.

**Chờ RabbitMQ xác nhận (publisher confirm):**

```java
private void send(OutboxEvent event) throws Exception {
    CorrelationData correlation = new CorrelationData(event.getEventId().toString());
    rabbitTemplate.send(EventRouting.EXCHANGE, event.getRoutingKey(), toMessage(event), correlation);
    var confirm = correlation.getFuture().get(properties.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);   // chờ tối đa 5 giây
    if (!confirm.ack()) {
        throw new IllegalStateException("RabbitMQ từ chối (nack): " + confirm.reason());
    }
}
```

Bật bằng `spring.rabbitmq.publisher-confirm-type: correlated`. Không chờ xác nhận thì "gửi không lỗi" chưa chắc là RabbitMQ đã nhận.

**Lùi dần khi lỗi** ([OutboxEvent.backoff](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/outbox/OutboxEvent.java)):
lần lỗi thứ n chờ 2^(n-1) giây (1, 2, 4, 8... tối đa 5 phút); lỗi đủ 10 lần thì `FAILED`. Mỗi ngày 03:00 xóa các dòng `PUBLISHED` cũ hơn 7 ngày.

### 3.5. Bài học thật: vì sao `READ_COMMITTED`?

Lúc kiểm thử EX-18 (tắt RabbitMQ khi đang thu tiền), nhóm gặp lỗi lạ: **thu tiền bị treo 50 giây rồi báo 409**. Nguyên nhân:
- Relay chạy ở mức cô lập mặc định của MySQL là `REPEATABLE READ`. Ở mức này, `SELECT ... FOR UPDATE` khóa cả **khoảng trống** trong chỉ mục
  (gap lock), không chỉ các dòng được chọn.
- RabbitMQ sập → relay đứng chờ xác nhận, **giữ khóa khoảng trống** suốt thời gian đó.
- Giao dịch thu tiền muốn `INSERT` dòng outbox mới vào đúng khoảng đó → **bị chặn** tới hết 50 giây chờ khóa.

Sửa: relay chạy `READ_COMMITTED` (chỉ khóa đúng các dòng đã chọn). Có kiểm thử hồi quy
[OutboxMySqlIT.java](../vetclinic-backend/common-library/src/test/java/com/vetclinic/common/it/OutboxMySqlIT.java). Bài học: **RabbitMQ sập
không được làm nghiệp vụ chính chậm theo.**

## 4. Thử tận tay

Trong Workbench (cổng 3307), xem outbox của clinical:

```sql
SELECT id, aggregate_type, event_type, routing_key, status, attempt_count, created_at, published_at
FROM pet_clinic_clinical_db.outbox_events ORDER BY id DESC LIMIT 5;
```

Kết quả thật (sau một ca chốt và thu tiền):
```
id   aggregate_type  event_type                 routing_key            status     attempt_count
297  VISIT           VisitCompletedEvent        visit.completed        PUBLISHED  0
296  VISIT           ServiceInvoicePaidEvent    invoice.service_paid   PUBLISHED  0
295  VISIT           PrescriptionCreatedEvent   prescription.created   PUBLISHED  0
```

**Thí nghiệm EX-18:**
```bash
docker compose stop rabbitmq-broker
```
Trên giao diện, thu tiền phí khám một ca → **vẫn thu được bình thường**, không treo. Chạy lại câu SQL: dòng mới có `status = PENDING`,
`attempt_count` tăng dần, `last_error` ghi lỗi kết nối.

```bash
docker compose start rabbitmq-broker
```
Vài giây sau dòng đó thành `PUBLISHED`, phiếu xếp hàng xuất hiện ở màn hình hàng đợi. **Không mất gì.**

## 5. Tự kiểm tra

1. "Ghi kép" là gì? Vì sao cả hai cách (gửi trước commit, gửi sau commit) đều sai?
2. Outbox giải quyết ghi kép thế nào? Vì sao dòng outbox phải nằm **cùng giao dịch**?
3. `Propagation.MANDATORY` trên `OutboxWriter.write` để làm gì?
4. Vì sao Outbox chỉ bảo đảm "ít nhất một lần" chứ không phải "đúng một lần"?
5. `FOR UPDATE SKIP LOCKED` giúp gì khi chạy nhiều bản service?
6. Vì sao relay dừng lô khi gặp một sự kiện lỗi, không gửi tiếp các sự kiện sau?
7. Vì sao việc phát phiếu xếp hàng không làm ngay trong hàm thu tiền?
