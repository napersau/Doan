# CLAUDE.md — Hệ thống Quản lý Phòng khám Thú y (VetClinic, Đồ án tốt nghiệp)

Đồ án gồm Spring Boot microservices, React (Create React App, cổng 3000) và MySQL / RabbitMQ / Redis chạy bằng Docker Compose.
Nhóm: Lưu Minh Hiển (trưởng nhóm, BE), Nguyễn Đức Khởi (BE + DevOps), Vũ Hồng Linh (FE). Hạn nộp GVHD ghi trong tài liệu: 20/11/2026.

> **TRẠNG THÁI (22/11/2026): MÃ NGUỒN ĐÃ Ở v4.0** (Phase 0–8 của `LO_TRINH_SUA_CODE_V4.md`, nguồn duy nhất về tiến độ và về từng quyết định). Tài liệu `docs/` v4.0 (18/11, thiết kế lại theo nhận xét GVHD) là **đích**; mã nguồn đã khớp. Nhánh `develop`, tag `v4.0`. Mốc quay lại: `v3-final` (v3.0 + phiếu xếp hàng MySQL), `v2-baseline`.
> **Kết quả đã kiểm chứng (22/11/2026, từ CSDL sạch nạp bằng `docs/`):** `mvn -o clean install` toàn bộ xanh (common 45, gateway 5, auth 66, appointment 71, clinical 302 + 15 ca live Redis chỉ chạy khi có Redis, billing 48, notification 55, reporting 33); FE 36 test + `npm run build` sạch; `docker compose up -d --build` → **13 container healthy**; `bash e2e/run_all.sh`: backend **97**, notification **31**, giao diện luồng khám **72**, chức năng phụ **216**, quét trang **47**, ngoại lệ phân tán + NFR (`v4_resilience`) **59**, đều đạt.
> **Quy tắc:** docs v4.0 là đích; mã đã khớp. Nếu code và docs lệch nhau về sau, làm theo docs rồi báo người dùng. Mọi chỗ làm KHÁC docs đã được liệt kê ở `DANH_SACH_SUA_TAI_LIEU_V4.md` (đọc trước khi sửa tài liệu hoặc viết báo cáo). **Không sửa `docs/`** trừ khi người dùng yêu cầu (ngoại lệ đã làm: `docs/seed_demo_data.sql` v4.0 và `docs/.dockerignore`).
> Lịch sử: `CLAUDE.v3-backup-2026-11-22.md` (mô tả chi tiết v3.0, kể cả các điều chỉnh 12–17/11), `CLAUDE.v2-backup-2026-10-29.md`, `LO_TRINH_SUA_CODE_V3.md`, `LO_TRINH_SUA_CODE_V4.md`. Lệnh tắt `/v4-next` (`.claude/commands/v4-next.md`) chỉ còn ý nghĩa lịch sử vì lộ trình đã xong.

---

## 1. Nguồn tài liệu chuẩn (thứ tự ưu tiên khi mâu thuẫn)

Tất cả nằm phẳng trong `docs/` (**bỏ qua mọi file có hậu tố " (1)"**: bản v3.0 cũ, dùng để đối chiếu, nên xóa khỏi `docs/` trước khi nộp).

1. `docs/schema_init.sql` (v4.0: **5 CSDL, 52 bảng**, 0 ENUM MySQL, 4 VIEW, dữ liệu danh mục) — DDL thật; Docker nạp trực tiếp file này + `docs/seed_demo_data.sql`.
2. `docs/THIET_KE_CO_SO_DU_LIEU_ERD.md`, `docs/DANH_SACH_KIEU_ENUM_HE_THONG.md` (18 enum Java lưu VARCHAR + 4 bảng danh mục + `LabEvaluation`).
3. `docs/THIET_KE_KIEN_TRUC_MICROSERVICES.md` — danh mục service, 17 sự kiện RabbitMQ, lời gọi Feign, outbox, Redis, reporting.
4. `docs/QUY_CHUAN_THIET_KE_VA_LAP_TRINH_BACKEND_SPRINGBOOT.md` (bắt buộc) — mã lỗi, URI, mẫu giao dịch, chương 9 (Feign / RabbitMQ / Outbox / Redis).
5. `docs/THIET_KE_SO_DO_TRANG_THAI_STATE_MACHINE.md` (SM-01…SM-07, T01…T14, Q1…Q5), `docs/TONG_HOP_NGOAI_LE_VA_XU_LY_HE_THONG.md` (EX-01…EX-20).
6. `docs/DAC_TA_USE_CASE_CORE.md`, `docs/YEU_CAU_CHUC_NANG_VA_PHI_CHUC_NANG.md` (FR, NFR-01…10), `docs/THIET_KE_SO_DO_HOAT_DONG_ACTIVITY.md`, `docs/TAI_LIEU_MO_TA_NGHIEP_VU_PHONG_KHAM_THU_Y.md`.
7. `docs/LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md` — danh sách task + `curl` kiểm thử + ID dữ liệu demo; `docs/KE_HOACH_*.md`, `docs/BANG_TONG_HOP_*.md` chỉ để biết tiến độ / người phụ trách, **không** dùng quyết định kỹ thuật.

---

## 2. Tech stack (đã kiểm chứng)

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| Java | 21 (Temurin) | máy dev: `JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"` (enforcer chặn JDK khác) |
| Spring Boot / Cloud | **4.1.1** / **2025.1.3 (Oakwood)** | không hạ xuống Boot 3; Resilience4j qua `spring-cloud-starter-circuitbreaker-resilience4j` |
| JWT | jjwt 0.12.6 + `jjwt-gson` | gson để không kéo Jackson 2 cạnh Jackson 3 |
| MySQL / RabbitMQ / Redis | 8.0 (1 container, 5 CSDL) / 3.12-management / 7-alpine (`--appendonly yes --appendfsync everysec`) | ảnh CĐHA ở Docker Volume, không MinIO/S3 |
| Lombok + MapStruct | MapStruct 1.6.x, `lombok-mapstruct-binding` 0.2.0 | thứ tự processor: lombok → binding → mapstruct |
| Frontend | React 19 + **Create React App**, react-router 6, CSS thuần, `fetch` (không Axios), STOMP tự viết | `fe/`, cổng 3000; docs ghi Vite / TS / Tailwind / AntD / Axios là lỗi thời (D8) |

