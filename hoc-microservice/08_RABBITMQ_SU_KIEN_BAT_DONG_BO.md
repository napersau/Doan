# 08. Giao tiếp bất đồng bộ bằng sự kiện: RabbitMQ

## 1. Vấn đề

Thu ngân bấm "Đã thu tiền" ca VIS-123. Ngay sau đó, **bốn** việc phải xảy ra ở **ba** service:
1. Hàng đợi (clinical) phát phiếu số thứ tự cho khách.
2. Báo cáo (reporting) cộng doanh thu.
3. Thông báo (notification) đẩy tin cho Lễ tân / Bác sĩ.
4. ...và sau này có thể thêm việc khác (tích điểm khách hàng chẳng hạn).

Nếu dùng Feign, clinical phải **biết và gọi** từng service, chờ từng cái trả lời. Một cái sập thì thu tiền lỗi? Thêm service mới
thì phải sửa clinical? Rất tệ.

## 2. Lý thuyết

### 2.1. Sự kiện (event)

**Sự kiện** là một thông báo "**việc X vừa xảy ra**", đặt tên ở thì quá khứ: `ServiceInvoicePaidEvent` (đã thu tiền hóa đơn dịch vụ),
`VisitCheckedInEvent` (đã tiếp đón). Bên gửi **không biết và không quan tâm** ai nhận. Ai quan tâm thì tự đăng ký nghe.

Ví dụ đời thường: thay vì gọi điện báo từng phòng, thu ngân **dán một tờ thông báo lên bảng tin chung**. Phòng nào cần thì tự đọc.

### 2.2. Message Broker: RabbitMQ

**RabbitMQ** là "bưu điện" chuyển tin nhắn. Các khái niệm:

```
 Producer ──gửi tin + routing key──► EXCHANGE ──(binding)──► QUEUE ──► Consumer
 (bên gửi)  "invoice.service_paid"   "clinic.events"          (hộp thư     (bên nhận)
                                     (bàn phân loại)           của 1 bên nhận)
```

| Khái niệm | Ví dụ đời thường | Trong dự án |
|---|---|---|
| **Exchange** | Bàn phân loại thư ở bưu điện | Một exchange chung `clinic.events`, loại **topic** |
| **Routing key** | Địa chỉ ghi trên phong bì | `invoice.service_paid`, `visit.checked_in`... |
| **Queue** | Hộp thư riêng của một người nhận | `reporting-service.invoice.service_paid` |
| **Binding** | "Thư có địa chỉ X thì bỏ vào hộp Y" | Gắn queue với exchange theo routing key |
| **Consumer** | Người mở hộp thư đọc | Phương thức có `@RabbitListener` |

**Điểm mấu chốt:** một sự kiện gửi lên exchange được **sao chép vào mọi queue** có binding khớp. Ba service nghe `invoice.service_paid`
→ ba queue → mỗi service nhận một bản, xử lý độc lập. Service này lỗi không ảnh hưởng service kia.

### 2.3. Bền vững, thử lại, DLQ

- **Durable queue + persistent message**: RabbitMQ khởi động lại thì tin nhắn vẫn còn (ghi xuống đĩa).
- **Consumer tắt**: tin nhắn **nằm chờ** trong queue, consumer bật lại thì xử lý tiếp. Đây là lý do tắt reporting một lúc không mất doanh thu.
- **Retry**: xử lý lỗi thì thử lại vài lần.
- **DLQ (Dead Letter Queue)**: thử hết số lần mà vẫn lỗi → chuyển sang "hộp thư chết" để người kiểm tra, **không** thử mãi làm tắc queue.

## 3. Trong dự án

### 3.1. Danh mục sự kiện: một file duy nhất

[EventRouting.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/EventRouting.java) trong `common-library`:

```java
public static final String EXCHANGE = "clinic.events";
public static final String DEAD_LETTER_EXCHANGE = "clinic.events.dlx";
public static final String DEAD_LETTER_SUFFIX = ".dlq";

public static final String APPOINTMENT_BOOKED = "appointment.booked";
public static final String VISIT_CHECKED_IN = "visit.checked_in";
public static final String INVOICE_SERVICE_PAID = "invoice.service_paid";
public static final String PRESCRIPTION_CREATED = "prescription.created";
// ... tổng cộng 17 routing key

public static String queueName(String serviceName, String routingKey) {
    return serviceName + "." + routingKey;          // quy ước tên queue: <service>.<routing-key>
}
```

Bảng ai gửi, ai nhận (nguồn: [THIET_KE_KIEN_TRUC_MICROSERVICES.md](../docs/THIET_KE_KIEN_TRUC_MICROSERVICES.md) §5.2):

