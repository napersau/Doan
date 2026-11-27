# 18. Câu hỏi ôn tập: hội đồng hay hỏi gì về microservice

Mỗi câu có **câu trả lời ngắn** (nói trong 30 giây) và chỗ để **chỉ vào code** nếu được hỏi sâu. Đọc xong 17 file trước rồi hãy luyện phần này.

---

## A. Kiến trúc tổng thể

**1. Vì sao chọn microservice cho phòng khám?**
Chia theo nghiệp vụ (tài khoản, đặt lịch, khám, thuốc, thông báo, báo cáo) để mỗi phần triển khai và hỏng độc lập: báo cáo hay email lỗi không
làm dừng việc khám; nhóm 3 người làm song song trên các service khác nhau. Đổi lại phải giải các bài toán phân tán (gọi nhau, mất tin nhắn, dữ
liệu lệch), và dự án giải từng bài bằng Gateway, Eureka, Feign, Circuit Breaker, RabbitMQ, Outbox, Idempotent Consumer. → File 01.

**2. Hệ thống có bao nhiêu service? Kể tên.**
8 module chạy: `service-registry`, `api-gateway` và 6 service nghiệp vụ `auth`, `appointment`, `clinical`, `billing-pharmacy`,
`notification`, `reporting`; cộng `common-library` (thư viện, không chạy riêng). Docker có 13 container (thêm MySQL, RabbitMQ, Redis,
Mailpit, frontend).

**3. Vì sao clinical-service lớn nhất (24 bảng), không tách nhỏ hơn?**
Các bước của một ca khám (tiếp đón, sinh hiệu, chỉ định, CLS, hóa đơn dịch vụ, đơn thuốc) gắn chặt trong cùng một trạng thái ca (SM-01) và
nhiều thao tác phải nằm trong **một giao dịch** (thu tiền + hoàn tất ca). Tách nhỏ sẽ phải dùng Saga cho cả những việc đơn giản. Ranh giới
service nên theo ranh giới giao dịch nghiệp vụ.

**4. Đồng bộ và bất đồng bộ dùng khi nào?**
Đồng bộ (Feign) khi **cần câu trả lời ngay** để quyết định, và chỉ để **đọc** (ví dụ đếm Bác sĩ để tính sức chứa). Bất đồng bộ (RabbitMQ) khi
**báo cho service khác** biết việc đã xảy ra để chúng tự cập nhật (thu tiền → phát phiếu, cộng doanh thu, thông báo). → File 01, 06, 08.

## B. Gateway, Eureka, bảo mật

**5. API Gateway làm gì?**
Cổng vào duy nhất (cổng 8080): định tuyến theo đường dẫn tới service qua Eureka (`lb://`), kiểm JWT một lần, xóa header giả mạo, cấu hình CORS
một chỗ, không định tuyến `/internal/**`. → [JwtAuthenticationFilter.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/security/JwtAuthenticationFilter.java), file 03.

**6. Service con xác thực người dùng thế nào?**
Không giải mã JWT lại. Gateway kiểm chữ ký rồi gắn `X-User-Id` / `X-User-Roles`; `HeaderAuthenticationFilter` trong common-library nạp vào
Spring Security để `@PreAuthorize` hoạt động. An toàn vì Gateway luôn xóa header client gửi và cổng service con không mở ra ngoài. → File 04.

**7. Nếu ai đó gửi thẳng `X-User-Roles: ADMIN` thì sao?**
Qua Gateway: header bị xóa trước khi kiểm token → 401. Gọi thẳng service con: không được vì chỉ cổng 8080 được mở ra máy host.

**8. Endpoint `/internal/**` không cần đăng nhập, có lỗ hổng không?**
Không, vì Gateway không có route cho `/internal` (trả 404) và service con không mở cổng ra ngoài; chỉ các container trong mạng Docker gọi được.
Các endpoint này chỉ đọc.

**9. Eureka dùng làm gì? Eureka sập thì sao?**
Danh bạ địa chỉ: service đăng ký tên + địa chỉ, gửi nhịp tim; bên gọi tra theo tên. Eureka sập thì các service vẫn gọi nhau được nhờ bản sao danh
bạ trong bộ nhớ, chỉ service mới khởi động là không tìm được. → File 02.

**10. Refresh Token chống đánh cắp thế nào?**
Mỗi token chỉ dùng một lần (xoay vòng), lưu dạng SHA-256. Token đã dùng mà bị đem ra dùng lại → coi là bị đánh cắp → thu hồi mọi phiên của người
đó. Hai request refresh đồng thời: `UPDATE ... WHERE revoked = false` chỉ cho một request thắng. → [AuthServiceImpl.refresh](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/service/impl/AuthServiceImpl.java).

## C. Dữ liệu