**Khác biệt Boot 4 / Spring Cloud 2025.1 so với mã mẫu trong docs:** Jackson 3 (`tools.jackson.databind`; annotation vẫn `com.fasterxml.jackson.annotation`; RabbitMQ dùng `JacksonJsonMessageConverter`, không `Jackson2JsonMessageConverter`); Gateway dùng `spring-cloud-starter-gateway-server-webflux`, property `spring.cloud.gateway.server.webflux.*`; request DTO dùng `Boolean`/`Integer` cho trường tùy chọn (Jackson 3 từ chối thiếu `boolean`/`int`); Spring Framework 7 chỉ nhận `@Controller` / `@RestController` là handler (`@RequestMapping` trơn không đủ).

---

## 3. Kiến trúc và cổng

| Module | Port | CSDL (bảng) | Package gốc |
|---|---|---|---|
| `service-registry` (Eureka, không phụ thuộc common-library) | 8761 | – | `com.vetclinic.registry` |
| `api-gateway` (WebFlux) | 8080 | – | `com.vetclinic.gateway` |
| `auth-service` | 8081 | `pet_clinic_auth_db` (9): `roles`, `users`, `user_roles`, `user_profiles`, `staff_profiles`, `veterinarian_profiles`, `pet_owner_profiles`, `refresh_tokens`, `outbox_events` | `com.vetclinic.auth` |
| `appointment-service` | 8082 | `pet_clinic_appointment_db` (4): `appointments`, `appointment_status_histories`, `outbox_events`, `processed_events` | `com.vetclinic.appointment` |
| `clinical-service` | 8083 | `pet_clinic_clinical_db` (24): danh mục (`species`, `service_categories`, `services`, `service_prices`, `lab_test_items`, `service_lab_test_items`, `lab_test_reference_ranges`), thú cưng (`pets`), đợt khám (`pet_visits`, `vital_signs`, `medical_records`, `pet_visit_status_histories`), chỉ định (`visit_service_details`, `visit_service_detail_histories`, `lab_test_results`, `imaging_results`, `imaging_attachments`), hóa đơn dịch vụ (`service_invoices`), đơn thuốc (`prescriptions`, `prescription_items`), lịch sử phiếu (`queue_ticket_histories`, `queue_ticket_history_items`), hạ tầng (`outbox_events`, `processed_events`) + VIEW `v_service_invoice_amounts`, `v_current_service_prices` | `com.vetclinic.clinical` |
| `billing-pharmacy-service` | 8084 | `pet_clinic_billing_pharmacy_db` (10): `medicine_categories`, `medicines`, `medicine_ingredients`, `medicine_prices`, `medicine_stocks`, `medicine_invoices`, `medicine_invoice_items`, `inventory_transactions`, `outbox_events`, `processed_events` + VIEW `v_medicine_invoice_amounts`, `v_current_medicine_prices` | `com.vetclinic.billing` |
| `notification-service` | 8085 | – (WebSocket STOMP + Mail, chống trùng bằng `ProcessedEvents` trong bộ nhớ, D10; không SMS) | `com.vetclinic.notification` |
| `reporting-service` | 8086 | `pet_clinic_reporting_db` (5, mô hình đọc CQRS): `visit_facts`, `revenue_facts`, `daily_revenue_summaries`, `doctor_daily_performances`, `processed_events` | `com.vetclinic.reporting` |
| `common-library` | – | – | `com.vetclinic.common` |
| Redis | 6379 | phiếu xếp hàng đang chờ / đang phục vụ (không lưu MySQL) | – |

Cấu trúc thư mục: `vetclinic-backend/` (Maven multi-module; một `Dockerfile` dùng chung `--build-arg MODULE=<module>`, biên dịch trong container), `fe/`, `e2e/`, `scripts/`, `docker-compose.yml` + `.dev.yml` + `.db.yml` + `.nomount.yml` + `.env`. Package trong service: `config, controller, service(+impl), repository, entity, dto/request, dto/response, mapper, client, event, constant`.

URI prefix (Gateway, Controller, Feign phải khớp): `/api/v1/auth|users|staff|roles/**` → auth · `/api/v1/appointments/**` · `/api/v1/clinical|pets|species|catalog/**`, `/files/**` → clinical · `/api/v1/billing|pharmacy/**` · `/api/v1/notifications/**`, `/ws/**` · `/api/v1/reports/**` → reporting. `/internal/**`: Gateway **không định tuyến** (chặn từ ngoài), service cho qua không cần header; đường dẫn theo Kiến trúc §5.1 (D5).

**Thành phần dùng chung trong `common-library`:** `ApiResponse`/`PageResponse`, `ErrorCode`/`AppException` + advice, `CommonSecurityConfig` + `HeaderAuthenticationFilter` + `SecurityUtils`, `RoleCode`, `UuidV7`, Feign (`FeignClientInterceptor`, `CustomFeignErrorDecoder`, `FeignFailurePredicate`, `ResilientFeignConfig`), sự kiện (`EventRouting`, record sự kiện, `RabbitCommonConfig.durableQueueWithDlq`), Outbox (`@EnableOutbox`, `OutboxWriter`, `OutboxRelay`, `InternalOutboxController`), `IdempotentConsumer` + `processed_events` (`@EnableIdempotentConsumer`), `NamedLockService`.

