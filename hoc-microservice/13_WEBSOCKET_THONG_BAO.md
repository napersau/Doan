# 13. Thông báo realtime: WebSocket STOMP + notification-service

## 1. Vấn đề

Bác sĩ chỉ định xét nghiệm. Khi Kỹ thuật viên nhập xong kết quả, màn hình Bác sĩ phải **tự hiện** "Ca VIS-123 đã có kết quả", không cần
bấm tải lại. HTTP thông thường chỉ có chiều **client hỏi → server trả lời**; server không tự gửi được.

Cách thô là cứ 2 giây hỏi một lần (*polling*): tốn tài nguyên, vẫn trễ. Cần một kênh **server đẩy xuống** được.

## 2. Lý thuyết

- **WebSocket**: một kết nối **hai chiều, giữ mở lâu dài** giữa trình duyệt và server. Server gửi xuống bất cứ lúc nào.
- **STOMP**: giao thức nhắn tin chạy trên WebSocket, có khái niệm "đăng ký kênh" (`SUBSCRIBE /topic/...`) và "gửi tới kênh". Giống
  "đăng ký nhận tin nhóm Zalo".

Các lệnh (frame) STOMP chính:

| Frame | Ai gửi | Ý nghĩa |
|---|---|---|
| `CONNECT` | Client | Mở phiên (kèm token) |
| `SUBSCRIBE` | Client | Đăng ký nghe một kênh |
| `MESSAGE` | Server | Đẩy tin xuống kênh |
| `SEND` | Client | Gửi tin lên (dự án **cấm**) |

**Vì sao tách thành một service riêng?** Gửi email chậm (SMTP vài giây) và hay lỗi; giữ hàng trăm kết nối WebSocket tốn bộ nhớ. Đặt
trong clinical thì làm chậm và làm nặng nghiệp vụ khám. notification-service chỉ **nghe sự kiện** từ RabbitMQ và **đẩy** ra ngoài: nó sập
thì nghiệp vụ vẫn chạy, chỉ mất thông báo.

## 3. Trong dự án

### 3.1. Luồng tổng thể

```
clinical: KTV hoàn tất xét nghiệm → outbox ParaclinicalResultsReadyEvent ("visit.results_ready")
            │ RabbitMQ
            ▼
notification-service: NotificationEventListener nghe → soạn nội dung
            │ SimpMessagingTemplate
            ▼
/user/queue/notifications của đúng Bác sĩ phụ trách   ◄── WebSocket ──  trình duyệt Bác sĩ
            (đi qua Gateway: /ws → lb:ws://notification-service)
```

### 3.2. Kết nối đi qua Gateway

```yaml
# api-gateway/application.yml
- id: notification-websocket
  uri: lb:ws://notification-service      # ws:// = chuyển tiếp kết nối WebSocket
  predicates:
    - Path=/ws/**
```

`/ws/**` nằm trong danh sách công khai của Gateway (không kiểm JWT ở Gateway). Lý do: **trình duyệt không gắn được header
`Authorization` khi mở WebSocket**. Thay vào đó JWT được gửi trong frame `CONNECT` và notification-service tự kiểm (mục 3.4). Dự án không
truyền token qua URL `?token=` vì URL dễ bị ghi vào log.

### 3.3. Cấu hình STOMP

[WebSocketConfig.java](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/config/WebSocketConfig.java):

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOriginPatterns);   // điểm kết nối
}

@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue")        // broker trong bộ nhớ
            .setHeartbeatValue(new long[]{10_000, 10_000});  // nhịp tim 10 giây, kết nối nhàn rỗi không bị cắt
    registry.setUserDestinationPrefix("/user");             // kênh riêng từng người
}

@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new StompAuthChannelInterceptor(tokenVerifier));   // cổng bảo mật
}
```

Hai loại kênh:

| Kênh | Ai nhận | Ví dụ |
|---|---|---|
| `/topic/role/<VAI_TRÒ>` | Mọi người có vai trò đó | `/topic/role/RECEPTIONIST`: "Lịch hẹn mới chờ duyệt" |
| `/user/queue/notifications` | Đúng một người | Bác sĩ phụ trách: "Ca VIS-123 đã có kết quả" |

### 3.4. Bảo mật kênh

[StompAuthChannelInterceptor.java](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/security/StompAuthChannelInterceptor.java)
xét từng frame client gửi lên:

```java
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    switch (accessor.getCommand()) {
        case CONNECT, STOMP -> authenticate(accessor);          // bắt buộc JWT hợp lệ (kiểm bằng cùng JWT_SECRET)
        case SUBSCRIBE -> authorizeSubscription(accessor);      // chỉ được nghe kênh của mình
        case SEND -> throw new MessagingException("Không được gửi dữ liệu lên kênh thông báo");
        default -> requireUser(accessor);
    }
    return message;
}