**11. Mỗi service một CSDL nhưng chỉ có một container MySQL, có mâu thuẫn không?**
Không. Một máy chủ MySQL chứa 5 CSDL logic, mỗi service chỉ kết nối CSDL của mình, không JOIN chéo, không khóa ngoại chéo. Tách thành 5 máy chủ
chỉ cần đổi `DB_HOST`, không sửa code. Gộp một container là để tiết kiệm tài nguyên khi demo.

**12. Không JOIN được giữa các service thì hiển thị tên chủ nuôi trên lịch hẹn thế nào?**
Lưu **snapshot** (`owner_name_snapshot`...) lúc tạo, cập nhật bất đồng bộ khi nhận `UserProfileUpdatedEvent`. Danh sách đọc snapshot nên không
phụ thuộc auth (NFR-10). → File 05.

**13. "Eventual consistency" có chấp nhận được không?**
Với tên hiển thị, báo cáo doanh thu: có, trễ dưới 1 giây. Với điều kiện ghi quan trọng (sức chứa khung giờ, Bác sĩ còn ACTIVE khi phân công,
tồn kho khi bán) thì **không** dựa vào snapshot mà kiểm tra trực tiếp tại thời điểm ghi.

## D. Sự kiện, Outbox, chống trùng (câu hỏi "ăn điểm")

**14. Vì sao không gửi RabbitMQ trực tiếp trong code nghiệp vụ?**
Bài toán ghi kép: gửi trước commit thì giao dịch rollback mà sự kiện đã đi; gửi sau commit thì RabbitMQ sập là mất sự kiện. Dự án dùng
**Transactional Outbox**: ghi sự kiện vào bảng `outbox_events` cùng giao dịch, `OutboxRelay` gửi sau mỗi 500 ms, chờ publisher confirm rồi đánh
dấu `PUBLISHED`. → File 09.

**15. Chứng minh Outbox hoạt động?**
Tắt RabbitMQ, thu tiền: vẫn thành công, dòng outbox `PENDING`. Bật lại: thành `PUBLISHED`, phiếu xuất hiện. Có kịch bản tự động
[e2e/v4_resilience.mjs](../e2e/v4_resilience.mjs) (EX-18).

**16. Outbox có gửi trùng không? Xử lý thế nào?**
Có thể (at-least-once): relay gửi xong mà chưa kịp đánh dấu. Bên nhận dùng **Idempotent Consumer**: bảng `processed_events` khóa
`(event_id, consumer)` ghi cùng giao dịch với thay đổi nghiệp vụ; trùng thì bỏ qua. Thêm lớp UNIQUE (`medicine_invoices.prescription_id`), khóa
`queue:dedupe` trên Redis. → File 10.

**17. Listener xử lý lỗi thì sao?**
Retry 3 lần (1 s, 2 s, 4 s), vẫn lỗi thì vào DLQ `<queue>.dlq`, không làm tắc queue. Riêng phiếu xếp hàng có `QueueReconcileJob` mỗi 60 giây bù
phiếu của sự kiện đã vào DLQ.

**18. Thêm trường vào một sự kiện đang chạy cần chú ý gì?**
Giữ constructor cũ, trường mới cho phép `null`, vì trong queue còn tin nhắn kiểu cũ. → [ServiceInvoicePaidEvent.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/ServiceInvoicePaidEvent.java).

**19. Giao dịch phân tán (một nghiệp vụ qua nhiều service) xử lý thế nào?**
Saga kiểu choreography: mỗi bước là giao dịch cục bộ, nối bằng sự kiện. Không rollback chéo service mà dùng hành động bù (hoàn tiền phát
`ServiceInvoiceRefundedEvent`, báo cáo ghi dòng âm). Việc cần nguyên tử tuyệt đối (thu tiền thuốc + trừ kho) thì đặt trong **cùng một service**.
→ File 17.

## E. Chịu lỗi

**20. auth-service sập thì hệ thống ra sao?**
Người đã đăng nhập vẫn dùng được tới khi Access Token hết hạn (Gateway tự kiểm chữ ký). Danh sách lịch hẹn, đợt khám vẫn xem được (snapshot).
Các thao tác cần kiểm tra Bác sĩ (đặt lịch, phân công) báo 503 `SYS_1004`, không đoán bừa. Circuit Breaker mở sau khi lỗi ≥ 50% trong 20 lời gọi để
từ chối ngay, không chờ timeout. → File 07.

**21. Vì sao lỗi 404 không làm mở Circuit Breaker?**
404 là lỗi nghiệp vụ, service kia vẫn khỏe. `FeignFailurePredicate` chỉ tính timeout, mất kết nối, 5xx. Nếu tính 404, vài người nhập sai id sẽ
khóa mạch của mọi người.