### 3.1. Danh tính và bảo mật
- Khóa chính: **UUIDv7 `BINARY(16)`** cho thực thể nghiệp vụ (sinh ở ứng dụng, `UuidV7.generate(clock)`); **`BIGINT AUTO_INCREMENT`** cho bảng con / lịch sử / audit / outbox; **`SMALLINT`** cho danh mục (`roles`, `species`, `service_categories`, `medicine_categories`, `lab_test_items`). Không còn ENUM MySQL (trạng thái = `VARCHAR` + enum Java `@Enumerated(STRING)`).
- Đăng nhập: Access Token 30 phút (HS256, claim `userId` là **chuỗi** UUID, **`roles` là DANH SÁCH** mã vai trò không tiền tố), Refresh Token 7 ngày (UUID, lưu `token_hash`, dùng 1 lần, Token Rotation). Gateway **xóa `X-User-Id`/`X-User-Roles` từ client** rồi gắn lại từ JWT; `HeaderAuthenticationFilter` thêm `ROLE_` đúng một lần. Thiếu / sai / hết hạn token → **401 `SYS_1002` ngay tại Gateway**; sai vai trò → 403. Whitelist công khai: `POST /auth/login|refresh|logout|register`, `GET /appointments/slots`, `/files/**` (URL ký tạm ảnh CĐHA), `/ws/**`, `/actuator/health`.
- Sai mật khẩu luôn `AUTH_1103`; 5 lần liên tiếp → `AUTH_1112` (429) 15 phút. Mật khẩu 8–72 ký tự (kiểm tra theo **byte** trước BCrypt strength 12). **FE phải xếp hàng các lời gọi `/refresh`** (hai refresh đồng thời cùng token bị coi là đánh cắp).
- Người dùng: `users` (tài khoản) tách `user_roles` / `user_profiles` / `staff_profiles` (phòng `room_number` duy nhất) / `veterinarian_profiles` (bắt buộc với VETERINARIAN) / `pet_owner_profiles`. Admin gán 5 vai trò nhân sự; `PET_OWNER` do Lễ tân tạo hoặc tự đăng ký (tên đăng nhập = SĐT). Khóa / đổi vai trò / reset thu hồi mọi Refresh Token.
- **Chỉ publish port 8080** (và 3000 cho giao diện); port hạ tầng chỉ mở ở `docker-compose.dev.yml`. CORS **chỉ ở Gateway**. `@PreAuthorize("hasRole(...)")` trên mọi endpoint nghiệp vụ. `JWT_SECRET` (≥ 32 byte) dùng chung auth + gateway + notification, không commit.
- WebSocket: Gateway route `/ws/**` → `lb:ws://notification-service`; JWT trong frame STOMP `CONNECT` (không `?token=`); `SEND` bị cấm; chỉ `SUBSCRIBE` `/user/queue/notifications` và `/topic/role/<vai trò của mình>` (D7).

---

## 4. Mô hình nghiệp vụ v4.0 (quy tắc bắt buộc)

### 4.1. Đặt lịch (appointment-service)
14 khung 30 phút (sáng 08:00–11:00, chiều 13:30–16:30; cấu hình trong yml). **Sức chứa mỗi khung = số Bác sĩ `ACTIVE` × 2** (hỏi auth-service qua Feign, không fallback: lỗi thì `SYS_1004`). Khách không chọn Bác sĩ; online → `PENDING`, Lễ tân → `CONFIRMED`. Chủ nuôi chỉ hủy / đổi trước giờ hẹn ≥ 2 giờ (`APPT_1204`). `appointments` có `owner_id` + 3 cột snapshot (`pet_name_snapshot`, `owner_name_snapshot`, `owner_phone_snapshot`) cập nhật bất đồng bộ qua `PetUpdatedEvent` / `UserProfileUpdatedEvent`. NO_SHOW: `CONFIRMED` quá `appointment_date + start_time + 30 phút` (cron 5 phút, giờ VN). Mã lịch `APT-YYYYMMDD-XXXXXX` (đuôi ngẫu nhiên, UNIQUE).