| Routing key | Bên gửi | Bên nhận (mỗi bên một queue riêng) |
|---|---|---|
| `appointment.booked` / `.status_changed` / `.no_show` | appointment | notification |
| `visit.checked_in` | clinical | appointment (đổi lịch → CHECKED_IN), notification, reporting, clinical (phiếu cấp cứu) |
| `invoice.service_paid` | clinical | clinical (phát phiếu), reporting (doanh thu), notification |
| `invoice.service_refunded`, `visit.cancelled` | clinical | reporting, notification |
| `visit.results_ready` | clinical | clinical (phiếu ưu tiên 1), notification |
| `visit.completed` | clinical | reporting, notification |
| `prescription.created` | clinical | billing (tạo hóa đơn thuốc nháp) |
| `pharmacy.medicine_invoice_paid` | billing | reporting, notification |
| `pharmacy.low_stock` | billing | notification |
| `user.profile_updated`, `user.status_changed` | auth | appointment, clinical, reporting, notification |
| `pet.updated` | clinical | appointment, reporting |
| `queue.ticket_issued`, `visit.patient_called` | clinical | notification |

Lưu ý: clinical **tự nghe sự kiện của chính mình** (`invoice.service_paid` → phát phiếu). Lý do nằm ở file 09 và 11.

### 3.2. Nội dung sự kiện: một `record` Java

Mọi sự kiện là `record` trong package [common-library/.../event](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/),
cài interface [DomainEvent](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/DomainEvent.java):

```java
public interface DomainEvent extends BaseEvent {
    UUID eventId();         // mã duy nhất (UUIDv7) để bên nhận chống xử lý trùng (file 10)
    Instant occurredAt();   // thời điểm xảy ra
    UUID actorUserId();     // ai làm (null = hệ thống tự động)
    String routingKey();    // gửi với routing key nào
}
```

Ví dụ [ServiceInvoicePaidEvent.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/ServiceInvoicePaidEvent.java) (rút gọn):

```java
public record ServiceInvoicePaidEvent(
        UUID eventId, Instant occurredAt, UUID actorUserId,
        UUID visitId, String visitCode, UUID petId, String petName, String ownerName,
        UUID doctorId, String doctorName, boolean emergency, UUID cashierId, Instant paidAt,
        List<Line> lines                 // từng hóa đơn vừa thu: dịch vụ gì, bao nhiêu tiền, hàng đợi nào
) implements DomainEvent {

    /** Dạng cũ trước khi có doctorName. */
    public ServiceInvoicePaidEvent(UUID eventId, ..., UUID doctorId, boolean emergency, ...) {
        this(eventId, ..., doctorId, null, emergency, ...);    // trường mới = null
    }

    @Override
    public String routingKey() { return EventRouting.INVOICE_SERVICE_PAID; }
}
```

Hai điều đáng học:
1. **Sự kiện mang đủ dữ liệu** (tên thú cưng, số tiền từng dòng...) để bên nhận **không phải gọi ngược lại** clinical. Reporting cộng
   doanh thu chỉ từ nội dung sự kiện.
2. **Constructor "dạng cũ"**: khi thêm trường mới vào sự kiện đang chạy, giữ constructor cũ và cho trường mới được `null`, vì trong queue
   có thể còn tin nhắn kiểu cũ chưa xử lý. Đây gọi là **tương thích ngược** (backward compatibility).

Sự kiện được chuyển thành JSON khi gửi. [RabbitCommonConfig.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/RabbitCommonConfig.java)
cấu hình bộ chuyển đổi JSON và **chỉ tin** package `com.vetclinic.common.event` (không cho dựng lớp lạ từ tin nhắn: an toàn).

### 3.3. Khai báo exchange (dùng chung) và queue (mỗi service)

**Exchange** khai báo một lần trong `common-library`:

```java
@Bean
public TopicExchange clinicEventsExchange() {
    return new TopicExchange(EventRouting.EXCHANGE, true, false);   // durable = true
}
@Bean
public DirectExchange clinicEventsDeadLetterExchange() {
    return new DirectExchange(EventRouting.DEAD_LETTER_EXCHANGE, true, false);
}

/** Queue bền vững; tin nhắn lỗi (hết retry) được đẩy sang <queue>.dlq qua clinic.events.dlx. */
public static Queue durableQueueWithDlq(String queueName) {
    return QueueBuilder.durable(queueName)
            .withArgument("x-dead-letter-exchange", EventRouting.DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", queueName)
            .build();
}
```

**Queue** do service **nhận** tự khai báo. Ví dụ
[AppointmentRabbitConfig.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/event/AppointmentRabbitConfig.java):

```java
public static final String VISIT_CHECKED_IN_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CHECKED_IN;
// = "appointment-service.visit.checked_in"

for (String[] entry : queues) {
    Queue queue = RabbitCommonConfig.durableQueueWithDlq(queueName);         // queue chính
    Queue deadLetterQueue = RabbitCommonConfig.deadLetterQueue(queueName);   // queue chết: <tên>.dlq
    declarations.add(BindingBuilder.bind(queue).to(clinicEventsExchange).with(entry[1]));   // nhận routing key này
    declarations.add(BindingBuilder.bind(deadLetterQueue).to(clinicEventsDeadLetterExchange).with(queueName));
}
```