private void authorizeSubscription(StompHeaderAccessor accessor) {
    Authentication user = requireUser(accessor);
    String destination = accessor.getDestination();
    if ("/user/queue/notifications".equals(destination)) return;           // kênh riêng: luôn được
    if (destination.startsWith("/topic/role/")
            && user có vai trò trùng với phần đuôi của destination) return; // Lễ tân chỉ nghe /topic/role/RECEPTIONIST
    throw new MessagingException("Không có quyền đăng ký kênh này");
}
```

Chủ nuôi không thể đăng ký `/topic/role/ADMIN` để nghe trộm thông báo của Quản trị viên.

### 3.5. Nghe sự kiện, đẩy thông báo

[NotificationEventListener.java](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/event/NotificationEventListener.java)
có listener cho 11 loại sự kiện. Ví dụ lịch hẹn mới:

```java
@RabbitListener(queues = NotificationRabbitConfig.APPOINTMENT_BOOKED_QUEUE)
public void onAppointmentBooked(AppointmentBookedEvent event) {
    processed.runOnce("push:" + event.eventId(), () -> {                 // chống trùng (file 10)
        if (pending) {
            dispatcher.toRole(RoleCode.RECEPTIONIST, message(..., "Lịch hẹn mới chờ duyệt", ...));
        }
        dispatcher.toUser(event.ownerId(), message(..., "Đã ghi nhận lịch hẹn", ...));
    });
    processed.runOnce("mail:" + event.eventId(), () -> {
        UserSummary owner = people.requireUser(event.ownerId());          // Feign → auth lấy email
        emailService.send(owner.email(), "[VetClinic] Xác nhận đặt lịch khám ...", ...);
    });
}
```

[NotificationDispatcher.java](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/service/NotificationDispatcher.java):

```java
public void toUser(UUID userId, NotificationMessage message) {
    history.addForUser(userId, message);    // lưu lịch sử để người chưa online xem lại sau
    messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", message);
}
public void toRole(RoleCode role, NotificationMessage message) {
    history.addForRole(role, message);
    messagingTemplate.convertAndSend("/topic/role/" + role.name(), message);
}
```

Email gửi qua SMTP tới **Mailpit** (hộp thư giả để test, không gửi ra Internet thật).

### 3.6. Phía giao diện

[fe/src/api/stomp.js](../fe/src/api/stomp.js) tự viết client STOMP tối giản:

```javascript
socket.onopen = () => socket.send(frame('CONNECT', { 'accept-version': '1.2', ..., Authorization: `Bearer ${token}` }));
// nhận CONNECTED rồi đăng ký:
socket.send(frame('SUBSCRIBE', { id: 'sub-user', destination: '/user/queue/notifications' }));
roleList.forEach((code) => socket.send(frame('SUBSCRIBE', { id: `sub-role-${code}`, destination: `/topic/role/${code}` })));
```

## 4. Thử tận tay

1. Mở hai trình duyệt (hoặc một cửa sổ ẩn danh): một đăng nhập `letan_hoa`, một đăng nhập `khach_lan`.
2. Ở cửa sổ khách, đặt lịch online → cửa sổ Lễ tân hiện ngay thông báo "Lịch hẹn mới chờ duyệt" (kênh `/topic/role/RECEPTIONIST`).
3. Lễ tân duyệt → cửa sổ khách hiện "Lịch hẹn đã được xác nhận" (kênh riêng của khách).

Xem email: mở Mailpit (chế độ dev, cổng 8025):
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mailpit
```
Vào `http://localhost:8025`, thấy thư "[VetClinic] Xác nhận đặt lịch khám..." (chỉ với chủ nuôi có email, ví dụ `khach_nam`).

Xem frame STOMP: F12 → tab **Network** → lọc **WS** → bấm vào kết nối `/ws` → tab **Messages**: thấy `CONNECT`, `SUBSCRIBE`, `MESSAGE`.

## 5. Tự kiểm tra

1. Vì sao không dùng HTTP thường để báo "đã có kết quả xét nghiệm"?
2. Vì sao JWT gửi trong frame `CONNECT` chứ không qua header như API thường?
3. `/topic/role/RECEPTIONIST` khác `/user/queue/notifications` thế nào?
4. Vì sao client bị cấm gửi frame `SEND`?
5. notification-service sập thì nghiệp vụ khám bệnh có bị ảnh hưởng không? Thông báo lúc đó đi đâu?