### 4.2. Tiếp đón và hàng đợi trên Redis (clinical-service)
- **Thu trước, phiếu xếp hàng** (`service_categories.requires_prepayment`): check-in (T01) lập dịch vụ khám `PENDING` + hóa đơn phí khám; thu ngân thu xong → `ServiceInvoicePaidEvent` (outbox, cùng giao dịch) → **`QueueIssuerListener` phát phiếu DOCTOR** (chỉ phát bất đồng bộ, thường dưới 1 giây; màn hình Thu ngân hiện "Đang cấp số thứ tự…"). Chỉ định XN / CĐHA: nộp tiền xong mới có phiếu phòng (gộp dịch vụ cùng phòng thành một phiếu). Dịch vụ làm tại phòng (tiêm, tẩy giun...) làm ngay, thu khi chốt ca. Cấp cứu: phiếu ngay (`priority 2`), thu sau. Ca đủ kết quả CLS (T06) nhận phiếu DOCTOR `priority 1` không thu tiền.
- **Redis** (Lua nguyên tử, không khóa lạc quan): `queue:{yyyyMMdd}:{DOCTOR|LABORATORY|IMAGING}:{doctorId|UNASSIGNED|ALL}` ZSET với `score = (2 − priority) × 10^13 + epochMillis(paidAt)` (thứ tự theo giờ thu tiền, không tụt hạng khi hệ thống chậm), `ticket:{id}` HASH (TTL 36 giờ), `queue:seq:{ngày}:{loại}`, `queue:dedupe:{khóa}` (EX-19), `queue:visit:{visitId}`, `queue:busy:{doctorId}` (một Bác sĩ một phiếu `IN_SERVICE`, TTL 36 giờ), `queue:meta:{ngày}`. 6 script Lua: `issue_ticket`, `move_ticket`, `call_ticket`, `start_ticket`, `finish_ticket`, `ensure_seq`. Phiếu kết thúc ghi `queue_ticket_histories` (+ `_items`) **trong giao dịch nghiệp vụ**; lệnh Lua kết thúc chạy sau commit. Redis lỗi khi đọc → `CLIN_1318` (503).
- **Bền vững:** `QueueRebuildService` dựng lại phiếu thiếu từ MySQL (ca đã nộp phí khám, ca cấp cứu, `RESULTS_READY`, CLS đã thu); chạy khi khởi động nếu mất `queue:meta` (`QueueRebuildJob`), mỗi 60 giây bù phiếu của sự kiện đã vào DLQ (`QueueReconcileJob`), và nút quản trị `POST /clinical/queue-tickets/rebuild` (ADMIN). Số phiếu không trùng lịch sử: `ensure_seq` nâng bộ đếm theo số lớn nhất trong `queue_ticket_histories`.
- **API hàng đợi:** `POST /clinical/queue-tickets/call-next?queueType=`, `/{id}/call` ("Gọi" chỉ ghi `calledCount` + `PatientCalledEvent`, không đổi trạng thái / thứ tự: khách vắng thì gọi ca khác), `/{id}/start` (phiếu phòng); Bác sĩ bắt đầu bằng `start-exam` / `start-phase2` (cần có phiếu: `CLIN_1314`); `GET /clinical/pet-visits/queue` (bảng điều phối, cần auth-service để liệt kê Bác sĩ `ACTIVE`), `/my-queue`, `GET /clinical/cashier/pending?section=EXAM_FEE|PARACLINIC|FINAL`.
- **Phân công (Q2):** Lễ tân gán ca `WAITING` cho Bác sĩ `ACTIVE` bất kỳ lúc nào (kiểm tra với auth-service TẠI THỜI ĐIỂM GHI, `CLIN_1316`); phiếu đi theo, giữ số và thứ tự. Danh sách đợt khám / hàng đợi **chỉ dùng cột snapshot** (không gọi auth: NFR-10); số phòng và `doctorActive` chỉ có ở chi tiết một ca.
- **EX-17:** auth ghi `UserStatusChangedEvent` (outbox) khi khóa / mở / ngừng; clinical gỡ `doctor_id` ca `WAITING` và chuyển phiếu sang `UNASSIGNED` (giữ số và thứ tự); ca `IN_PROGRESS` không bị ngắt; `RESULTS_READY` luôn thuộc Bác sĩ khám chính, Admin xử lý tay.

### 4.3. State machine SM-01 `pet_visits` (7 trạng thái, T01…T14)
`WAITING → IN_PROGRESS (GĐ1) → WAITING_RESULTS | WAITING_PAYMENT`; `WAITING_RESULTS → RESULTS_READY → IN_PROGRESS (GĐ2) → WAITING_PAYMENT → COMPLETED`; hủy: T03 (Lễ tân, `WAITING`), T11 (Bác sĩ, GĐ1, chỉ khi chưa có dịch vụ tại phòng `COMPLETED`); T08 / T12 khách bỏ về → `COMPLETED` + công nợ (EX-11); T14 mở lại hồ sơ (Bác sĩ phụ trách hoặc ADMIN, lý do bắt buộc). Mỗi lần đổi trạng thái ghi `pet_visit_status_histories` (`changed_by_user_id` cho phép NULL = hệ thống) cùng giao dịch; hồ sơ từ `WAITING_PAYMENT` trở đi bị khóa. `pet_visits` tách `vital_signs` (sinh hiệu, bắt buộc `weight`, `temperature` trước khi chỉ định CLS: `CLIN_1308`) và `medical_records` (chẩn đoán xác định bắt buộc để chốt: `CLIN_1307`). T10: khi mọi `service_invoices` của ca là `PAID`/`CANCELLED`/`REFUNDED` thì `COMPLETED` **trong cùng giao dịch** thu tiền (thu ngân xác nhận với `invoiceIds: []` nếu đã thu trước hết). Race `WAITING_RESULTS → RESULTS_READY`: khóa dòng `pet_visits` (`findByIdForUpdate`, `READ_COMMITTED`) **trước** rồi mới đếm dịch vụ CLS còn dở.

### 4.4. Dịch vụ, hóa đơn dịch vụ, đơn thuốc, hóa đơn thuốc
- `services` + **`service_prices`** (lịch sử giá có hiệu lực; đổi giá = đóng dòng cũ + thêm dòng mới, `BILL_1410` nếu hiệu lực không sau dòng đang mở). Hóa đơn dịch vụ **chụp** `unit_price`, `vat_rate` lúc chỉ định (EX-20: đổi giá không ảnh hưởng ca đang mở). Mỗi dòng `visit_service_details` có đúng 1 `service_invoices` (`UNIQUE`, cùng giao dịch); số tiền **không lưu** (VIEW / `InvoiceAmounts`, `BigDecimal`, làm tròn HALF_UP 2 chữ số). Hủy dịch vụ: chưa thu → `CANCELLED`; đã thu + phòng khám không làm được (`reasonType=CLINIC`) → **`REFUNDED`** + `ServiceInvoiceRefundedEvent`; khách tự hủy (`CUSTOMER`) → giữ `PAID`; phí khám không hoàn (`CLIN_1310`). Giảm giá trên từng hóa đơn (`BILL_1402`). Không lưu phương thức thanh toán.
- Trạng thái con của dịch vụ CLS **suy ra từ `action` mới nhất** của `visit_service_detail_histories` (enum `ServiceDetailAction`: `ORDERED, START_PROCESSING, SAMPLE_COLLECTED, COMPLETED, CANCELLED`). Lab: nhập đủ chỉ số của gói, `LabEvaluation` (`LOW`/`NORMAL`/`HIGH`) tính khi hiển thị. CĐHA: một request multipart, tệp lưu theo `file_key` tương đối (URL ký tạm sinh lúc trả về, `app.storage.*`), chỉ `image/jpeg`, `image/png`, `application/dicom`, kiểm tra magic-byte.
- **Đơn thuốc ở clinical** (`PUT /pet-visits/{id}/prescription`, chỉ định chuyên môn, không giá, 1 đơn / đợt khám, ≥ 1 thuốc; kê vượt tồn chỉ cảnh báo). **Chốt ca → `PrescriptionCreatedEvent` (outbox) → billing tự tạo hóa đơn thuốc NHÁP `PENDING`** (idempotent, `medicine_invoices.prescription_id UNIQUE`; EX-19). Lễ tân sửa dòng / hủy / thu ở `GET|PUT|POST /billing/medicine-invoices...`; **giá thuốc làm mới theo bảng giá hiện hành lúc xác nhận thu** (EX-20); **thu tiền + trừ kho + nhật ký kho** là MỘT `@Transactional` (thiếu kho → rollback, `PHARM_1406`, EX-15; trùng → 409, EX-16). Trừ kho chỉ bằng `UPDATE medicine_stocks SET quantity_on_hand = quantity_on_hand - :qty WHERE medicine_id = :id AND quantity_on_hand >= :qty`; không dùng `clearAutomatically`; bất biến `quantity_on_hand = SUM(inventory_transactions.quantity)`. Thuốc: `medicines` (thông tin) / `medicine_ingredients` / `medicine_prices` / `medicine_stocks`.