**22. Vì sao không dùng Circuit Breaker có sẵn của Spring Cloud OpenFeign?**
Nó chạy lời gọi ở luồng khác và bọc lỗi thành `NoFallbackAvailableException`, làm mất `AppException` nghiệp vụ (404 thành 500) và mất header người
dùng. Nhóm tự bọc `InvocationHandler` của Feign trong `ResilientFeignConfig`: cùng luồng, ném lại lỗi gốc.

**23. Redis sập hoặc mất dữ liệu thì hàng đợi ra sao?**
Thu tiền vẫn thành công (MySQL); listener phát phiếu báo `CLIN_1318` và được thử lại / vào DLQ rồi job đối soát bù. Mất sạch thì `QueueRebuildJob`
dựng lại từ MySQL khi khởi động, `ensure_seq` giữ số phiếu không trùng. MySQL là nguồn sự thật, Redis chỉ là trạng thái nhanh. → File 11.

## F. Đồng thời

**24. Hai khách đặt khung giờ còn 1 chỗ cùng lúc?**
Khóa tên MySQL `GET_LOCK("vetclinic:appt-slot:<ngày>T<giờ>")` trên kết nối riêng, giữ tới sau commit; người sau đếm thấy đã đủ → từ chối. Không
dùng `synchronized` vì chạy nhiều bản service thì khóa trong bộ nhớ vô tác dụng. → File 14.

**25. Hai thu ngân bán hết lô thuốc cùng lúc?**
`UPDATE medicine_stocks SET quantity_on_hand = quantity_on_hand - :qty WHERE ... AND quantity_on_hand >= :qty`: kiểm tra và trừ trong một câu; 0 dòng
= thiếu kho → rollback toàn bộ hóa đơn (EX-15). Thu trùng hóa đơn: khóa dòng hóa đơn + `@Version` (EX-16).

**26. Vì sao hàng đợi dùng Redis Lua thay vì MySQL?**
Cấp số và xếp hàng cần nguyên tử, rất nhanh, nhiều người thao tác cùng lúc. Lua chạy trọn một khối lệnh không bị chen; `INCR` không bao giờ cấp
trùng số; ZSET tự sắp theo score `(2 − priority) × 10^13 + giờ thu tiền`.

## G. Báo cáo

**27. Báo cáo lấy dữ liệu từ 3 service thế nào khi không JOIN được?**
CQRS: reporting có CSDL đọc riêng, chỉ nghe 8 sự kiện và cộng sẵn vào bảng phẳng (`revenue_facts`, `daily_revenue_summaries`...). API báo cáo chỉ đọc
một CSDL, không gọi service nào; truy vấn nặng không làm chậm CSDL khám bệnh. → File 12.

**28. Báo cáo sai thì sửa thế nào?**
`POST /api/v1/reports/rebuild?days=` (1–6 ngày): đọc lại outbox của auth / clinical / billing qua `/internal/outbox/events`, xóa cửa sổ, phát lại bằng
đúng bộ xử lý của listener. Giới hạn 6 ngày vì outbox chỉ giữ 7 ngày.

## H. Câu hỏi "bẫy" và cách trả lời thật thà

| Câu hỏi | Trả lời |
|---|---|
| Hệ thống có Rate Limiting ở Gateway không? | **Không.** Nhóm đã bỏ khỏi phạm vi; đó là hướng phát triển. |
| Chạy nhiều bản (scale) một service được không? | Thiết kế cho phép: khóa nằm ở MySQL / Redis, outbox dùng `SKIP LOCKED`, Gateway chia tải qua Eureka. Hạn chế: notification chống trùng trong bộ nhớ và dùng broker STOMP trong bộ nhớ, nên chạy nhiều bản cần thêm broker ngoài. Đồ án chưa đo tải nhiều bản. |
| Các CSDL có tách máy chủ thật không? | Cùng một container MySQL, 5 CSDL logic; tách được bằng cấu hình. |
| Hàng đợi Redis có chạy được trên Redis Cluster? | Script Lua hiện dựng khóa từ Hash phiếu, đúng cho Redis một nút; dùng Cluster cần sửa script. |
| Có gửi SMS không? | Không; thông báo qua ứng dụng (WebSocket) và email. |

---

## Mẹo khi bảo vệ

1. Luôn trả lời theo khuôn: **vấn đề → giải pháp → chỗ trong code → cách đã kiểm chứng**.
2. Chuẩn bị sẵn 3 màn hình: Workbench (bảng `outbox_events`, `processed_events`), RabbitMQ UI (queue + DLQ), giao diện đang chạy.
3. Demo ấn tượng nhất trong 2 phút: **tắt RabbitMQ → thu tiền vẫn được → bật lại → phiếu xuất hiện**. Nó chứng minh cùng lúc Outbox, sự kiện,
   hàng đợi và tính chịu lỗi.