Lúc service khởi động, Spring AMQP tự tạo exchange, queue, binding trên RabbitMQ nếu chưa có.

### 3.4. Bên nhận: `@RabbitListener`

[VisitCheckedInListener.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/event/VisitCheckedInListener.java):

```java
@RabbitListener(queues = AppointmentRabbitConfig.VISIT_CHECKED_IN_QUEUE)
public void onVisitCheckedIn(VisitCheckedInEvent event) {        // JSON được tự chuyển về record
    if (event.appointmentId() == null) {
        return;                                                   // khách vãng lai: không có lịch hẹn để cập nhật
    }
    idempotentConsumer.run(event.eventId(), CONSUMER,            // chống xử lý trùng (file 10)
            () -> appointmentService.markCheckedIn(event.appointmentId(), event.actorUserId(), event.visitCode()));
}
```

Đây là ví dụ kinh điển của microservice: clinical **không ghi** vào CSDL appointment. clinical báo "đã tiếp đón", appointment **tự** cập
nhật lịch hẹn của mình.

### 3.5. Retry 3 lần rồi vào DLQ

```yaml
# application.yml của mọi service có nhận sự kiện
spring.rabbitmq.listener.simple:
  default-requeue-rejected: false   # hết retry: KHÔNG trả lại queue (tránh lặp vô hạn) → sang DLQ
  retry:
    enabled: true
    max-attempts: 3                 # thử tối đa 3 lần
    initial-interval: 1000ms        # chờ 1 giây trước lần 2
    multiplier: 2                   # rồi 2 giây trước lần 3
```

```
Tin nhắn tới → listener ném lỗi → chờ 1s → thử lần 2 → lỗi → chờ 2s → thử lần 3 → lỗi
   → RabbitMQ chuyển sang clinic.events.dlx → queue "<tên>.dlq" → nằm đó chờ người xử lý
```

Lỗi tạm thời (MySQL vừa khởi động lại) thường hết sau vài giây nên retry là đủ. Lỗi thật (dữ liệu sai) thì vào DLQ, không làm tắc
các tin nhắn phía sau.

### 3.6. Bên gửi: KHÔNG gọi `rabbitTemplate.convertAndSend` trực tiếp

Bạn sẽ thấy code nghiệp vụ **không bao giờ** tự gửi lên RabbitMQ. Thay vào đó:

```java
outboxWriter.write("VISIT", visit.getId(), new VisitCheckedInEvent(...));
```

Tại sao? Vì gửi thẳng có lỗ hổng nghiêm trọng: **đó là toàn bộ nội dung file 09**.

Ngoại lệ duy nhất: hai sự kiện **chỉ để thông báo tức thời** (`QueueTicketIssuedEvent`, `PatientCalledEvent`) gửi thẳng sau khi giao dịch
commit qua [DomainEventRabbitPublisher.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/DomainEventRabbitPublisher.java),
vì mất một thông báo "mời vào phòng" không làm sai dữ liệu:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void publish(DomainEvent event) {
    rabbitTemplate.convertAndSend(EventRouting.EXCHANGE, event.routingKey(), event);
}
```

## 4. Thử tận tay

Mở giao diện quản trị RabbitMQ (chỉ mở ở chế độ dev):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d rabbitmq-broker
```

Vào `http://localhost:15672`, đăng nhập bằng `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` trong file `.env`.

- Tab **Exchanges** → bấm `clinic.events` → mục **Bindings**: thấy mọi queue và routing key đã gắn.
- Tab **Queues**: thấy các queue dạng `reporting-service.invoice.service_paid` và các queue `.dlq` tương ứng (bình thường DLQ trống, 0 tin).

Thí nghiệm "tin nhắn nằm chờ":
```bash
docker compose stop reporting-service
# Trên giao diện: thu tiền một ca. Trong RabbitMQ UI, queue reporting-service.invoice.service_paid có "Ready: 1"
docker compose start reporting-service
# Vài giây sau Ready về 0, báo cáo doanh thu đã có khoản vừa thu
```

## 5. Tự kiểm tra

1. Exchange, queue, routing key, binding là gì? Vẽ đường đi của `invoice.service_paid` tới reporting.
2. Ba service cùng nghe `invoice.service_paid`. RabbitMQ gửi mấy bản? Một service lỗi có ảnh hưởng service khác không?
3. Vì sao sự kiện mang theo tên thú cưng, số tiền... thay vì chỉ mang `invoiceId`?
4. Listener lỗi 3 lần liên tiếp thì tin nhắn đi đâu? Vì sao không thử lại mãi mãi?
5. Thêm trường mới vào một sự kiện đang chạy cần chú ý gì?
6. Vì sao appointment tự đổi lịch hẹn thành CHECKED_IN, thay vì clinical làm hộ?