### 4.5. Báo cáo (reporting-service, CQRS)
Chỉ NHẬN sự kiện (8 listener: `VisitCheckedIn`, `VisitCompleted`, `VisitCancelled`, `ServiceInvoicePaid`, `ServiceInvoiceRefunded` → dòng REFUND là bản sao ĐỔI DẤU của dòng SALE, `MedicineInvoicePaid`, `UserProfileUpdated`, `PetUpdated`), mỗi listener một queue + DLQ, ghi `processed_events` + dòng fact + bảng tổng hợp (`INSERT ... ON DUPLICATE KEY UPDATE`) **cùng một giao dịch**. `business_date` theo giờ VN. API (chỉ `ADMIN`): `GET /api/v1/reports/revenue/daily|revenue/by-category|doctors/performance|visits/summary` (đọc MỘT CSDL, không gọi service khác); doanh thu đã trừ hoàn tiền. Báo cáo lịch hẹn (tỷ lệ NO_SHOW) vẫn ở appointment-service (`GET /appointments/reports/summary`). **Dựng lại:** `POST /api/v1/reports/rebuild?days=` (ADMIN, 1–6 ngày, mặc định 3): đọc `outbox_events` của auth / clinical / billing qua `GET /internal/outbox/events`, xóa cửa sổ từ ngày đầu, phát lại bằng đúng bộ xử lý của listener (KHÔNG gửi lại RabbitMQ); dữ liệu cũ hơn cửa sổ giữ nguyên; service nguồn lỗi thì dừng trước khi xóa.

### 4.6. Ngoại lệ EX-01…EX-20 (nơi xử lý)
EX-01 NO_SHOW tự quét · EX-02 vãng lai · EX-03 bỏ về khi chờ · EX-04 cấp cứu (đầu mọi hàng đợi) · EX-05..07 gọi ca khác / gọi lại · EX-08 hủy một dịch vụ CLS · EX-09 huy hiệu liên phòng · EX-10 bác sĩ gọi mà khách vắng · EX-11 bỏ về hẳn → công nợ · EX-12 chốt thiếu thông tin · EX-13 kê vượt tồn · EX-14 giảm giá vượt hóa đơn · EX-15 thiếu kho · EX-16 bán / thu trùng · **EX-17** Bác sĩ bị khóa còn ca chờ · **EX-18** RabbitMQ / Redis gián đoạn sau khi thu tiền · **EX-19** sự kiện nhận hai lần · **EX-20** đổi giá khi ca đang mở. Thông báo chủ nuôi chỉ qua ứng dụng / Portal và email, không SMS. Chưa làm (ngoài phạm vi): SMS, quên mật khẩu tự phục vụ, hoàn tiền hóa đơn thuốc, ca kíp / nghỉ phép Bác sĩ, màn hình sảnh công cộng, Rate Limiting ở Gateway (xem mục 9).

---

## 5. Sự kiện RabbitMQ, Outbox và Feign (nguồn chuẩn: Kiến trúc §5)

Exchange topic `clinic.events`; mỗi consumer có queue riêng `<service>.<routing-key>` bền vững + DLQ `<queue>.dlq`; retry 3 lần (1 s, 2 s, 4 s) rồi DLQ. Payload là record trong `common-library` (`eventId` UUIDv7, `occurredAt`, `actorUserId`).

| Routing key | Producer → Consumer |
|---|---|
| `appointment.booked` / `.status_changed` / `.no_show` | appointment → notification |
| `visit.checked_in` | clinical → appointment (CHECKED_IN), notification, reporting, clinical (phát phiếu cấp cứu) |
| `invoice.service_paid` | clinical → clinical (`QueueIssuerListener`), reporting, notification |
| `invoice.service_refunded`, `visit.cancelled` | clinical → reporting, notification |
| `visit.results_ready` | clinical → clinical (phiếu `priority 1`), notification |
| `visit.completed` | clinical → reporting, notification |
| `prescription.created` | clinical → billing (hóa đơn nháp) |
| `pharmacy.medicine_invoice_paid` | billing → reporting, notification |
| `pharmacy.low_stock` | billing → notification |
| `user.profile_updated`, `user.status_changed` | auth → appointment, clinical, reporting, notification |
| `pet.updated` | clinical → appointment, reporting |
| `queue.ticket_issued`, `visit.patient_called` | clinical → notification (gửi thẳng, **không qua outbox**, D9) |

