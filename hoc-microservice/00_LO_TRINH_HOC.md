# Học Microservice qua dự án VetClinic

Bộ tài liệu này giải thích **mọi chỗ dùng kiến trúc microservice** trong mã nguồn VetClinic, cho người chưa biết gì về microservice.
Mỗi file học một chủ đề, theo cùng một khuôn:

1. **Vấn đề**: nếu không có kỹ thuật này thì hỏng ở đâu.
2. **Lý thuyết**: khái niệm, giải thích bằng ví dụ đời thường.
3. **Trong dự án**: dùng ở đâu, file nào, dòng nào.
4. **Code từng bước**: đoạn code thật (đã rút gọn phần `import`) và giải thích từng dòng quan trọng.
5. **Thử tận tay**: lệnh chạy trên hệ thống Docker đang chạy để tự thấy kết quả.
6. **Tự kiểm tra**: vài câu hỏi; trả lời được là đã hiểu.

Đường dẫn file trong tài liệu đều bấm được (tính từ thư mục `hoc-microservice/`).

---

## Thứ tự học đề xuất

| # | File | Học gì | Độ khó |
|---|---|---|---|
| 1 | [01_MICROSERVICE_LA_GI.md](01_MICROSERVICE_LA_GI.md) | Microservice là gì, VetClinic chia thành những service nào và vì sao | ★ |
| 2 | [02_SERVICE_DISCOVERY_EUREKA.md](02_SERVICE_DISCOVERY_EUREKA.md) | Các service tìm thấy nhau bằng tên (Eureka) | ★ |
| 3 | [03_API_GATEWAY.md](03_API_GATEWAY.md) | Một cổng vào duy nhất: định tuyến, chặn `/internal`, CORS | ★★ |
| 4 | [04_XAC_THUC_JWT_XUYEN_SERVICE.md](04_XAC_THUC_JWT_XUYEN_SERVICE.md) | Đăng nhập một lần, 7 service đều biết bạn là ai | ★★ |
| 5 | [05_DATABASE_PER_SERVICE.md](05_DATABASE_PER_SERVICE.md) | Mỗi service một CSDL, khóa ngoại logic, cột snapshot | ★★ |
| 6 | [06_GOI_DONG_BO_OPENFEIGN.md](06_GOI_DONG_BO_OPENFEIGN.md) | Service này hỏi service kia qua HTTP (Feign) | ★★ |
| 7 | [07_CIRCUIT_BREAKER.md](07_CIRCUIT_BREAKER.md) | Service kia sập thì không kéo cả hệ thống sập theo | ★★★ |
| 8 | [08_RABBITMQ_SU_KIEN_BAT_DONG_BO.md](08_RABBITMQ_SU_KIEN_BAT_DONG_BO.md) | Báo tin cho nhau bằng sự kiện (RabbitMQ): exchange, queue, DLQ | ★★★ |
| 9 | [09_TRANSACTIONAL_OUTBOX.md](09_TRANSACTIONAL_OUTBOX.md) | Đã lưu CSDL thì chắc chắn gửi được sự kiện (Outbox) | ★★★★ |
| 10 | [10_IDEMPOTENT_CONSUMER.md](10_IDEMPOTENT_CONSUMER.md) | Nhận một sự kiện hai lần cũng chỉ xử lý một lần | ★★★ |
| 11 | [11_HANG_DOI_REDIS.md](11_HANG_DOI_REDIS.md) | Hàng đợi khám dùng chung trên Redis, script Lua nguyên tử | ★★★★ |
| 12 | [12_CQRS_BAO_CAO.md](12_CQRS_BAO_CAO.md) | Báo cáo có CSDL đọc riêng, dựng từ sự kiện (CQRS) | ★★★ |
| 13 | [13_WEBSOCKET_THONG_BAO.md](13_WEBSOCKET_THONG_BAO.md) | Thông báo realtime qua WebSocket STOMP | ★★ |
| 14 | [14_DONG_THOI_VA_KHOA.md](14_DONG_THOI_VA_KHOA.md) | Nhiều request cùng lúc: khóa MySQL, `@Version`, `FOR UPDATE` | ★★★★ |
| 15 | [15_COMMON_LIBRARY.md](15_COMMON_LIBRARY.md) | Thư viện dùng chung giữa các service, bẫy khi chia sẻ code | ★★★ |
| 16 | [16_DOCKER_COMPOSE_TRIEN_KHAI.md](16_DOCKER_COMPOSE_TRIEN_KHAI.md) | Chạy 13 container bằng một lệnh, thứ tự khởi động, healthcheck | ★★ |
| 17 | [17_LUONG_NGHIEP_VU_DAU_CUOI.md](17_LUONG_NGHIEP_VU_DAU_CUOI.md) | **Ghép tất cả**: đi theo một ca khám từ đặt lịch đến báo cáo | ★★★ |
| 18 | [18_CAU_HOI_ON_TAP_BAO_VE.md](18_CAU_HOI_ON_TAP_BAO_VE.md) | Câu hỏi hội đồng hay hỏi và cách trả lời | – |