- **Phát sự kiện:** trong method `@Transactional` gọi `outboxWriter.write("<AGGREGATE>", uuid, new XxxEvent(UuidV7.generate(clock), ...))` (`@EnableOutbox` trên class Application; `aggregate_id` là `UUID`); **không gọi `rabbitTemplate` trực tiếp**. `OutboxRelay` mỗi 500 ms lấy lô `PENDING` (`FOR UPDATE SKIP LOCKED`), gửi, chờ publisher confirm, đánh dấu `PUBLISHED`; lỗi → lùi dần, `FAILED` sau 10 lần; xóa `PUBLISHED` > 7 ngày. **Relay chạy `READ_COMMITTED`** (ở REPEATABLE READ khóa khoảng của lô đang gửi chặn các giao dịch nghiệp vụ ghi dòng outbox mới khi RabbitMQ treo; có test hồi quy `OutboxMySqlIT`). RabbitMQ `connection-timeout: 5000ms`.
- **Nhận sự kiện:** `idempotentConsumer.run(event.eventId(), "<service>.<tên-listener>", () -> ...)`: dòng `processed_events` + thay đổi nghiệp vụ cùng giao dịch; trùng → bỏ qua. Thêm sự kiện mới: cập nhật `EventRouting`, record, bảng ở mục này, Kiến trúc §5.2 và Quy chuẩn §9.2. Thêm trường vào sự kiện đang chạy phải **giữ constructor cũ** và để trường mới có thể `null` (tin nhắn cũ vẫn trong queue).
- **Feign — chỉ ĐỌC, gọi `/internal/**`, ngoài `@Transactional`, timeout 2 s (connect + read), Circuit Breaker Resilience4j** (cửa sổ 20, ngưỡng 50%, mở 10 s, tối thiểu 10 lời gọi; `resilience4j.circuitbreaker.configs.default` trong yml của appointment / clinical / notification). **Không dùng `spring.cloud.openfeign.circuitbreaker.enabled`**: bộ Spring Cloud chạy lời gọi ở luồng khác và bọc lỗi (`ExecutionException` → `NoFallbackAvailableException`) làm nơi gọi mất `AppException` nghiệp vụ (404 thành 500 / 503); `ResilientFeignConfig` bọc `InvocationHandler` của Feign bằng `CircuitBreakerRegistry` (cùng luồng, ném lại ngoại lệ gốc). `FeignFailurePredicate`: chỉ timeout / mất kết nối / 5xx tính thất bại, 4xx nghiệp vụ không. Mạch mở → `CircuitBreakerExceptionHandler` trả 503 `SYS_1004`. Nơi chỉ làm giàu hiển thị bắt `RuntimeException` và dùng snapshot; nơi kiểm tra điều kiện ghi (Bác sĩ còn `ACTIVE`, thuốc tồn tại, sức chứa khung giờ) không có fallback.
  Lời gọi hiện có: appointment→auth (đếm Bác sĩ `ACTIVE`, người dùng), appointment→clinical (`/internal/pets/{id}`), clinical→auth (Bác sĩ `ACTIVE`, tên, kiểm tra ghi), clinical→appointment (lịch `CONFIRMED`), clinical→billing (`/internal/medicines`, `/internal/medicine-invoices/status`), notification→auth / clinical (làm giàu, email), reporting→3 endpoint outbox (chỉ job dựng lại, read-timeout 15 s, không Circuit Breaker). **Không service nào ghi sang service khác qua Feign.**

---

## 6. Quyết định kỹ thuật còn hiệu lực (đọc trước khi code)

1. **`common-library` không được làm sập Gateway / Eureka:** bean servlet (`GlobalExceptionHandler`, `HeaderAuthenticationFilter`, `CommonSecurityConfig`, `FeignExceptionHandler`, `CircuitBreakerExceptionHandler`) gắn `@ConditionalOnWebApplication(SERVLET)` / `@ConditionalOnClass`; dependency web / security / feign / resilience4j trong `common-library/pom.xml` là `optional`. Mọi service quét `scanBasePackages = "com.vetclinic"`, nên **mọi `@Component` trong common-library tới được service không có JPA / Feign**: lớp tham chiếu kiểu Spring Data phải có `@ConditionalOnClass` (đã làm với `InternalOutboxController`). `HeaderAuthenticationFilter` **không** là `@Component` (nếu không sẽ chạy hai lần).
2. **Thời gian:** JVM mọi service có CSDL chạy **UTC** (`TZ=UTC`, `-Duser.timezone=UTC`, `TimeZones.forceUtcDefault()`, JDBC `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`, `hibernate.jdbc.time_zone: UTC`); giờ phòng khám luôn **tường minh** bằng bean `Clock` (`Asia/Ho_Chi_Minh`) và `@Scheduled(..., zone = "Asia/Ho_Chi_Minh")`; không `LocalDate.now()` trần. `Instant` cho mọi mốc giao dịch; ngày nghiệp vụ báo cáo theo giờ VN.
3. **Concurrency:** UNIQUE ở CSDL + `@Version` (`PetVisit`, `ServiceInvoice`, `MedicineInvoice`) + khóa dòng; Redis Lua cho hàng đợi; `NamedLockService` (`GET_LOCK` MySQL trên kết nối riêng, phương thức gọi khóa không `@Transactional`) cho kiểm tra-rồi-ghi không có `version` (khung giờ đặt lịch, một thú cưng / ngày). Chuyển trạng thái trên bảng không có `version`: `findByIdForUpdate`. Lỗi khóa dòng / deadlock → 409 `SYS_1005`. `@Transactional(noRollbackFor = AppException.class)` + UPDATE nguyên tử cho chỗ "chặn lỗi nhưng vẫn phải ghi" (thu hồi Refresh Token khi phát hiện đánh cắp). Mã sinh tự động (`APT-`, `VIS-`, `PET-`, `SINV-`, `MINV-`) dựa UNIQUE + đuôi ngẫu nhiên, không `count + 1`.
4. **Hibernate:** `ddl-auto: validate`, `open-in-view: false`; UUID `BINARY(16)` (`@JdbcTypeCode(SqlTypes.BINARY)` / `columnDefinition = "binary(16)"`); cột `TINYINT`/`SMALLINT` cần `@JdbcTypeCode(SqlTypes.TINYINT/SMALLINT)`; thay danh sách con theo kiểu so sánh (diff) chứ không xóa-rồi-thêm (UNIQUE); truy vấn native với UUID dùng `byte[]` 16 byte hoặc `UNHEX/UUID_TO_BIN`.
5. **Testing:** unit test với mock không bắt được lỗi MySQL thật / đua tranh → dùng các script chạy jar với MySQL + RabbitMQ + Redis thật (mục 8). Mockito strict stubs: `lenient()` cho stub dùng chung. Test báo `ClassFormatError: Invalid method Code length 0` / "could not instrument" → `.class` trong `target/` hỏng (IDE tự build): `mvn clean install` lại. **`mvn install` không đóng gói lại jar service khi chỉ `common-library` đổi** → dùng `mvn -o -pl <module> clean install` trước khi chạy script kiểm tra (Docker biên dịch từ nguồn nên không bị).
6. **Docker Compose:** Gateway khởi động **sau cùng** (`depends_on` mọi service `healthy`); Eureka tắt `use-read-only-response-cache`; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, `mem_limit` 384–512m mỗi service; healthcheck `/actuator/health` (Redis: `management.health.redis.enabled: false` ở clinical). `schema_init.sql` không idempotent: chỉ nạp khi volume trống → sửa schema thì `docker compose down -v`. MySQL healthcheck chờ bảng `pets` có ≥ 2 dòng (seed đã nạp xong).
7. **Lỗi trong code mẫu của docs (đừng chép nguyên):** `startExamination` gọi Feign trong `@Transactional`; ném `AppException` trong `@Transactional` rollback cả lệnh phải giữ; Spring Security 7 từ chối BCrypt > 72 byte bằng exception; claim `userId` luôn ký dạng chuỗi (jjwt-gson parse số thành `5.0`); `confirmServicePayment` mẫu cần so sánh enum qua tham số JPQL và kiểm tra đủ `invoiceIds`.

---

## 7. Quy chuẩn code bắt buộc (tóm tắt Quy chuẩn v4.0)

- Không trả Entity ra API; Request DTO luôn `@Valid`; MapStruct `componentModel = "spring"`. Phản hồi luôn `ApiResponse<T>` (`status, errorCode, message, data, timestamp`), phân trang `PageResponse<T>`; tạo mới trả 201; lỗi nghiệp vụ `throw new AppException(ErrorCode.X[, message])`. Mã lỗi: `ErrorCode` (Quy chuẩn §5.1); mã mới thì thêm cuối nhóm, không đánh số lại.
- Tiền `BigDecimal` (cấm `double/float`); **không lưu tổng tiền / thành tiền / thuế / tồn sau giao dịch / nhãn đánh giá XN** (VIEW hoặc code). Enum `@Enumerated(EnumType.STRING)`, 18 enum khớp `DANH_SACH_KIEU_ENUM_HE_THONG.md` (`scripts/enum_audit.py` kiểm tra). Request DTO: `Boolean`/`Integer` cho trường tùy chọn.
- Chuyển trạng thái dùng `POST /{id}/<action>`, không `PUT` (ngoại lệ: `PUT .../clinical-info`, `PUT .../prescription`, `PUT .../medicine-invoices/{id}/items`). Mỗi lần đổi trạng thái ghi bảng history **trong cùng transaction**.
- Mọi nghiệp vụ tiền / kho nằm trọn trong một service và một `@Transactional(rollbackFor = Exception.class)`. `@PreAuthorize` trên mọi endpoint nghiệp vụ. Không xóa cứng master đã có giao dịch (`services`, `medicines`, `species`): `is_active = false`.
- Ngôn ngữ: message lỗi và UI tiếng Việt có dấu; tên code, commit và log kỹ thuật tiếng Anh.

---

## 8. Lệnh chạy và kiểm thử

Lần đầu: `cp .env.example .env`, điền `JWT_SECRET` (`openssl rand -hex 32`); `.env` không commit. Tài khoản seed (mật khẩu chung **`Admin@123`**): `admin`, `bs_tien` (P101), `bs_an` (P102), `bs_mai` (P103), `letan_hoa`, `ktv_lab_minh` (P201), `ktv_cdha_dung` (P202), chủ nuôi `khach_lan` (thú cưng Lu, Milo), `khach_nam`, `khach_huong`, `khach_duc`, `khach_thao`, `khach_binh`; id cố định theo D11 (`docs/seed_demo_data.sql`).

```bash
docker compose up -d --build        # toàn bộ hệ thống (13 container), lần đầu ~10 phút; chỉ public :8080 (API) và :3000 (giao diện)
docker compose ps                   # mọi container phải "healthy"
docker compose down -v              # RESET CSDL (bắt buộc sau khi sửa docs/schema_init.sql / docs/seed_demo_data.sql)
# Docker Desktop không bind-mount được thư mục: thêm  -f docker-compose.nomount.yml  (image MySQL dựng sẵn từ docs/)
# Máy đã có MySQL chiếm 3306: docker-compose.db.yml mở MySQL ở 3307; dev: -f docker-compose.dev.yml (mở 3306/5672/15672/8761/8025)

export JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"
cd vetclinic-backend && mvn -o clean install                  # unit test toàn bộ (lần đầu cần mạng để tải Resilience4j / Redis starter)
mvn -o -pl <module> clean install                              # module sửa (kèm common-library đứng trước nếu đổi common)
cd fe && npm install && npm start                              # cổng 3000 (fe/.env: REACT_APP_API_URL=http://localhost:8080)
cd fe && CI=true npm test && npm run build                     # 36 test; build phải "Compiled successfully", không cảnh báo lint
```