**Cách học hiệu quả:**
- Đọc lần lượt 1 → 8 trước (nền tảng), sau đó 9 → 16, cuối cùng đọc **file 17** để thấy mọi mảnh ghép chạy cùng nhau.
- Mỗi file làm phần **Thử tận tay**. Thấy dữ liệu thật dễ nhớ hơn đọc lý thuyết rất nhiều.
- Mở file code được nhắc tới ngay bên cạnh, đọc cả chú thích tiếng Việt trong code: chú thích trong dự án này viết rất kỹ lý do.

---

## Bức tranh toàn cảnh (nhìn trước, hiểu dần)

```
                         Trình duyệt (React, cổng 3000)
                                    │  HTTP + WebSocket
                                    ▼
                    ┌────────────────────────────────┐
                    │   api-gateway  (cổng 8080)     │  ← cổng vào DUY NHẤT
                    │   kiểm JWT, định tuyến         │
                    └───────────────┬────────────────┘
                                    │ hỏi Eureka: "auth-service ở đâu?"
       ┌──────────────┬─────────────┼──────────────┬──────────────┬──────────────┐
       ▼              ▼             ▼              ▼              ▼              ▼
  auth-service  appointment-   clinical-     billing-       notification-  reporting-
    (8081)      service (8082) service (8083) pharmacy(8084) service (8085) service (8086)
       │              │             │    │         │              │              │
   auth_db     appointment_db  clinical_db  Redis  billing_db    (không DB)   reporting_db
       │              │             │              │              │              │
       └──────────────┴──── RabbitMQ (exchange "clinic.events") ──┴──────────────┘
                                  ▲ các service gửi / nhận sự kiện

              service-registry (Eureka, 8761): sổ địa chỉ của mọi service
```

- **Mũi tên đứng** (Gateway → service, service → service qua Feign): gọi **đồng bộ**, hỏi rồi chờ trả lời.
- **RabbitMQ**: gửi **bất đồng bộ**, báo tin rồi làm việc khác, không chờ.
- **Mỗi service một CSDL riêng**: không service nào đọc / ghi thẳng vào CSDL của service khác.

---

## Từ điển nhanh

| Thuật ngữ | Nghĩa ngắn gọn | Học ở file |
|---|---|---|
| Service / microservice | Một chương trình chạy riêng, làm một mảng nghiệp vụ, có CSDL riêng | 01 |
| Monolith | Một chương trình duy nhất làm tất cả | 01 |
| Service Discovery / Eureka | "Danh bạ" để service tìm địa chỉ của nhau bằng tên | 02 |
| API Gateway | Cổng vào duy nhất, mọi request từ ngoài đi qua đây | 03 |
| JWT | "Thẻ thông hành" có chữ ký, chứng minh bạn đã đăng nhập | 04 |
| Database per Service | Mỗi service sở hữu một CSDL, không dùng chung | 05 |
| Snapshot (bản chụp) | Lưu bản sao dữ liệu của service khác để khỏi phải hỏi lại | 05 |
| Feign | Thư viện gọi HTTP sang service khác như gọi hàm Java | 06 |
| Circuit Breaker | "Cầu dao": service kia hỏng liên tục thì ngắt, không gọi nữa | 07 |
| Message broker / RabbitMQ | "Bưu điện" chuyển tin nhắn (sự kiện) giữa các service | 08 |
| Event / sự kiện | Tin báo "việc X vừa xảy ra" (vd. "đã thu tiền hóa đơn") | 08 |
| DLQ (Dead Letter Queue) | Hộp chứa tin nhắn xử lý lỗi nhiều lần, để người kiểm tra | 08 |
| Transactional Outbox | Ghi sự kiện vào bảng CSDL cùng giao dịch, gửi đi sau | 09 |
| At-least-once | Tin nhắn chắc chắn tới, nhưng có thể tới **hơn một lần** | 09, 10 |
| Idempotent | Làm một lần hay nhiều lần, kết quả như nhau | 10 |
| Lua script (Redis) | Đoạn lệnh chạy trọn vẹn trong Redis, không ai chen ngang | 11 |
| CQRS | Tách mô hình ghi và mô hình đọc (báo cáo có CSDL riêng) | 12 |
| Eventual consistency | Dữ liệu các service **sẽ** khớp nhau, sau một khoảng ngắn | 05, 08, 12 |
| Saga (choreography) | Một nghiệp vụ dài chia thành nhiều bước ở nhiều service, nối với nhau bằng sự kiện | 17 |