**Kiểm thử chạy thật** (cần Docker, JDK 21, jar mới bằng `clean install`):
- `bash scripts/service_check.sh <module> [--reset]` — chạy jar với MySQL tạm (`vetclinic-check-mysql`, cổng 3399, nạp `docs/`), Hibernate `validate` khớp lược đồ; kèm `auth_staff_check` (auth), `catalog_check` (clinical), `medicine_check` (billing).
- Script cần nhiều jar + RabbitMQ / Redis tạm (tự dựng container): `pet_appointment_check`, `user_event_check`, `ex17_check`, `visit_check`, `imaging_check`, `prescription_check`, `queue_check`, `queue_rebuild_check`, `redis_queue_check`, `reporting_check`, `feign_resilience_check`; `python scripts/enum_audit.py` (enum / khóa / danh mục khớp docs).
- `bash e2e/run_all.sh` (sau `down -v && up -d --build`, mọi container healthy; CSDL seed sạch): 6 bộ `backend_v4`, `notification_v4`, `fe_smoke_v4` (Chrome headless), `features_v4`, `fe_pages_v4`, `v4_resilience` (CUỐI CÙNG: dừng / bật lại RabbitMQ, Redis, auth, appointment, clinical; ~10 phút). Chi tiết `e2e/README.md`.

Gateway http://localhost:8080 · (file dev) RabbitMQ UI :15672, Eureka :8761, Mailpit :8025. Dev trên máy có `mysqld` chiếm 3306: `MYSQL_HOST_PORT=3307 ...`, chạy service ngoài Docker với `DB_PORT=3307` (thiếu là nối nhầm MySQL máy host).

---

## 9. Việc còn mở và hạn chế đã biết (báo người dùng khi đụng tới)

- **Rate Limiting ở Gateway (D6):** người dùng chọn (22/11/2026) **xóa khỏi tài liệu**, không làm trong code: bỏ cụm "Rate Limiting" ở `docs/THIET_KE_KIEN_TRUC_MICROSERVICES.md` dòng 86 (bảng service `api-gateway`) và "Rate Limit" ở `docs/BANG_TONG_HOP_LICH_TRINH_TOAN_KHOA.md` dòng 50 (Tuần 12, Khởi); đừng ghi như tính năng đã có trong báo cáo.
- **Việc sửa tài liệu cần người dùng làm** (chi tiết và vị trí ở `DANH_SACH_SUA_TAI_LIEU_V4.md`): file "Giải trình thay đổi thiết kế v4.0" ghi EX-17 là "cảnh báo Lễ tân chuyển phiếu" trong khi EX-17 / SM-07 / Kiến trúc §5.2 ghi clinical **tự chuyển** (D4, code làm theo bản sau); xóa ba bản " (1)"; Quy chuẩn 9.1 mô tả Circuit Breaker bằng `spring.cloud.openfeign.circuitbreaker.enabled` (code dùng `ResilientFeignConfig`); Task 4.5.4 "xóa các bảng reporting" (code dựng lại theo cửa sổ ≤ 6 ngày, giữ dữ liệu cũ hơn); FE Vite / TS / Tailwind / Axios (D8); kênh WebSocket (D7); cột "Tình trạng" trong Bảng tổng hợp / Kế hoạch.
- **Hạn chế đã biết:** hoàn tiền hóa đơn thuốc ngoài phạm vi; báo cáo chỉ phản ánh sự kiện từ lúc reporting chạy (dựng lại được ≤ 6 ngày gần nhất, outbox giữ 7 ngày); khóa Redis chỉ theo ngày hôm nay (phiếu phát sát nửa đêm nằm ở khóa ngày cũ); Lua script dựng khóa hàng đợi từ Hash phiếu (đúng cho Redis một nút, đổi nếu dùng Redis Cluster); Redis sập quá ~3 giây thì sự kiện vào DLQ và `QueueReconcileJob` (60 giây) bù phiếu; bảng điều phối của Lễ tân (`/pet-visits/queue`) và lịch sử bệnh án vẫn cần auth-service (liệt kê Bác sĩ `ACTIVE`, nhãn "Đã khóa"), các danh sách đợt khám / lịch hẹn thì không; `service_categories.queue_type` và `visit_facts.current_status` lưu chuỗi (không enum Java); `fe/legacy-mock` (bản mock cũ, không build) chưa xóa; chưa có màn hình sảnh `/topic/lobby`.

---

## 10. Cách làm việc trong repo

- Trước khi code một use case: đọc mục UC + State Machine + EX tương ứng trong docs v4.0 và Quy chuẩn chương liên quan; làm theo đúng tên bảng / cột / API / mã lỗi / routing key trong docs. Mâu thuẫn mới: dừng, ghi vào `LO_TRINH_SUA_CODE_V4.md` mục 4 và báo người dùng.
- Sửa schema / enum: cập nhật `docs/schema_init.sql`, `THIET_KE_CO_SO_DU_LIEU_ERD.md`, `DANH_SACH_KIEU_ENUM_HE_THONG.md` cùng lúc, rồi `down -v`, `service_check.sh` các service có DB.
- Git: nhánh `develop` (mỗi bước lộ trình = 1 commit `v4 <bước>: ...`, `git push origin develop`); `main` chỉ cập nhật khi người dùng yêu cầu; **không commit `update.md`**; kiểm tra kết quả `git add` (một pathspec sai làm lệnh thất bại âm thầm và commit chỉ có phần đã stage: đã gặp ở bước 8.2); commit kết thúc bằng dòng `Co-Authored-By` theo hướng dẫn của phiên làm việc.
- Test tối thiểu cho luồng dễ lỗi (đã có trong `e2e/` và `scripts/`): EX-15 / EX-16 (thiếu kho, thu trùng đồng thời), khung giờ hết chỗ khi đặt đồng thời, `RESULTS_READY` khi hủy một trong hai dịch vụ CLS và khi hai KTV hoàn tất cùng lúc, refresh token dùng lại, gọi ca bất kỳ, thu hóa đơn cuối → `COMPLETED`, công nợ, EX-17…EX-20, NFR-09 / 10.
- Báo cáo trung thực: kết quả test phải nói rõ đạt / lỗi; bước nào mới kiểm tra cấp service thì nói rõ chưa chạy Docker + e2e.
