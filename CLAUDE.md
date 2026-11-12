# CLAUDE.md — Hệ thống Quản lý Phòng khám Thú y (VetClinic, Đồ án tốt nghiệp)

Đồ án gồm Spring Boot microservices, React (Create React App, cổng 3000) và MySQL/RabbitMQ chạy bằng Docker Compose. Hạn nộp GVHD: **20/11/2026**.
Nhóm: Lưu Minh Hiển (trưởng nhóm, BE), Nguyễn Đức Khởi (BE + DevOps), Vũ Hồng Linh (FE).

> **ĐỌC TRƯỚC (cập nhật 11/11/2026).** Bộ tài liệu `docs/` đã được nhóm viết lại thành **phiên bản 3.0** (theo góp ý của GVHD, rà soát 10–11/11) và thay đổi lớn so với thiết kế cũ: **25 bảng**, hóa đơn tách theo từng dịch vụ, mua thuốc là tùy chọn, **mỗi bác sĩ có hàng đợi riêng**, bỏ "Khóa ưu tiên", đổi danh mục sự kiện RabbitMQ.
> **TIẾN ĐỘ DI CHUYỂN v2 → v3.0: xem mục "▶ TRẠNG THÁI HIỆN TẠI" ở đầu `LO_TRINH_SUA_CODE_V3.md` (nguồn duy nhất, được cập nhật sau mỗi bước).** Tính đến 12/11/2026: **Phase 0 ✅ đã xong** (git + schema/seed + hạ tầng kiểm chứng + baseline 288 test); **Phase 1 ✅ đã xong** (`common-library`: `ErrorCode` đã đánh số lại theo Quy chuẩn §5.1; đã THÊM 7 event + 9 routing key v3.0, event/routing key v2 còn tạm giữ tới bước 7.1); **Phase 2 ✅ đã xong** (`appointment-service`: 9 cột, chủ nuôi suy ra từ thú cưng, sức chứa N×2, NO_SHOW = bắt đầu + 30', `/slots` công khai ở service, phát song song event cũ + event v3.0); **Phase 3 ✅ đã xong 12/11/2026** (`clinical-service` chạy đúng schema v3.0: hóa đơn riêng từng dịch vụ, hàng đợi riêng từng bác sĩ + Lab/CĐHA với Gọi số/Đẩy lùi Q3–Q5, T01–T14, thu tiền T10 + công nợ + báo cáo doanh thu dịch vụ, `/internal/pet-visits/{id}/summary`; 183 unit test + đã chạy kiểm chứng thật trên MySQL). Còn tạm giữ (không còn service nào phát hay nghe, xóa ở 7.1): event `DoctorPriorityCallbackEvent` (phát song song `ParaclinicalResultsReadyEvent`), `/internal/clinical/visits/{id}`, bí danh `/service-details/{id}/lab-results` và `/imaging-result` (xóa ở bước 7.1). **Phase 4 ✅ đã xong 12/11/2026** (`billing-pharmacy-service` chạy đúng schema v3.0: đơn thuốc không giá/không trạng thái + `stockWarnings`, hóa đơn thuốc `MINV-` lập/sửa/hủy/thu tiền, trừ kho nguyên tử + nhật ký `EXPORT_SALE` có dấu, `POST /pharmacy/inventory/import|adjust`, báo cáo doanh thu thuốc; 56 unit test + đã chạy kiểm chứng thật trên MySQL, kể cả thu tiền song song); **Phase 5 ✅ đã xong 12/11/2026** (`notification-service` nghe đúng 9 sự kiện v3.0, `POST /notifications/{id}/read`, bỏ `send-email`; appointment/clinical đã ngừng phát event v2 trùng; 45 unit test + chạy kiểm chứng thật với RabbitMQ/Mailpit); **Phase 6 (Gateway + Frontend) là bước kế tiếp**; Phase 6–7 chưa làm. Lưu ý: sau Khối A chưa có đường vào Giai đoạn 2 (chờ 3.B3 + 3.B10) và `DoctorStatus.BUSY` còn điều kiện v2 (chờ 3.B2). Các đoạn bên dưới mô tả code là "v2" vẫn đúng cho tới khi phase tương ứng được tích `✅` trong file lộ trình — đừng coi một phần là đã sửa nếu file đó chưa ghi như vậy. Code đã sửa nằm ở nhánh `develop` (mốc quay lại: tag `v2-baseline`).
> **Mã nguồn trong repo (backend, `fe/`, `e2e/`) vẫn là thiết kế v2 của ngày 29/10** và **chưa khớp** `docs/schema_init.sql` mới: chạy `docker compose up` ngay bây giờ sẽ làm các service lỗi `ddl-auto: validate`. Kế hoạch (`KE_HOACH_...`, Tuần 12) ghi rõ "điều chỉnh mã nguồn tương ứng" với v3.0.
> **Quy tắc:** khi làm việc, **docs v3.0 là đích**; mã nguồn hiện có chỉ là nền để sửa. Khi code và docs khác nhau, làm theo docs rồi báo cho người dùng. Khi docs mâu thuẫn nội bộ hoặc sẽ làm hệ thống chạy sai, làm theo mục §10 rồi báo lại. Phần "đang có trong code" ở §9 cho biết chính xác chỗ nào phải sửa.
> Bản CLAUDE.md cũ (mô tả chi tiết thiết kế v2, hữu ích khi di chuyển code) được giữ ở `CLAUDE.v2-backup-2026-10-29.md`; không phải nguồn chuẩn nữa.
> **Đã đối chiếu lại toàn bộ `docs/` (11/11/2026, tối).** Cả 14 tài liệu `.md`/`.sql` đều ghi "phiên bản 3.0"; nội dung trong CLAUDE.md này khớp với: `schema_init.sql` (25 bảng, 25 FK nội bộ, 2 VIEW), Kiến trúc §5 (9 sự kiện, 5 lời gọi Feign), Enum (10 + `LabEvaluation`), Quy chuẩn §3.2/§5.1 (39 mã lỗi), State Machine (T01–T14, Q1–Q5), FR-01…70 / NFR-01…08, UC-01…39 / BR-01…34, Lộ trình (Task 1.2.3 → 5.5). Không phát hiện thay đổi thiết kế nào chưa được ghi ở đây; chỉ bổ sung các điểm lệch nhỏ ở §10 #14–#16 và §11. Khi docs đổi tiếp, đối chiếu lại các mục trên rồi sửa file này.
> **Lịch còn lại (Bảng tổng hợp):** Tuần 12 (09–15/11) đang làm: Hiển viết Chương 4–5 báo cáo; Khởi chuẩn bị `seed_demo_data.sql` (~100 dòng) + ổn định Docker + tích hợp Gateway; Linh kiểm thử responsive FE + chụp ảnh cho Chương 4. Tuần 13 (16–20/11): diễn tập bảo vệ (Hiển 7' / Khởi 5' / Linh 3'), video demo dự phòng 5–7', slide 25–30 trang; **20/11 nộp báo cáo Word/PDF + slide + video**. Việc "điều chỉnh mã nguồn theo v3.0" nằm trong Tuần 12 nhưng **chưa thực hiện** (xem §9).

---

## 1. Nguồn tài liệu chuẩn (thứ tự ưu tiên khi mâu thuẫn)

Tất cả nằm phẳng trong `docs/` (thư mục `docs/Docs/*.docx` cũ đã bị xóa). Ba file có hậu tố " (1)" trong tên: `THIET_KE_KIEN_TRUC_MICROSERVICES (1).md`, `BANG_TONG_HOP_LICH_TRINH_TOAN_KHOA (1).md`, `THIET_KE_CO_SO_DU_LIEU_ERD (1).docx` (bản Word của ERD, **đã là v3.0**, dùng để đóng quyển báo cáo).

1. `docs/schema_init.sql` (v3.0: **25 bảng**, 2 VIEW) — DDL thật
2. `docs/THIET_KE_CO_SO_DU_LIEU_ERD.md`, `docs/DANH_SACH_KIEU_ENUM_HE_THONG.md` (10 enum lưu CSDL + `LabEvaluation` chỉ ở tầng ứng dụng)
3. `docs/THIET_KE_KIEN_TRUC_MICROSERVICES (1).md` — **nguồn chuẩn cho danh mục service, sự kiện RabbitMQ, lời gọi Feign**
4. `docs/QUY_CHUAN_THIET_KE_VA_LAP_TRINH_BACKEND_SPRINGBOOT.md` (v3.0, bắt buộc) — mã lỗi, URI, mẫu giao dịch
5. `docs/THIET_KE_SO_DO_TRANG_THAI_STATE_MACHINE.md` (SM-01…SM-06, T01…T14, Q1…Q5), `docs/TONG_HOP_NGOAI_LE_VA_XU_LY_HE_THONG.md` (EX-01…EX-16)
6. `docs/DAC_TA_USE_CASE_CORE.md` (UC-01…39, BR-01…34), `docs/YEU_CAU_CHUC_NANG_VA_PHI_CHUC_NANG.md` (FR-01…70, NFR-01…08), `docs/THIET_KE_SO_DO_HOAT_DONG_ACTIVITY.md` (AD-01…06), `docs/TAI_LIEU_MO_TA_NGHIEP_VU_PHONG_KHAM_THU_Y.md`
7. `docs/LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md` — danh sách task + `curl` kiểm thử + **ID dữ liệu demo cố định**; dùng để biết tên endpoint, body, mã lỗi kỳ vọng
8. `docs/KE_HOACH_*.md`, `docs/BANG_TONG_HOP_*.md` — chỉ để biết tiến độ và người phụ trách, **không** dùng quyết định kỹ thuật

Mọi tài liệu đã đổi sang liên kết tương đối (hết lỗi `file:///c:/Users/Admin/...`). Mỗi tài liệu có "PHỤ LỤC: THAY ĐỔI SO VỚI PHIÊN BẢN 2.0" liệt kê chính xác chỗ đổi; đọc phụ lục khi cần biết vì sao một thứ khác bản cũ.

---

## 2. Tech stack (phiên bản đã kiểm chứng)

| Thành phần | Phiên bản dùng | Ghi chú |
|---|---|---|
| Java | 21 (Temurin) | |
| Spring Boot | **4.1.1** | Không tồn tại "4.1.11"; docs v3.0 đã sửa thành 4.1.1+. Không hạ xuống Boot 3 |
| Spring Cloud | **2025.1.3 (Oakwood)** | Docs ghi "2025.1.2 trở lên"; 2025.1.0/1.1 chỉ chạy Boot 4.0 |
| JWT | jjwt 0.12.6 + `jjwt-gson` | gson để không kéo Jackson 2 vào cạnh Jackson 3 |
| MySQL / RabbitMQ | 8.0 (1 container, 4 database logic) / 3.12-management | Ảnh CĐHA lưu trên **Docker Volume**, không dùng MinIO/S3 |
| Lombok + MapStruct | MapStruct 1.6.x, `lombok-mapstruct-binding` 0.2.0 | Thứ tự processor: lombok → binding → mapstruct-processor |
| Frontend | React 19 + **Create React App**, react-router 6, CSS thuần, `fetch` (không Axios), STOMP tự viết | Thư mục `fe/`, cổng 3000. **Docs v3.0 (Lộ trình, Kế hoạch) vẫn ghi "React Vite + TypeScript + Tailwind/Ant Design + Axios" — đây là thông tin lỗi thời; nhóm đã chốt CRA + CSS thuần, giữ nguyên** |

**Khác biệt Boot 4 / Spring Cloud 2025.1 so với mã mẫu trong docs (mẫu chưa cập nhật, sửa theo API hiện hành rồi ghi chú):**
- **Jackson 3**: `ObjectMapper`/`JsonMapper` ở `tools.jackson.databind`; riêng annotation (`@JsonInclude`) vẫn ở `com.fasterxml.jackson.annotation`. RabbitMQ dùng converter Jackson 3 của Spring AMQP 4 (`JacksonJsonMessageConverter`), **không** chép `Jackson2JsonMessageConverter` từ Quy chuẩn §9.2.
- Gateway: starter `spring-cloud-starter-gateway-server-webflux`, property `spring.cloud.gateway.server.webflux.*` (Lộ trình ghi `spring-cloud-starter-gateway`).
- Request DTO không dùng kiểu nguyên thủy cho trường tùy chọn: Jackson 3 từ chối thiếu `boolean`/`int` (400 `SYS_1001`) → dùng `Boolean`/`Integer`.

---

## 3. Kiến trúc & cổng

| Module | Port | Database (v3.0) | Package gốc |
|---|---|---|---|
| `service-registry` (Eureka, **không phụ thuộc common-library**) | 8761 | – | `com.vetclinic.registry` |
| `api-gateway` (WebFlux) | 8080 | – | `com.vetclinic.gateway` |
| `auth-service` | 8081 | `pet_clinic_auth_db` — 3 bảng: `roles`, `users`, `refresh_tokens` | `com.vetclinic.auth` |
| `appointment-service` | 8082 | `pet_clinic_appointment_db` — 2 bảng: `appointments`, `appointment_status_histories` | `com.vetclinic.appointment` |
| `clinical-service` | 8083 | `pet_clinic_clinical_db` — **14 bảng**: `species`, `pets`, `services`, `lab_test_items`, `service_lab_test_items`, `lab_test_reference_ranges`, `pet_visits`, `pet_visit_status_histories`, `visit_service_details`, `visit_service_detail_histories`, `lab_test_results`, `imaging_results`, `imaging_attachments`, **`service_invoices`** + VIEW `v_service_invoice_amounts` | `com.vetclinic.clinical` |
| `billing-pharmacy-service` | 8084 | `pet_clinic_billing_pharmacy_db` — **6 bảng**: `medicines`, `prescriptions`, `prescription_items`, **`medicine_invoices`**, **`medicine_invoice_items`**, `inventory_transactions` + VIEW `v_medicine_invoice_amounts` | `com.vetclinic.billing` |
| `notification-service` | 8085 | – (WebSocket STOMP + Mail, in-memory, **không SMS**) | `com.vetclinic.notification` |
| `common-library` | – | – | `com.vetclinic.common` |

**Phân chia trách nhiệm tiền (đổi lớn so với v2):** hóa đơn **dịch vụ** (`service_invoices`) nằm ở **clinical-service** cùng CSDL với chỉ định và đợt khám → mọi thao tác "chỉ định + lập hóa đơn", "hủy dịch vụ + hủy hóa đơn", "thu tiền + hoàn tất ca" là **một giao dịch cục bộ**, không còn sự kiện liên service. Hóa đơn **thuốc** (`medicine_invoices`) và kho nằm ở **billing-pharmacy-service**: "thu tiền thuốc + trừ kho + ghi nhật ký kho" là một giao dịch cục bộ. Không còn bảng `invoices`/`invoice_items`, không còn Saga/sự kiện "đóng ca" từ billing.

Cấu trúc thư mục: `vetclinic-backend/` (Maven multi-module, parent `spring-boot-starter-parent`; một `Dockerfile` dùng chung `--build-arg MODULE=<module>`), `fe/`, `docker-compose.yml` + `docker-compose.dev.yml` + `.env` ở gốc. Package trong mỗi service: `config, controller, service(+impl), repository, entity, dto/request, dto/response, mapper, client, event, constant` (scheduler ở `scheduler/`).

URI prefix (Gateway, Controller, Feign phải khớp): `/api/v1/auth/**` · `/api/v1/appointments/**` · `/api/v1/clinical/**`, `/api/v1/pets/**`, `/api/v1/species/**` · `/api/v1/pharmacy/**`, `/api/v1/billing/**` · `/api/v1/notifications/**`, `/ws/**`. Endpoint nội bộ `/internal/**`: Gateway **không định tuyến** (chặn từ ngoài), service cho qua không cần header.

Thành phần dùng chung trong `common-library` (dùng lại): `ApiResponse`/`PageResponse`, `ErrorCode`/`AppException` + advice bắt lỗi, `CommonSecurityConfig` + `HeaderAuthenticationFilter` + `SecurityUtils`, `RoleCode`, Feign (`FeignClientInterceptor`, `CustomFeignErrorDecoder`), sự kiện (`EventRouting`, record event, `RabbitCommonConfig.durableQueueWithDlq(...)`, `DomainEventRabbitPublisher`), `NamedLockService`.
- **Phát event:** trong method `@Transactional` chỉ `applicationEventPublisher.publishEvent(new XxxEvent(...))`; `DomainEventRabbitPublisher` gửi RabbitMQ sau commit (`AFTER_COMMIT`). Không gọi `rabbitTemplate` trực tiếp.
- **Endpoint public riêng của service:** `vetclinic.security.public-paths` (yml); ở Gateway: `vetclinic.gateway.public-paths`.
- **Feign khi không có người dùng** (consumer, scheduler): gọi `/internal/**`.

### 3.1. auth-service (không đổi bởi v3.0; code đã hoàn thành, 28 unit test)

| Endpoint | Quyền | Ghi chú |
|---|---|---|
| `POST /api/v1/auth/login` · `/refresh` · `/logout` · `/register` | public | Access Token 30m (HS256; claim `userId` là **chuỗi**; `role` là **mã thô không tiền tố**, `ROLE_` do `HeaderAuthenticationFilter` thêm đúng một lần), Refresh Token 7d (UUID, dùng 1 lần, Token Rotation). `register`: luôn `PET_OWNER`, tên đăng nhập = SĐT, trùng SĐT → `AUTH_1102` |
| `GET/PUT /me` · `POST /change-password` | đã đăng nhập | Đổi mật khẩu thu hồi mọi Refresh Token |
| `GET/POST /users` | ADMIN, RECEPTIONIST | Lễ tân: tìm bị ép lọc `PET_OWNER`, tạo chỉ được `PET_OWNER` |
| `GET /users/doctors` · `GET /users/{id}` | ADMIN, RECEPTIONIST (+ nhân sự y tế cho `{id}`) | |
| `PUT /users/{id}` · `POST /users/{id}/status` · `/role` · `/reset-password` | ADMIN (reset: cả RECEPTIONIST cho chủ nuôi) | Số phòng `roomNumber` duy nhất; khóa/đổi vai trò/reset **thu hồi mọi Refresh Token**; không tự khóa/đổi vai trò chính mình. Docs gọi khóa là `POST /users/{id}/lock` — giữ `/status` của code |
| `GET /internal/auth/doctors/active-count` · `/doctors` · `/users/{id}` · `/users?ids=` | không cần header | Cho Feign. Docs gợi ý đường dẫn `/internal/users/...`; **giữ tiền tố `/internal/auth/...`** của code (docs chỉ ghi "gợi ý") |

- Đăng nhập sai luôn `AUTH_1103`; sai 5 lần liên tiếp (theo username) → `AUTH_1112` (429) 15 phút. Mật khẩu 8–72 ký tự, có chữ và số, BCrypt strength 12 (kiểm tra độ dài **byte** trước `matches/encode`).
- **Frontend phải xếp hàng các lời gọi `/refresh`** (một lần tại một thời điểm); hai refresh đồng thời cùng token bị coi là đánh cắp và đăng xuất mọi thiết bị.
- Phạm vi vai trò: Admin gán **5 vai trò nhân sự**; `PET_OWNER` do lễ tân tạo hoặc tự đăng ký (FR-01 v3.0 đã ghi đúng như vậy → **câu hỏi mở #5 cũ đã đóng**). Chưa có quên mật khẩu tự phục vụ (xử lý tại quầy bằng `reset-password`), chưa xác minh SĐT/email khi đăng ký.

---

## 4. Mô hình nghiệp vụ v3.0 (tóm tắt các quy tắc bắt buộc)

### 4.1. Đặt lịch (appointment-service)
- 14 khung 30 phút: sáng 08:00, 08:30 … 11:00 (7 khung), chiều 13:30 … 16:30 (7 khung); nghỉ trưa 12:00–13:30. Đặt trong `application.yml`, không hard-code.
- **Sức chứa mỗi khung = `N_active × 2`** (N_active = số `VETERINARIAN` đang `ACTIVE`, lấy qua Feign auth; k=2 theo chu kỳ khám 2 pha). 3 bác sĩ ⇒ 6 chỗ/khung. Lịch `PENDING`/`CONFIRMED`/`CHECKED_IN` chiếm chỗ; chỉ `CANCELLED`/`NO_SHOW` trả chỗ.
- Khách **không chọn bác sĩ** (BR-04). Online → `PENDING`, `booking_channel = ONLINE`; lễ tân (điện thoại/quầy) → `CONFIRMED` ngay, `PHONE`/`WALK_IN`. Chủ nuôi chỉ hủy/đổi trước giờ hẹn ≥ 2 giờ (`APPT_1204`).
- **`appointments` chỉ có 9 cột** (`id, appointment_code, pet_id, appointment_date, start_time, reason, booking_channel, status, created_at`): **không có `owner_id`, `end_time`, `updated_at`, người/lý do hủy** — chủ nuôi suy ra qua `pets.owner_id` (clinical), người duyệt/hủy/lý do/thời điểm lấy từ `appointment_status_histories` (`changed_by_user_id`, `notes`; `NULL` = hệ thống).
- **NO_SHOW (EX-01, FR-17): `CONFIRMED` quá `appointment_date + start_time + 30 phút`** (không phải giờ kết thúc khung); cron 5 phút/lần, múi giờ VN (`0 */5 7-21 * * *`); phát `AppointmentNoShowEvent`. Khách đến trễ sau khi bị NO_SHOW được tiếp nhận như vãng lai (lịch giữ `NO_SHOW`, FR-18).
- Mã lịch hiển thị dạng `APT-YYYYMMDD-XXX`; mã không sinh bằng `count+1`, phải dựa UNIQUE hoặc id.

### 4.2. Tiếp đón & hàng đợi riêng từng bác sĩ (clinical-service) — thay thế hoàn toàn hàng đợi sảnh chung + "Khóa ưu tiên" của v2
- Check-in (UC-19, T01): `POST /pet-visits/check-in` body `{petId, appointmentId?, isEmergency, initialSymptoms, doctorId?}`. **Lễ tân không cân/đo** (bác sĩ làm trong phòng). Lịch hẹn phải `CONFIRMED`, đúng ngày, đúng thú cưng (`APPT_1205`, Feign **chỉ đọc**); thú cưng đang có đợt khám mở → `CLIN_1306`. Ca vào `WAITING`; chọn bác sĩ ngay (hệ thống **gợi ý bác sĩ ít người chờ nhất**) thì vào hàng đợi bác sĩ đó, chưa chọn thì vào **danh sách chờ phân công** (`doctor_id IS NULL`). Sau commit phát `VisitCheckedInEvent`; appointment-service tự chuyển lịch `CHECKED_IN`.
- **`queue_order BIGINT`** = thời điểm tiếp nhận tính bằng **epoch micro giây** (`QueueOrders.now()`); **`skip_count INT`** = số lần bị "Đẩy lùi 1 bậc" (có ở `pet_visits` và `visit_service_details`). **Thứ tự mọi hàng đợi: `ORDER BY (is_emergency AND skip_count = 0) DESC, queue_order ASC`** (chỉ mục `idx_visits_queue (doctor_id, status, is_emergency, queue_order)`, `idx_vsd_queue (status, queue_order)`). **Không còn tầng "có hẹn đúng giờ" và không còn cột `scheduled_at`** (giờ hẹn ở CSDL khác).
- Bốn loại hàng đợi, cùng cơ chế **Gọi số tiếp theo / Đẩy lùi 1 bậc** (BR-34): danh sách chờ phân công; hàng đợi **từng bác sĩ** (ca `WAITING` + `RESULTS_READY` có `doctor_id` = bác sĩ đó); hàng đợi Lab (`LABORATORY` `PENDING`); hàng đợi CĐHA (`IMAGING` `PENDING`). Hàng đợi Lab/CĐHA **tự có** khi chỉ định, không ai xếp.
- **Q2 – Phân công:** lễ tân gán ca `WAITING` cho **bác sĩ ACTIVE bất kỳ, bất cứ lúc nào, không cần bác sĩ rảnh**; đổi bác sĩ giữ nguyên `queue_order`; có **Gỡ phân công** (`doctor_id = NULL`). Ca `RESULTS_READY` luôn thuộc bác sĩ khám chính. Kiểm tra trong giao dịch bằng `version` (FR-25): bác sĩ vừa "Bắt đầu khám" ca này thì từ chối chuyển. Không ghi `pet_visit_status_histories` (trạng thái không đổi).
- **Q3 – Gọi số:** bác sĩ **không có ca `IN_PROGRESS`** (`CLIN_1302`) bấm "Gọi số tiếp theo": chọn ca đầu hàng đợi (`CLIN_1303` nếu trống), phát `PatientCalledEvent`, **ca chưa đổi trạng thái**. Bệnh nhân vào phòng mới bấm "Bắt đầu khám" (T02) / "Bắt đầu GĐ 2" (T07). **Gọi trước, đổi trạng thái sau.**
- **Q4 – Đẩy lùi 1 bậc:** `skip_count + 1`; `queue_order` mới = trung bình cộng của ca ngay sau và ca sau nữa (không có ca sau nữa → = ca ngay sau + 1; nếu người kế tiếp là ca cấp cứu chưa bị lùi → = `queue_order` của ca thường đầu tiên − 1); không còn ai phía sau → giữ nguyên vị trí. Không giới hạn số lần; ca cấp cứu đã bị lùi xếp như ca thường. Gọi ngay người kế tiếp. Ở phòng CLS: dịch vụ giữ `PENDING`, ghi `action = 'SKIPPED'`. Khách quay lại chỉ chờ tới lượt. Dùng `@Version` chống đua.
- **Q5 – Ca có kết quả CLS (T06):** hệ thống đặt `queue_order = MIN(queue_order trong hàng đợi bác sĩ) − 1`, `skip_count = 0` ⇒ ca lên **đầu hàng đợi** (chỉ sau ca cấp cứu). **Lễ tân không bị chặn**; ca mới xếp vào sau.
- **Trạng thái bác sĩ (SM-02, tính động, 2 giá trị):** `BUSY` nếu có ca `IN_PROGRESS`, ngược lại `AVAILABLE`. **Bỏ `AVAILABLE_PRIORITY_CALLBACK`**; ca `WAITING` đã gán **không** làm bác sĩ BUSY. Chỉ để hiển thị và chặn bác sĩ gọi số khi đang khám dở; **không dùng để chặn lễ tân phân công**. Bảng điều phối kèm `waitingCount` (ca `WAITING`/`RESULTS_READY`) và `suggestedDoctorId`.

### 4.3. State machine SM-01 `pet_visits` (T01…T14; 7 trạng thái; không lưu `started_at/closed_at/completed_at/cancel_reason`)
Mọi lần đổi trạng thái ghi 1 dòng `pet_visit_status_histories` (`changed_by_user_id` **cho phép NULL** = hệ thống; thời điểm/lý do lấy từ dòng này). Hai giai đoạn của `IN_PROGRESS` phân biệt bằng lịch sử (đã qua `WAITING_RESULTS` ⇒ GĐ 2).

| Mã | Chuyển | Điểm cốt lõi |
|---|---|---|
| T01 | ∅ → `WAITING` | check-in (xem 4.2) |
| T02 | `WAITING → IN_PROGRESS` (GĐ1) | đúng bác sĩ được gán (`CLIN_1311`), không có ca `IN_PROGRESS` khác; **tự tạo 1 `visit_service_details` dịch vụ khám (`EXAMINATION`, mặc định `EXAM_GEN`, `IN_PROGRESS`) + 1 `service_invoices` `PENDING`** |
| T03 | `WAITING → CANCELLED` | lễ tân hủy tiếp nhận (EX-03), lý do bắt buộc |
| T04 | GĐ1 → `WAITING_RESULTS` | có ≥1 dịch vụ `LABORATORY`/`IMAGING`; đã có `weight_at_visit`, `temperature` (>0) (`CLIN_1308`); dịch vụ tại phòng đã `COMPLETED`/`CANCELLED` (`CLIN_1309`). Mỗi dịch vụ CLS: `visit_service_details` `PENDING` + `service_invoices` `PENDING` (snapshot giá/VAT) cùng giao dịch; dịch vụ khám → `COMPLETED`; bác sĩ được giải phóng |
| T05 | GĐ1 → `WAITING_PAYMENT` | chốt ca không CLS: `diagnosis` bắt buộc (`CLIN_1307`, EX-12), sinh hiệu, dịch vụ tại phòng xong/hủy; khóa hồ sơ chuyên môn |
| T06 | `WAITING_RESULTS → RESULTS_READY` | không còn dịch vụ CLS `PENDING`/`IN_PROGRESS`; hệ thống tự chuyển (`changed_by_user_id = NULL`), áp Q5 |
| T07 | `RESULTS_READY → IN_PROGRESS` (GĐ2) | sau khi bác sĩ gọi số và bệnh nhân vào phòng |
| T08 | `RESULTS_READY → COMPLETED` | "Đóng ca / Khách bỏ về" (EX-11), **không tạo hóa đơn mới**, hóa đơn dịch vụ đã làm giữ `PENDING` làm **công nợ** |
| T09 | GĐ2 → `WAITING_PAYMENT` | chốt chuyên môn (cần `diagnosis`) |
| T10 | `WAITING_PAYMENT → COMPLETED` | khi **mọi `service_invoices` của ca là `PAID`/`CANCELLED`** (không xét thuốc), **trong cùng giao dịch** thu tiền; `changed_by_user_id` = thu ngân |
| T11 | GĐ1 → `CANCELLED` | hủy ca/chuyển viện không thu phí; **chỉ khi chưa có dịch vụ tại phòng `COMPLETED`**; mọi dịch vụ → `CANCELLED` kéo theo hóa đơn `CANCELLED` |
| T12 | `WAITING_RESULTS → COMPLETED` | khách bỏ về giữa chừng (EX-11); chỉ khi không có dịch vụ CLS `IN_PROGRESS`; CLS còn `PENDING` → `CANCELLED` kèm hóa đơn; hóa đơn đã làm giữ `PENDING`. **(T12 cũ "mở lại ca" nay là T14)** |
| T13 | *(bỏ)* | hoãn ngược `IN_PROGRESS → RESULTS_READY` không còn; thay bằng Đẩy lùi 1 bậc. Số hiệu giữ để khỏi lệch tham chiếu |
| T14 | `WAITING_PAYMENT → IN_PROGRESS` | **"Mở lại hồ sơ" bởi bác sĩ phụ trách HOẶC ADMIN**, lý do bắt buộc, bác sĩ không BUSY; hóa đơn `PAID` giữ nguyên, dịch vụ bỏ → hủy kèm hóa đơn, dịch vụ thêm → hóa đơn mới; **nếu đơn thuốc đã có hóa đơn thuốc chưa hủy (`PENDING`/`PAID`) thì không được sửa đơn thuốc** (`PHARM_1411`); chốt lại bằng T05/T09 |

Ca đã `WAITING_PAYMENT`/`COMPLETED`/`CANCELLED` bị khóa chỉnh sửa chuyên môn (NFR-06). Không có API hủy hóa đơn dịch vụ lẻ: muốn bỏ một khoản thì hủy dịch vụ (BR-33); dịch vụ có hóa đơn `PAID` không hủy được (`CLIN_1310`); hoàn tiền nằm ngoài phạm vi.

### 4.4. Dịch vụ & hóa đơn dịch vụ (SM-03, SM-05)
- **Phí khám là một dịch vụ.** Dịch vụ tại phòng (`VACCINATION`, `DEWORMING`, `EXTERNAL_PARASITE`, `INJECTION_INFUSION`, `PROCEDURE`, `SURGERY`): bác sĩ chỉ định (`POST /{id}/in-room-services`) rồi bấm "Đã thực hiện" (`service-details/{id}/perform`: ghi `START_PROCESSING` rồi `COMPLETED`); không qua hàng đợi CLS.
- **Mỗi dòng `visit_service_details` có đúng 1 `service_invoices` (UNIQUE), tạo cùng giao dịch**; `visit_service_details` **không chứa giá**. Hóa đơn snapshot `unit_price = services.price`, `vat_rate = services.vat_rate`; mã `SINV-YYYY-XXXX`. Bất biến: dịch vụ `CANCELLED` ⇔ hóa đơn `CANCELLED`.
- Số tiền **không lưu**: `quantity × unit_price × (1 + vat_rate) − discount_amount` (VIEW `v_service_invoice_amounts`; `quantity` lấy từ `visit_service_details`). Làm tròn `HALF_UP` 2 chữ số, `BigDecimal`.
- Trạng thái con của dịch vụ CLS **suy ra từ `action` mới nhất** của `visit_service_detail_histories` (không có cột riêng): `ORDERED`/`SKIPPED` → `QUEUED`; `START_PROCESSING` → `SAMPLING` (Lab: đang lấy mẫu); `SAMPLE_COLLECTED` → `ANALYZING`. `action` có 6 giá trị: `ORDERED, START_PROCESSING, SAMPLE_COLLECTED, COMPLETED, CANCELLED, SKIPPED` (**bỏ `POSTPONED`, `RECALL`**; bỏ tab "Ca tạm hoãn" và ghim "Ưu tiên kế tiếp").
- **EX-09 huy hiệu liên phòng** trên hàng đợi CĐHA: `labStatus = SAMPLING` (dịch vụ Lab cùng ca `IN_PROGRESS` và `action` mới nhất `START_PROCESSING`) / `SAMPLE_COLLECTED` (đã lấy mẫu, sẵn sàng chụp).
- Lab: phải nhập đủ các chỉ số thuộc gói (`service_lab_test_items`; thiếu → `SYS_1001`); lưu `min_value_snapshot`/`max_value_snapshot` theo loài; **không có cột `evaluation`** — `LabEvaluation` (`LOW` vàng, `NORMAL` xanh lá, `HIGH` đỏ) tính khi hiển thị. CĐHA: **một request multipart** (`description`, `conclusion`, `files[]`): tạo `imaging_results` trước, lưu tệp vào volume, rồi `imaging_attachments`; lỗi lưu tệp thì rollback và xóa tệp đã ghi; chỉ `image/jpeg`, `image/png`, `application/dicom`; tên đĩa là UUID; kiểm tra magic-byte.
- EX-08: hủy 1 dịch vụ CLS → dịch vụ + hóa đơn `CANCELLED` cùng giao dịch; nếu là dịch vụ CLS cuối cùng còn dở → kích hoạt T06.
- **Race `WAITING_RESULTS → RESULTS_READY`:** khóa dòng `pet_visits` (`findByIdForUpdate`, `READ_COMMITTED`) **trước** rồi mới đếm dịch vụ CLS còn dở — nếu đếm trước thì hai KTV hoàn tất cùng lúc làm ca kẹt mãi (đã gặp ở v2, giữ nguyên bài học).

### 4.5. Thu tiền dịch vụ (clinical) và bán thuốc tùy chọn (billing-pharmacy)
- **Thu hóa đơn dịch vụ (UC-34/35, FR-59/60):** `POST /pet-visits/{id}/service-invoices/confirm-payment` body `{invoiceIds:[...]}` (được thu một phần). Ca phải `WAITING_PAYMENT`, hoặc `COMPLETED` còn công nợ (`BILL_1409` nếu chưa chốt); mọi id phải thuộc ca (`BILL_1401`); hóa đơn phải `PENDING` (`BILL_1403` đã thu, `BILL_1408` đã hủy); `@Version` chống thu trùng (`SYS_1005`). Cuối giao dịch: nếu không còn hóa đơn `PENDING` và ca `WAITING_PAYMENT` → `COMPLETED` + lịch sử, rồi phát `VisitCompletedEvent` (chỉ để thông báo). Ca `COMPLETED` do khách bỏ về: chỉ thu công nợ, giữ nguyên trạng thái. `GET /pet-visits/with-debt`: ca `COMPLETED` còn hóa đơn `PENDING`.
- **Giảm giá trên từng hóa đơn** (`POST /service-invoices/{id}/discount`, `{discountAmount}`): `≤ quantity × unit_price × (1+vat)` của chính hóa đơn đó (`BILL_1402`, EX-14). Không lưu phương thức thanh toán (FR-57).
- **Đơn thuốc (UC-31):** chỉ định chuyên môn, **không giá, không trạng thái** (bỏ `status/dispensed_*`); tối đa 1 đơn/đợt khám (`visit_id UNIQUE`); mỗi thuốc 1 dòng; mỗi dòng đủ `medicineId, quantity>0, dosage, frequency, durationDays>0`. Kê/sửa khi ca `IN_PROGRESS` và đúng bác sĩ (Facade gọi Feign đọc trạng thái, `PHARM_1411` nếu đã khóa). **Kê vượt tồn kho chỉ cảnh báo** (`stockWarnings`, EX-13), không chặn (BR-32: khách có thể mua ngoài).
- **Hóa đơn thuốc (UC-36, SM-06):** chỉ phát sinh khi khách chọn mua tại phòng khám. `POST /pharmacy/prescriptions/{id}/medicine-invoices` `{items:[{prescriptionItemId, quantity}], discountAmount}` — ca phải `WAITING_PAYMENT`/`COMPLETED`; chặn nếu đơn đã có hóa đơn thuốc (`PHARM_1407`, kể cả hóa đơn đã hủy); mua một phần được, `quantity ≤` số kê (`PHARM_1410`); snapshot `unit_price = medicines.sale_price`, `vat_rate = medicines.vat_rate`; mã `MINV-YYYY-XXXX`; trạng thái `PENDING`. `PUT /billing/medicine-invoices/{id}/items` sửa dòng khi còn `PENDING` (`quantity = 0` bỏ dòng, còn ≥1 dòng, giữ snapshot giá, kiểm tra lại giảm giá). `POST .../{id}/cancel` = khách đổi ý (**hủy là kết thúc**, đơn không lập lại hóa đơn). **`POST .../{id}/confirm-payment` = `PAID` + trừ kho + ghi `inventory_transactions` `EXPORT_SALE` (`quantity` âm) trong MỘT `@Transactional`**; thiếu kho ở bất kỳ dòng nào → rollback toàn bộ, hóa đơn giữ `PENDING` (`PHARM_1406`, EX-15); sắp dòng theo `medicineId` trước khi trừ (tránh deadlock). **Không đổi trạng thái đợt khám.** Phát `MedicineInvoicePaidEvent` và `MedicineLowStockEvent` (khi `stock <= min_stock_alert`).
- **Trừ kho:** chỉ bằng `UPDATE medicines SET stock_quantity = stock_quantity - :qty, version = version + 1 WHERE id = :id AND stock_quantity >= :qty` (ảnh hưởng 0 dòng ⇒ thiếu hàng). **Không dùng `clearAutomatically`** (làm detach entity đang xử lý → `LazyInitializationException`); nơi cần đọc tồn thật thì `EntityManager.refresh` hoặc native query. Không bao giờ gán `stockQuantity` rồi `save()` (`@DynamicUpdate` cho `Medicine`). Bất biến: `stock_quantity = SUM(inventory_transactions.quantity)`. `inventory_transactions.quantity` **có dấu** (+ nhập, − xuất); `EXPORT_SALE` bắt buộc có `medicine_invoice_id`; không có `balance_after`/`reference_id`; loại `EXPORT_PRESCRIPTION` đổi thành `EXPORT_SALE`.
- Chống bán trùng (EX-16): `medicine_invoices.prescription_id UNIQUE` + chỉ xác nhận khi `PENDING` + `@Version`.
- **VAT (câu hỏi mở #3 cũ đã đóng):** thuế suất là **dữ liệu**, nằm ở `services.vat_rate` (mặc định 0.00) và `medicines.vat_rate` (mặc định 0.05), snapshot vào hóa đơn khi chỉ định/khi lập hóa đơn thuốc. **Không còn** cấu hình `billing.vat.*`.

### 4.6. Báo cáo, bệnh án
- Doanh thu (FR-69, Admin): 2 nguồn lấy từ 2 loại hóa đơn `PAID` theo `paid_at`: dịch vụ (VIEW, nhóm theo `services.category`) + thuốc (VIEW); công nợ = `service_invoices` `PENDING` của ca `COMPLETED`; hóa đơn `CANCELLED` không tính. Hai nguồn ở 2 service ⇒ **gọi API từng service rồi cộng ở tầng ứng dụng** (chưa quy định service nào đứng ra gộp — xem §10 #5).
- Vận hành (FR-70): tổng ca, ca thường vs ca có CLS, `COMPLETED`, `CANCELLED`, tỷ lệ `NO_SHOW`, số ca từng bác sĩ.
- Bệnh án trọn đời (FR-67/68, UC-38/39): `GET /pets/{id}/medical-history`; chủ nuôi chỉ thấy thú cưng của mình; đơn thuốc xem được (kể cả khi mua ngoài).

### 4.7. Ngoại lệ EX-01…EX-16 (tóm tắt nơi xử lý)
EX-01 NO_SHOW tự quét · EX-02 vãng lai · EX-03 bỏ về khi chờ (`WAITING→CANCELLED`) · EX-04 cấp cứu (đầu mọi hàng đợi; bị lùi thì mất ưu tiên) · EX-05 ca mới xếp vào bác sĩ đang có ca có kết quả (không chặn; ca có kết quả đã đứng đầu) · EX-06 vắng ở Lab/CĐHA → Đẩy lùi · EX-07 quay lại sau khi bị lùi (không cần thao tác) · EX-08 hủy 1 dịch vụ CLS · EX-09 huy hiệu liên phòng · EX-10 bác sĩ gọi số mà khách vắng → Đẩy lùi (không đổi trạng thái) · EX-11 bỏ về hẳn → `COMPLETED` + công nợ · EX-12 chốt thiếu thông tin · EX-13 kê vượt tồn (chỉ cảnh báo) · EX-14 giảm giá vượt hóa đơn · EX-15 thiếu kho khi bán → rollback, sửa hóa đơn hoặc hủy · EX-16 bán trùng. Thông báo cho chủ nuôi chỉ qua ứng dụng/Portal và email, **không SMS**.

---

## 5. Danh mục API đích v3.0 (theo Quy chuẩn §3.2 và Lộ trình; tên có thể điều chỉnh nhỏ nếu nhóm đồng ý — xem §10)

**appointment-service:** `GET /appointments/slots?date=` (**public**, không cần token — Gateway + service đều cho qua; trả `capacity, booked, remaining`…) · `POST /appointments` (chủ nuôi; lễ tân dùng cùng endpoint hoặc `POST /appointments/by-receptionist` theo docs) · `GET /appointments`, `/{id}` · `POST /{id}/confirm`, `/cancel` (bắt buộc `reason`), `/reschedule`, `/no-show` · `GET /{id}/history` · `GET /internal/appointments/{id}`.
**clinical-service:**
- Danh mục: `GET/POST/PUT /species`, `/pets` (`GET /pets?ownerId=`), `GET /pets/{id}/medical-history`, `GET/POST/PUT /clinical/services` (kèm `vat_rate`), `GET/POST/PUT /clinical/lab-test-items` (+ `.../{code}/reference-ranges`, `GET/PUT /services/{id}/lab-test-items`).
- Đợt khám: `POST /clinical/pet-visits/check-in` · `GET /pet-visits/queue` (bảng điều phối: chờ phân công + hàng đợi từng bác sĩ) · `GET /pet-visits/my-queue` (bác sĩ) · `GET /pet-visits?status=` · `GET /pet-visits/with-debt` · `POST /pet-visits/{id}/assign-doctor` `{doctorId}`, `/unassign-doctor`, `POST /pet-visits/call-next`, `POST /pet-visits/{id}/skip` · `/start-exam` (kèm tùy chọn `weightAtVisit, temperature, clinicalNotes`) · `PUT /{id}/clinical-info` · `/paraclinical-orders` `{serviceIds, notes}` · `/in-room-services` · `/cancel-exam` (T11) · `/cancel` (EX-03) · `/start-phase2` (T07) · `/abandon` (T08/T12) · `/close-visit` `{diagnosis, treatmentPlan, doctorAdvice}` · `/reopen` `{reason}` (T14) · `GET /{id}/service-invoices` · `POST /{id}/service-invoices/confirm-payment` · `POST /service-invoices/{id}/discount` · `GET /clinical/doctors/status`.
- Dịch vụ & CLS: `POST /clinical/service-details/{id}/perform`, `/cancel`, `/start`, `/sample-collected`, `/skip` · `GET /clinical/lab-queue`, `/imaging-queue` · `POST /clinical/lab-queue/call-next`, `/imaging-queue/call-next` · `POST/GET /clinical/lab-results/{detailId}` · `POST /clinical/imaging-results/{detailId}` (multipart).
- Nội bộ: `GET /internal/pets/{id}` (docs) — code dùng `/internal/clinical/pets/{id}`, `/pets?ids=`; `/internal/pet-visits/{id}/summary` (trạng thái + bác sĩ, cho billing).
**billing-pharmacy-service:** `GET/POST/PUT /pharmacy/medicines` · `POST/PUT /pharmacy/prescriptions`, `GET /prescriptions/{id}`, `/print` · `POST /pharmacy/prescriptions/{id}/medicine-invoices` · `POST /pharmacy/inventory/import`, `/adjust` (ADMIN) · `GET /billing/medicine-invoices/{id}` · `PUT /billing/medicine-invoices/{id}/items` · `POST /billing/medicine-invoices/{id}/confirm-payment`, `/cancel`.
**notification-service:** `GET /notifications` (của người dùng hiện tại), `POST /notifications/{id}/read`; **không còn API gửi email công khai** (email/WebSocket chỉ do sự kiện RabbitMQ kích hoạt).

### 5.1. Mã lỗi `ErrorCode` (Quy chuẩn §5.1 — 39 mã; **một số mã đổi nghĩa so với v2**, FE/test phải cập nhật)
`SYS_1000…1005` (UNCATEGORIZED, INVALID_INPUT_DATA 400, UNAUTHORIZED 401, ACCESS_DENIED 403, SERVICE_UNAVAILABLE 503, OPTIMISTIC_LOCK_CONFLICT 409) · `AUTH_1101…1106` · `APPT_1201 PET_NOT_FOUND` (**giữ mã dù pets thuộc clinical**), `1202 APPOINTMENT_NOT_FOUND`, `1203 SLOT_CAPACITY_EXCEEDED`, `1204 CANNOT_CANCEL_APPOINTMENT`, `1205 APPOINTMENT_NOT_CHECKABLE` · `CLIN_1301 VISIT_NOT_FOUND`, `1302 DOCTOR_NOT_AVAILABLE` (bác sĩ đang khám ca khác), `1303 QUEUE_EMPTY` (**không còn là "khóa ưu tiên"**), `1304 PARACLINICAL_SERVICE_IN_PROGRESS`, `1305 INVALID_STATUS_TRANSITION`, `1306 PET_HAS_OPEN_VISIT`, `1307 DIAGNOSIS_REQUIRED`, `1308 VITAL_SIGNS_REQUIRED`, `1309 IN_ROOM_SERVICE_PENDING`, `1310 PAID_SERVICE_CANNOT_BE_CANCELLED`, `1311 NOT_ASSIGNED_DOCTOR` · `BILL_1401 INVOICE_NOT_FOUND`, `1402 DISCOUNT_EXCEEDS_INVOICE_AMOUNT`, `1403 INVOICE_ALREADY_PAID`, `1408 INVOICE_ALREADY_CANCELLED`, `1409 VISIT_NOT_READY_FOR_PAYMENT` · `PHARM_1404 PRESCRIPTION_NOT_FOUND`, `1405 MEDICINE_NOT_FOUND`, `1406 INSUFFICIENT_STOCK`, `1407 PRESCRIPTION_ALREADY_INVOICED`, `1410 PURCHASE_QUANTITY_EXCEEDS_PRESCRIBED`, `1411 PRESCRIPTION_LOCKED`.
Mã lỗi mới của nhóm AUTH/khác giữ như code hiện có (`AUTH_1112`, `AUTH_1114`…); mã mới thì thêm cuối nhóm, không đánh số lại.

---

## 6. Sự kiện RabbitMQ & Feign v3.0 (nguồn chuẩn: Kiến trúc §5)

Exchange topic **`clinic.events`**. Mỗi consumer có queue riêng `<service>.<routing-key>`, durable, kèm DLQ `<queue>.dlq`; retry 3 lần (1s, 2s, 4s) rồi vào DLQ; consumer **idempotent**; chỉ phát **sau commit**. Payload là record trong `common-library` (`com.vetclinic.common.event`), luôn có `eventId`, `occurredAt`, `actorUserId`.

| Event | Routing key | Producer → Consumer |
|---|---|---|
| `AppointmentBookedEvent` | `appointment.booked` | appointment → notification |
| `AppointmentStatusChangedEvent` | `appointment.status_changed` | appointment → notification |
| `AppointmentNoShowEvent` | `appointment.no_show` | appointment → notification |
| `VisitCheckedInEvent` | `visit.checked_in` | clinical → **appointment** (chuyển `CONFIRMED→CHECKED_IN`, bỏ qua nếu đã xử lý; nếu lịch đã `NO_SHOW` thì ghi log cảnh báo), notification |
| `PatientCalledEvent` | `visit.patient_called` | clinical → notification (gọi số ở phòng khám/Lab/CĐHA; báo bị đẩy lùi khi vắng) |
| `ParaclinicalResultsReadyEvent` | `visit.results_ready` | clinical → notification (huy hiệu "Có kết quả", làm mới hàng đợi bác sĩ) |
| `VisitCompletedEvent` | `visit.completed` | clinical → notification (phiếu thu điện tử). **Chỉ thông báo** |
| `MedicineInvoicePaidEvent` | `pharmacy.medicine_invoice_paid` | billing-pharmacy → notification. **Không** đổi trạng thái đợt khám |
| `MedicineLowStockEvent` | `pharmacy.low_stock` | billing-pharmacy → notification (cảnh báo Admin) |

**Đã bỏ:** `VisitClosedToPaymentEvent`, `PaymentAndDispenseCompletedEvent`, và các tên v2 (`AppointmentCreatedEvent`, `DoctorPriorityCallbackEvent`, `LowStockEvent`, `PaymentCompletedEvent`…). Exchange cũ `clinic.exchange` không dùng.

**Feign — chỉ ĐỌC, gọi `/internal/**`, ngoài `@Transactional`, `connectTimeout 3000 / readTimeout 5000`, có `CustomFeignErrorDecoder`:** appointment→auth (đếm bác sĩ ACTIVE), appointment→clinical (thú cưng tồn tại và đúng chủ), clinical→auth (tên/vai trò/`room_number`, kiểm tra bác sĩ `VETERINARIAN` đang `ACTIVE`), clinical→appointment (lịch `CONFIRMED`, đúng ngày, đúng thú cưng), billing→clinical (`/internal/pet-visits/{id}/summary`: trạng thái + bác sĩ). **Không service nào ghi sang service khác qua Feign**; mọi đổi trạng thái liên service đi bằng sự kiện.

---

## 7. Lược đồ CSDL v3.0 — thay đổi so với v2 (đã nằm trong `schema_init.sql`)

- **Thêm:** `service_invoices` (clinical), `medicine_invoices`, `medicine_invoice_items` (billing), `service_lab_test_items`. **Bỏ:** `invoices`, `invoice_items`. 2 VIEW tính tiền. 25 FK vật lý nội bộ (auth 2, appointment 1, clinical 15, billing 7).
- **Cột thêm:** `pet_visits.queue_order/skip_count/version`, `visit_service_details.queue_order/skip_count`, `services.vat_rate`, `medicines.vat_rate/version`, `service_invoices.version`, `medicine_invoices.version`.
- **Cột bỏ (dẫn xuất hoặc trùng lịch sử):** `appointments.end_time/owner_id/confirmed_*/cancelled_*/cancel_reason/updated_at`; `pets.weight`; `pet_visits.assigned_room/started_at/closed_at/completed_at/cancel_reason` **và `scheduled_at`, `callback_postponed_at`** (các cột của v2 không còn); `visit_service_details.unit_price/subtotal`; `lab_test_results.evaluation`; `prescriptions.status/dispensed_*`; `prescription_items.unit_price/subtotal`; `inventory_transactions.balance_after/reference_id`.
- `pet_visit_status_histories.changed_by_user_id` **cho phép NULL** (trái với quy tắc v2 "giữ NOT NULL"); `visit_service_detail_histories.changed_by_user_id` vẫn NOT NULL. Enum: `InventoryTransactionType` = `IMPORT, EXPORT_SALE, ADJUSTMENT`; `PaymentStatus` dùng chung cho hai loại hóa đơn; **không còn** `PrescriptionStatus`, `InvoiceItemType`.
- `@Version` bắt buộc ở `PetVisit`, `ServiceInvoice`, `MedicineInvoice`, `Medicine`. Ràng buộc đáng nhớ: `service_invoices.visit_service_detail_id UNIQUE`, `medicine_invoices.prescription_id UNIQUE`, `prescriptions.visit_id UNIQUE`, `pet_visits.appointment_id UNIQUE`, `CHECK (stock_quantity >= 0)`, `UNIQUE(lab_test_results: detail, item)`, `UNIQUE(prescription_items: prescription, medicine)`.
- Mọi cột liên service là khóa ngoại **logic** (`BIGINT` + index, chú thích `LFK:`), không có FK vật lý chéo database. Không có truy vấn JOIN chéo database.
- Seed trong `schema_init.sql` v3.0: 6 vai trò, 1 admin, 2 loài, 7 dịch vụ (VAT 0), 4 chỉ số xét nghiệm + `service_lab_test_items` (gói `LAB_CBC` id=5), khoảng tham chiếu Chó/Mèo, 4 thuốc (VAT 5%) + nhập kho đầu kỳ. **Không còn dữ liệu demo** (PHẦN 5 của v2 đã mất); Lộ trình Task 1.2.3 dự kiến `seed_demo_data.sql` (chưa tồn tại) — xem §9.6.
- Mọi sửa schema phải cập nhật **cùng lúc** `schema_init.sql`, `THIET_KE_CO_SO_DU_LIEU_ERD.md` (+ bản `.docx`), `DANH_SACH_KIEU_ENUM_HE_THONG.md`, rồi `docker compose down -v`.

---

## 8. Quyết định kỹ thuật vẫn còn hiệu lực (đọc trước khi code)

### 8.1. `common-library` không được làm sập Gateway/Eureka
Gateway (WebFlux) và Eureka không được nạp bean servlet. Bean servlet (`GlobalExceptionHandler`, `HeaderAuthenticationFilter`, `CommonSecurityConfig`) gắn `@ConditionalOnWebApplication(type = SERVLET)`; bean Feign gắn `@ConditionalOnClass(name = "feign.Feign")`; dependency web/security/feign trong `common-library/pom.xml` là `<optional>true</optional>`. `HeaderAuthenticationFilter` **không** là `@Component` (nếu lỡ thì phải `FilterRegistrationBean.setEnabled(false)` — Boot tự đăng ký mọi Filter bean và filter sẽ chạy 2 lần); chỉ add vào `SecurityFilterChain`. **Quy chuẩn v3.0 §2.2 yêu cầu đổi cách nạp sang `@AutoConfiguration` + file `AutoConfiguration.imports` và mỗi service chỉ quét package của chính nó**, trong khi code hiện dùng `scanBasePackages = "com.vetclinic"` + điều kiện; cả hai đều chạy đúng. Đừng tự đổi: xem §10 #4.

### 8.2. Bảo mật
- Gateway **xóa `X-User-Id`/`X-User-Role` từ client** trước khi đọc JWT; thiếu/sai/hết hạn token → **401 `SYS_1002` ngay tại Gateway** (để FE refresh; 403 chỉ khi sai vai trò). Whitelist public: `POST /auth/login`, `/refresh`, `/register`, `/actuator/health`, **`GET /appointments/slots` (mới theo docs v3.0)**; Portal công khai còn có "bảng giá dịch vụ" (đọc danh mục dịch vụ không cần đăng nhập — chưa có trong code). Chặn `/internal/**` ở Gateway (403).
- **Chỉ publish port 8080**; port hạ tầng chỉ mở ở `docker-compose.dev.yml`. CORS **chỉ ở Gateway** (có 3000 và 5173).
- `@PreAuthorize("hasRole('...')")` trên mọi endpoint nghiệp vụ. JWT secret từ `JWT_SECRET` (≥ 32 byte), dùng chung auth + gateway + notification; không commit.
- WebSocket: Gateway route `/ws/**` → `lb:ws://notification-service`; JWT trong frame STOMP `CONNECT` (`Authorization: Bearer ...` hoặc `token`), **không** `?token=`; `SEND` bị cấm. Hiện code chỉ cho `SUBSCRIBE` `/user/queue/notifications` và `/topic/role/<vai trò của mình>`; **Lộ trình Task 5.1 mô tả bộ topic khác** (`/topic/lobby`, `/topic/doctor/{id}`, `/topic/lab`, `/topic/imaging`, `/topic/admin`) — xem §10 #6.

### 8.3. Thời gian & múi giờ
- **JVM mọi service có CSDL chạy UTC**: `TZ=UTC` + `-Duser.timezone=UTC` trong Docker và `TimeZones.forceUtcDefault()` ở dòng đầu `main()`; JDBC giữ `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`, `hibernate.jdbc.time_zone: UTC`. (Đã đo: JVM giờ VN + phiên UTC làm `09:00` bị ghi thành `01:00` do `LocalTime` đi qua `java.sql.Time`; đừng đổi lại.)
- Giờ phòng khám luôn **tường minh**: bean `Clock` (`Asia/Ho_Chi_Minh`) cho "hôm nay/bây giờ", `@Scheduled(..., zone = "Asia/Ho_Chi_Minh")`. Không dùng `LocalDate.now()` trần. `Instant` cho mọi mốc giao dịch (UTC trong DB); `queue_order` = epoch micro giây của `Instant`.
- `appointment_date/start_time` là giờ địa phương VN lưu nguyên giá trị.

### 8.4. Concurrency
- Docs v3.0 chọn **khóa lạc quan `@Version`** làm cơ chế chính (hai thu ngân bấm cùng lúc → một thành công, một nhận `SYS_1005`), cộng ràng buộc UNIQUE ở CSDL.
- Đã có trong code và vẫn dùng khi cần "kiểm tra rồi ghi" mà bảng không có `version`: `SlotLockService` (appointment) / `NamedLockService` (common-library) — `GET_LOCK` MySQL trên **kết nối riêng (không lấy từ pool)**, chạy `TransactionTemplate` bên trong, chỉ đóng kết nối sau khi commit, sắp tên khi lấy nhiều khóa; phương thức gọi khóa **không** `@Transactional`; lỗi `SYS_1008 RESOURCE_BUSY`. Dùng cho khung giờ đặt lịch, một thú cưng/ngày, `vetclinic:visit-pet:<id>` chặn check-in trùng. Với v3.0, kiểm tra `assign-doctor`/`skip`/`call-next` bằng `version` của `pet_visits` là đủ; không cần khóa tên `vetclinic:doctor:<id>` cũ.
- Chuyển trạng thái trên bảng không có `version` (`appointments`): `findByIdForUpdate` (`PESSIMISTIC_WRITE`) rồi kiểm tra lại trạng thái. Lỗi khóa dòng/deadlock → `PersistenceExceptionHandler` đổi thành 409 `SYS_1005`.
- Mọi action `POST /{id}/<action>` kiểm tra trạng thái nguồn; bấm hai lần thì lần sau trả lỗi nghiệp vụ, không tạo bản ghi trùng. Mã sinh tự động (`APT-`, `VISIT-`, `PET-`, `SINV-`, `MINV-`) dựa vào UNIQUE/id, không `count + 1`.

### 8.5. Lỗi trong code mẫu của docs (đừng chép nguyên)
- `startExamination` mẫu gọi Feign **trong** `@Transactional` — Quy chuẩn v3.0 đã sửa (Feign làm giàu dữ liệu ở Controller sau commit); giữ như vậy.
- Ném `AppException` (RuntimeException) trong `@Transactional` sẽ rollback cả lệnh ghi phải giữ (ví dụ thu hồi mọi Refresh Token khi phát hiện đánh cắp) → dùng `@Transactional(noRollbackFor = AppException.class)` + UPDATE nguyên tử. Áp dụng cho mọi chỗ "chặn lỗi nhưng vẫn phải ghi DB".
- Spring Security 7 từ chối BCrypt > 72 byte bằng exception; claim `userId` luôn ký dạng **chuỗi** (jjwt-gson có thể parse số thành `5.0`).
- Mẫu `confirmServicePayment` (Quy chuẩn §8.3): so sánh enum qua tham số JPQL (không so chuỗi), kiểm tra **đủ** `invoiceIds` thuộc ca (`BILL_1401`); mẫu chỉ là đoạn trích, khi chép phải bổ sung import/khóa dòng cho đúng.

### 8.6. Docker Compose
- Gateway khởi động **sau cùng** (`depends_on` mọi service nghiệp vụ `service_healthy`); Eureka tắt `use-read-only-response-cache`. Đừng bỏ hai cấu hình này (nếu không request đầu sau `up` trả 503).
- `depends_on` với `condition: service_healthy`; healthcheck cho mysql/rabbitmq/eureka/service (`/actuator/health`). 7 JVM chung một máy: `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, `mem_limit` 384–512m mỗi service. Eureka `prefer-ip-address=true`; đừng chạy lẫn service trong IDE với service trong Docker khi chưa cấu hình hostname.
- Ảnh CĐHA ở volume `imaging-uploads` → `/app/uploads/imaging/`; `spring.servlet.multipart.max-file-size` 20MB.
- `schema_init.sql` không idempotent (MySQL 8 không có `CREATE INDEX IF NOT EXISTS`); chỉ mount vào `/docker-entrypoint-initdb.d/`; sửa schema thì `docker compose down -v`.

---

## 9. KHOẢNG CÁCH: mã nguồn hiện tại (v2, 29/10) ↔ docs v3.0 (11/11) — danh sách việc di chuyển

Đã xác minh lại ngày 11/11 bằng cách tìm trong `vetclinic-backend/` (mã nguồn chính của `clinical-service`): **0** tham chiếu `ServiceInvoice`/`MedicineInvoice`/`queue_order`/`QueueOrders`; vẫn còn `PetVisit.scheduled_at`, `PetVisit.callback_postponed_at`, enum `DoctorStatus.AVAILABLE_PRIORITY_CALLBACK` (dùng ở `DoctorStatusServiceImpl`, `VisitServiceImpl`, `DoctorController`) và `Invoice`/`InvoiceItem` ở billing. Số liệu kiểm thử cũ (288 unit test, 560 kiểm tra e2e, 50 bước smoke FE) **chỉ đúng cho thiết kế v2**.

### 9.1. common-library — ✅ PHẦN THÊM/ĐỔI SỐ ĐÃ XONG 12/11/2026 (Phase 1); còn lại: xóa event/routing key v2 ở bước 7.1, `QueueOrders` làm ở clinical (3.A3)
*(Mô tả gốc bên dưới; `ErrorCode` và 7 event mới đã làm xong — xem `LO_TRINH_SUA_CODE_V3.md` Phase 1. `VisitCheckedInEvent` chưa có `doctorId`, thêm ở 3.B1.)* Đổi `ErrorCode` theo §5.1 (đổi nghĩa `CLIN_1303`, `CLIN_1308`; thêm `CLIN_1309/1310/1311`, `BILL_1408/1409`, `PHARM_1407/1410/1411`…). Thay bộ event: bỏ `VisitClosedToPaymentEvent`, `PaymentAndDispenseCompletedEvent`, `DoctorPriorityCallbackEvent`, `LowStockEvent`, `AppointmentCreatedEvent`; thêm 9 event + routing key ở §6 (`EventRouting`). Thêm `QueueOrders` (epoch micro) nếu đặt ở common. Enum: `InventoryTransactionType` → `EXPORT_SALE`; `PaymentStatus` dùng chung.

### 9.2. appointment-service — ✅ ĐÃ XONG 12/11/2026 (Phase 2); còn lại ngoài service này: Gateway công khai `/slots` (6.1), notification nghe `appointment.booked`/`no_show` rồi bỏ event cũ (5.1, 5.2, 7.1)
*(Mô tả gốc bên dưới là danh sách việc đã làm; xem `LO_TRINH_SUA_CODE_V3.md` Phase 2 để biết chi tiết và kết quả đo.)*
- Sức chứa **`N_active × 2`** (code đang dùng `N_active`). Bỏ `end_time`, `owner_id`, `updated_at` khỏi entity/DTO/migration (`appointments` 9 cột). Lọc "lịch của chủ nuôi" và `ownerName/ownerPhone/petName` phải đi qua clinical (không còn `owner_id`) — cần endpoint nội bộ kiểu `GET /internal/clinical/pets?ownerId=` (xem §10 #3).
- **NO_SHOW: `start_time + 30 phút`** (code đang dùng giờ kết thúc khung + 30). Cron 5 phút. Phát `AppointmentNoShowEvent`; đổi `appointment.created` → `appointment.booked`.
- `GET /slots` thành **public**. Consumer `visit.checked_in` giữ (idempotent + DLQ; ghi log cảnh báo nếu lịch đã `NO_SHOW`).
- Tính năng **có trong code nhưng docs không mô tả**: tự hủy `PENDING` quá hạn, `reschedule`, báo cáo `reports/summary` (FR-70 vẫn yêu cầu tỷ lệ NO_SHOW), `GET /{id}/history` — giữ, báo nhóm xác nhận (§10 #8).

### 9.3. clinical-service (thay đổi lớn nhất) — 🟨 KHỐI A ✅ XONG 12/11/2026 (Phase 3.A); Khối B (3.B1–3.B14) chưa làm
*(Danh sách bên dưới là mô tả đích; phần đã làm và các điều chỉnh/lưu ý ghi ở `LO_TRINH_SUA_CODE_V3.md` Phase 3, mục "Ghi chú điều chỉnh sau Khối A".)*
- **Thêm** bảng/entity/repository/service/API `service_invoices` + VIEW; lập hóa đơn cùng giao dịch ở T02/T04/dịch vụ tại phòng; hủy dịch vụ kéo theo hủy hóa đơn; thu tiền + hoàn tất ca trong cùng giao dịch (T10) + `discount`, `with-debt`, bảng kê. Bỏ `unit_price/subtotal` khỏi `visit_service_details`; bỏ `lab_test_results.evaluation` (tính khi hiển thị).
- **Viết lại hàng đợi:** bỏ `scheduled_at`/`QueueSchedule`/hàng đợi 3 bậc, `callback_postponed_at`, `postpone-callback`/`call-back`, trạng thái `AVAILABLE_PRIORITY_CALLBACK`, khóa tên `vetclinic:doctor:<id>`, `POSTPONED`/`RECALL`/`NEXT_IN_LINE`. Thêm `queue_order/skip_count`, `idx_visits_queue`, `idx_vsd_queue`, API `queue`, `my-queue`, `assign-doctor` (không kiểm tra bác sĩ rảnh), `unassign-doctor`, `call-next` (3 nơi), `skip` (3 nơi), `start-phase2`, `abandon`, `cancel-exam`, `in-room-services`, `perform`, `sample-collected`; `doctors/status` chỉ `BUSY`/`AVAILABLE` + `waitingCount`.
- Đổi `close-visit`: không phát `visit.closed_to_payment`, **không** gọi billing; chỉ validate, chuyển `WAITING_PAYMENT`, ghi lịch sử. `reopen`: bác sĩ phụ trách **hoặc ADMIN**, bỏ Feign hủy hóa đơn billing (hóa đơn dịch vụ là của clinical; chỉ chặn sửa đơn thuốc khi đã có hóa đơn thuốc chưa hủy — việc chặn nằm ở billing `PHARM_1411`). Bỏ consumer `payment.completed`. Phát `PatientCalledEvent`, `ParaclinicalResultsReadyEvent`, `VisitCompletedEvent`, `VisitCheckedInEvent`.
- API nội bộ cho billing: `/internal/pet-visits/{id}/summary` (docs) — code hiện có `/internal/clinical/visits/{id}`; bỏ `/visits/{id}/billable-services`. Check-in nhận `doctorId` tùy chọn và `initialSymptoms`.

### 9.4. billing-pharmacy-service — ✅ ĐÃ XONG 12/11/2026 (Phase 4); danh sách dưới đây là mô tả các việc đã làm (xem ghi chú sau Phase 4 trong `LO_TRINH_SUA_CODE_V3.md`)
Bỏ `Invoice`, `InvoiceItem`, `InvoiceCalculator` (VAT cấu hình), consumer `visit.closed_to_payment`, `from-visit`, `cancel-for-reopen`, `dispense` và `PrescriptionStatus`/`PRESCRIBED`/`DISPENSED`, "hàng đợi phát thuốc". Thêm `MedicineInvoice`/`MedicineInvoiceItem`, các API ở §4.5/§5, VIEW; đơn thuốc không giá/không trạng thái; kê vượt tồn chỉ cảnh báo (code hiện **chặn** `PHARM_1406`); `inventory_transactions.quantity` có dấu, `EXPORT_SALE`. Báo cáo doanh thu thuốc từ `medicine_invoices`. Khóa tên `vetclinic:visit-billing:<visitId>` cũ không còn cần (không còn hóa đơn gộp theo ca).

### 9.5. notification-service — ✅ ĐÃ XONG 12/11/2026 (Phase 5); danh sách dưới đây là mô tả các việc đã làm (xem ghi chú sau Phase 5 trong `LO_TRINH_SUA_CODE_V3.md`)
Đổi listener theo 9 sự kiện mới (thêm `visit.patient_called`, `visit.results_ready`, `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`, `appointment.no_show`; bỏ `doctor.priority_callback`, `visit.closed_to_payment`, `payment.completed`, `stock.low_alert`). Bỏ `POST /notifications/send-email`; thêm `POST /notifications/{id}/read`. Nội dung: gọi số (phòng + STT), báo bị đẩy lùi, ca có kết quả; không SMS.

### 9.6. Docker, dữ liệu mẫu, e2e, frontend
- **`docker-compose.yml` đang mount `docs/schema_init.sql` v3.0** (+ `docs/seed_demo_data.sql` từ 12/11) ⇒ stack đầy đủ không khởi động được cho tới khi backend được di chuyển (chỉ `mysql-server`, `rabbitmq-broker`, `mailpit`, `service-registry`, và `auth-service` chạy được ngay). Healthcheck MySQL đã đổi (12/11) từ bảng `invoice_items` (không còn) sang kiểm tra `pets` có ≥ 2 dòng. `./docs/demo-images` vẫn được mount nhưng schema mới **không còn bản ghi ảnh CĐHA mẫu**.
- **[ĐÃ SỬA 12/11/2026 (Phase 0.2) — đoạn này chỉ còn là lịch sử]** `schema_init.sql` v3.0 từng mất hai sửa lỗi đã chốt ở v2 (xem §10 #1); nay file đã có `SET NAMES utf8mb4;` và hash admin mới khớp `Admin@123`. Mô tả lỗi cũ: không có `SET NAMES utf8mb4;` ở đầu file (tiếng Việt trong seed sẽ bị mã hóa 2 lần khi chạy qua `mysql` client trong entrypoint Docker), và hash BCrypt của `admin` (`$2a$12$EixZaYVK1fsbw1ZfbX3OXePaWxn96p36WQoeG6Lruj3vjPGga31lW`) **không khớp `Admin@123`** (đã kiểm tra bằng `bcrypt.checkpw`) — dòng chú thích ngay cạnh còn ghi "cần kiểm tra lại hash trước khi demo". Chưa sửa vì file docs thuộc về nhóm.
- **Dữ liệu demo:** v3.0 không còn 15 tài khoản demo / 20 đợt khám / 11 hóa đơn của v2. Lộ trình định nghĩa bảng ID cố định (`admin`=1, `bs_tien`=2 P101, `bs_an`=3 P102, `bs_mai`=4 P103, `letan_hoa`=5, `ktv_lab_minh`=6 P201, `ktv_cdha_dung`=7 P202, `khach_lan`=8 sở hữu thú cưng #1 Lu (mèo, `PET-2026-0001`) và #2 Milo (chó)) và `docs/seed_demo_data.sql` (**đã có từ 12/11/2026**: đúng 7 tài khoản id 2–8 + 2 thú cưng, **mật khẩu chung của mọi tài khoản demo là `Admin@123`**, compose mount làm `02-seed_demo_data.sql`; Kế hoạch Tuần 12 còn muốn mở rộng thành ~100 dòng — việc của Khởi, Phase 7). Mọi `curl` trong Lộ trình dựa trên các ID này; luồng chuẩn: dịch vụ 100.000 + 250.000 + 180.000 = **530.000đ**, thuốc 10 × 15.000 × 1,05 = **157.500đ**, Clavamox tồn 200 → 190.
- **`e2e/*` (`appointment.sh, clinical.sh, billing.sh, notification.py, features.py, fe_smoke.mjs, reset_data.sh`) nhắm vào thiết kế v2** (hóa đơn gộp, hàng đợi 3 bậc, Khóa ưu tiên, dispense…) và bảng cũ — vô hiệu cho tới khi viết lại theo Task 3.x–5.5 của Lộ trình. `reset_data.sh` xóa bảng cũ nên **không chạy** trên schema mới.
- **Frontend (`fe/`, nối API v2):** các trang cần sửa theo API mới — Lễ tân: *hàng đợi/điều phối* (danh sách chờ phân công + cột hàng đợi từng bác sĩ, gợi ý bác sĩ ít người chờ, Xếp/Chuyển/Gỡ phân công luôn dùng được, hiển thị `skip_count`, bỏ nhóm "đúng giờ hẹn"), *thu ngân* (bảng kê **nhiều hóa đơn dịch vụ**, giảm giá từng hóa đơn, thu một phần, công nợ) và màn hình mới *bán thuốc theo đơn* (lập/sửa/hủy/xác nhận hóa đơn thuốc, in đơn cho khách mua ngoài); Bác sĩ: *hàng đợi riêng* với "Gọi số tiếp theo"/"Đẩy lùi 1 bậc", bỏ hộp thoại "Khóa ưu tiên"/"Mời bệnh nhân vào phòng" cũ, "Bắt đầu khám"/"Bắt đầu GĐ 2", dịch vụ tại phòng, cảnh báo tồn kho khi kê đơn, "Mở lại hồ sơ" (cả bác sĩ), "Khách bỏ về"; KTV Lab/CĐHA: gọi số + Đẩy lùi + "Đã lấy mẫu" + huy hiệu liên phòng, **bỏ tab "Ca tạm hoãn do vắng mặt"**; Admin: báo cáo 2 nguồn doanh thu, kho (`import`/`adjust`), bỏ trang mở lại ca T12 cũ; Portal: trang công khai (bảng giá dịch vụ, khung giờ trống). Nhãn/màu enum (`lib/labels.js`), ánh xạ mã lỗi và `api/endpoints.js` đều đổi.
- **Frontend: phần còn đúng:** `src/config.js`, `api/client.js` (401 → refresh **một lần dùng chung**, single-flight), `api/session.js`, `api/stomp.js` (STOMP 1.2 tự viết, JWT trong `CONNECT`, tự nối lại), `NotificationContext` + `useNotificationEvents` + `usePolling`, `RequireRole`, bộ `components/ui`, `hooks/useAsync`/`useAction` (chặn bấm đúp), `lib/format.js` (giờ VN; `formatDateTime` ghép từng phần ra `dd/MM/yyyy HH:mm`, đừng đổi lại), ảnh CĐHA tải qua API có `Authorization` (blob). Chưa có: quên mật khẩu tự phục vụ; chủ nuôi chưa xem được hóa đơn của mình (docs v3.0 không yêu cầu; đơn thuốc xem trong Lịch sử khám bệnh).

---

## 10. Chỗ docs mâu thuẫn / thiếu — cách xử lý đã chốt và câu hỏi mở (báo người dùng khi đụng tới)

| # | Vấn đề | Cách xử lý đang áp dụng | Cần nhóm xác nhận |
|---|---|---|---|
| 1 | ~~`schema_init.sql` v3.0 thiếu `SET NAMES utf8mb4;` và hash admin sai~~ | **ĐÃ ĐÓNG 12/11 (Phase 0.2):** đã thêm `SET NAMES utf8mb4;` và đổi hash BCrypt(12) (đã `checkpw` với `Admin@123`) ngay trong `docs/schema_init.sql` theo lộ trình của người dùng | Chỉ còn: báo nhóm biết file docs này đã được sửa (có thể cần cập nhật bản `.docx` nếu trích hash) |
| 2 | ~~Docs tham chiếu `seed_demo_data.sql` nhưng chưa có~~ | **ĐÃ ĐÓNG 12/11 (Phase 0.3):** có `docs/seed_demo_data.sql` theo bảng ID ở §9.6, mật khẩu chung `Admin@123` | Quy mô ~100 dòng (Phase 7) |
| 3 | `appointments` bỏ `owner_id`: "lịch của tôi" (PET_OWNER), lọc theo chủ, `ownerName/ownerPhone` cần danh sách pet của chủ | Gọi Feign sang clinical (`/internal/...pets?ownerId=`) **ngoài transaction**, rồi lọc `pet_id IN (...)`; service kia sập → trả rỗng/trường null thay vì lỗi | Tên endpoint nội bộ |
| 4 | Quy chuẩn §2.2 cấm `scanBasePackages = "com.vetclinic"` và đòi `AutoConfiguration`; code hiện dùng scan + điều kiện | **Giữ nguyên** (đang chạy đúng); chỉ đổi nếu nhóm yêu cầu | Có đổi không |
| 5 | Báo cáo doanh thu 2 nguồn (FR-69): docs không nói service nào gộp | Mỗi service cung cấp endpoint của mình (clinical: dịch vụ + công nợ; billing: thuốc); Admin FE gọi cả hai rồi cộng | Có cần endpoint tổng hợp ở một service |
| 6 | Notification: Lộ trình mô tả `/topic/lobby`, `/topic/doctor/{id}`, `/topic/lab`, `/topic/imaging`, `/topic/admin` và "màn hình sảnh"; code dùng `/user/queue/notifications` + `/topic/role/<ROLE>` và chặn SUBSCRIBE kênh khác | Giữ mô hình bảo mật hiện tại; gọi số gửi riêng cho chủ nuôi + vai trò liên quan; chưa làm màn hình sảnh công cộng | Có cần màn hình sảnh |
| 7 | Tên endpoint khác nhẹ giữa docs và code (lễ tân đặt lịch `by-receptionist`, `lock` ↔ `status`, `/internal/...` đường dẫn, `GET /lab-test-items/{code}/reference-ranges`) | Nội bộ: giữ code. Công khai mà FE/test của docs dùng: ưu tiên đúng tên trong Quy chuẩn §3.2/Lộ trình khi viết mới | Có đồng bộ hoàn toàn không |
| 8 | Code có mà docs không nói: tự hủy `PENDING` quá hạn, `reschedule`, `reset-password`, tìm/khóa tài khoản, `GET /pet-visits` lọc đa tiêu chí, báo cáo `reports/*`, `GET /{id}/history` | Giữ (không phá luồng docs) | Có đưa vào báo cáo không |
| 9 | Feign ngoài danh mục §5.1 của Kiến trúc: clinical→billing (đơn thuốc trong bệnh án trọn đời), notification→auth/clinical (làm giàu nội dung, email), billing→clinical | Giữ, chỉ ĐỌC, ngoài transaction, lỗi thì bỏ qua trường làm giàu | Bổ sung vào bảng Feign của docs |
| 10 | Mã lỗi trong code lệch Quy chuẩn §5.1 nhiều hơn dự tính: `APPT_1205`, `CLIN_1303/1306/1307/1308/1309/1310/1311`, `BILL_1402`, `PHARM_1407/1408/1409/1410/1411` đều khác số hoặc khác nghĩa (bảng đối chiếu đầy đủ ở `LO_TRINH_SUA_CODE_V3.md` bước 1.1) | Theo Quy chuẩn §5.1; mã chỉ có trong code dời xuống số tiếp theo của nhóm; cập nhật FE và test cùng lúc | – |
| 11 | Docs còn ghi frontend = Vite/TS/Tailwind/AntD/Axios | Giữ CRA + CSS thuần + `fetch` | Cập nhật Kế hoạch/Lộ trình cho khớp báo cáo |
| 12 | Tiêm phòng/tẩy giun định kỳ: Nghiệp vụ §35 chỉ yêu cầu ghi nhận lần thực hiện vào lịch sử bệnh án | Đã đủ qua `visit_service_details` (không có bảng/lịch tiêm riêng); FE bỏ "lịch tiêm phòng" | – |
| 13 | Hoàn tiền hóa đơn `PAID` ngoài phạm vi | Hóa đơn `PAID` không hủy/mở lại ca được (`CLIN_1310`, `INVOICE_ALREADY_PAID`) | Ghi vào hạn chế của báo cáo |
| 14 | Kiến trúc §3 và Bảng tổng hợp (Tuần 12, Khởi) nêu Gateway có **Rate Limiting**; không có trong Quy chuẩn/Lộ trình Task 1.5 và **không có trong code** Gateway (tìm `RateLimiter` = 0) | Không làm; không ghi vào báo cáo như tính năng đã có | Làm (vd. `RequestRateLimiter` + Redis/in-memory) hay bỏ khỏi tài liệu |
| 15 | Nội dung cũ còn sót trong docs v3.0 (không đổi thiết kế, tránh làm theo nhầm): Lộ trình tiêu đề Giai đoạn 3 còn "KHÓA ƯU TIÊN"; Lộ trình Task 5.2.3 "ca chờ tái khám / đã hoãn" và Bảng tổng hợp Tuần 10 (Linh) "Hộp thoại ưu tiên ca tái khám"; Kiến trúc §7 "Axios tự gọi `/refresh`"; Lộ trình Task 1.1.1 "Khởi tạo Git" (đã git init ngày 12/11, xem §13) | Bỏ qua phần cũ, theo §4.2/§4.3 ở trên (không còn Khóa ưu tiên/tab hoãn; FE dùng `fetch`) | Sửa lại 3 file này trước khi nộp |
| 16 | Bảng tổng hợp/Kế hoạch ghi Tuần 8–11 "ĐÃ XONG" (kể cả `billing-pharmacy`, `notification`, FE) dù mã nguồn mới ở mức v2 | Không dựa vào cột "Tình trạng" để suy ra code đã khớp v3.0; dùng §9 | Cập nhật cột tình trạng cho trung thực khi nộp GVHD |

Đã **đóng** nhờ docs v3.0: EX-10 (thay bằng Đẩy lùi 1 bậc), `lab_test_items` gắn `services` (`service_lab_test_items`), "mở lại visit" (T14: bác sĩ phụ trách hoặc Admin; chưa có hoàn tiền), FR-01 (5 vai trò), VAT (dữ liệu trên `vat_rate`).
Chưa làm / ngoài phạm vi (theo docs): SMS, in phiếu số phòng (UC-21 6.4)/phiếu hẹn (làm bằng `window.print()` nếu cần), quên mật khẩu tự phục vụ, hoàn tiền, quản lý ca kíp/nghỉ phép của bác sĩ ("luôn đi làm đầy đủ").

---

## 11. Lệnh chạy

> Cập nhật mục này mỗi khi `docker-compose.yml` hoặc `pom.xml` thay đổi.
> **Cảnh báo:** cho tới khi backend được di chuyển sang v3.0 (§9), `docker compose up` với `docs/schema_init.sql` mới sẽ làm service lỗi validate, và `e2e/*` không chạy được. Muốn chạy bản v2 để tham khảo thì phải dùng `schema_init.sql` v2 (không còn trong repo).

Lần đầu: `cp .env.example .env`, điền `JWT_SECRET` (`openssl rand -hex 32`); `.env` không commit.

Hai file ở gốc repo không thuộc `docs/`: `docker-compose.db.yml` (override chỉ để mở MySQL ra máy host ở cổng **3307** → 3306 container, dùng khi máy đã có MySQL chiếm 3306: `docker compose -f docker-compose.yml -f docker-compose.db.yml up -d`) và `update.md` (báo cáo rà soát BE/FE **thiết kế v2** ngày 29/10, kèm chuỗi lệnh dọn sạch container/volume/image: `docker compose down -v --rmi local --remove-orphans` → `docker builder prune -af` → `docker compose build --no-cache` → `up -d`). `update.md` chỉ để tham khảo, nội dung đối chiếu đã lỗi thời so với v3.0.

```bash
docker compose up -d --build               # Chạy toàn bộ hệ thống (NFR-08: 1 lệnh). Chỉ public :8080
docker compose ps                          # Mọi service phải "healthy"
docker compose down -v                     # RESET DB: bắt buộc mỗi khi sửa schema_init.sql

# Dev: hạ tầng trong Docker (mở 3306/5672/15672/8761/8025), service đang code chạy trong IDE
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mysql-server rabbitmq-broker mailpit service-registry

# Maven: máy để JAVA_HOME = JDK 17 nên phải trỏ sang JDK 21 (enforcer chặn nếu sai)
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"        # PowerShell: $env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.6"
cd vetclinic-backend && mvn -q clean install -DskipTests
mvn -pl clinical-service spring-boot:run                    # cần env JWT_SECRET cho gateway/auth/notification
mvn -pl billing-pharmacy-service test -Dtest=PharmacyServiceImplTest
```
**Nếu test báo `ClassFormatError: Invalid method Code length 0` hoặc Mockito "could not instrument":** file `.class` trong `target/` bị hỏng (constructor Lombok rỗng; nghi IDE tự build ghi đè vào `target/`). Chữa: `mvn clean install` lại; luôn dùng `clean` sau khi IDE vừa tự build. Docker biên dịch từ nguồn nên không bị.

Gateway http://localhost:8080 · (file dev) RabbitMQ UI :15672 (`guest/guest`), Eureka :8761, Mailpit :8025. Tài khoản seed: `admin` và 7 tài khoản demo (`bs_tien`, `bs_an`, `bs_mai`, `letan_hoa`, `ktv_lab_minh`, `ktv_cdha_dung`, `khach_lan`) đều dùng mật khẩu **`Admin@123`** (đăng nhập `admin` đã kiểm tra thật ngày 12/11 trên schema v3.0).

**Chạy dev trên máy này (đã kiểm chứng 12/11):** máy host có `mysqld` chiếm cổng 3306 nên MySQL của Docker phải ở **3307**: `MYSQL_HOST_PORT=3307 docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mysql-server rabbitmq-broker mailpit service-registry`; chạy service ngoài Docker với `DB_HOST=localhost DB_PORT=3307 DB_USERNAME=root DB_PASSWORD=<MYSQL_ROOT_PASSWORD> RABBITMQ_HOST=localhost TZ=UTC` + `JWT_SECRET` (từ `.env`) — **thiếu `DB_PORT=3307` là nối nhầm vào MySQL của máy host**. Maven phải dùng JDK 21 (`JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"`; `java -version` trong Git Bash vẫn in 22 do thứ tự PATH nhưng `mvn -v` đúng 21). Baseline trước khi sửa: **288 test pass** (auth 28, appointment 54, clinical 103, billing 68, notification 35).
**Giao diện:** `cd fe && npm install && npm start` (cổng 3000, `fe/.env`: `REACT_APP_API_URL=http://localhost:8080`); `npm run build` (phải "Compiled successfully", không cảnh báo lint); `npm test` (22 test lớp API/STOMP/định dạng, độc lập backend). Docker: `docker compose up -d --build frontend` → http://localhost:3000 (nginx, SPA fallback; địa chỉ Gateway nhúng lúc build qua `PUBLIC_API_URL`). Origin FE phải có trong `CORS_ALLOWED_ORIGINS` của Gateway (đổi biến thì **recreate** container gateway).

---

## 12. Quy chuẩn code bắt buộc (tóm tắt Quy chuẩn v3.0)

- Không trả Entity ra API; Request DTO luôn `@Valid`; MapStruct `componentModel = "spring"`, `unmappedTargetPolicy = WARN`.
- Tiền `BigDecimal` (cấm `double/float`); **không lưu tổng tiền/thành tiền/thuế/tồn sau giao dịch/nhãn đánh giá XN** (tính bằng VIEW hoặc code). Mốc giao dịch `Instant`. Enum `@Enumerated(EnumType.STRING)`, tên **khớp 100%** ENUM MySQL (lệch kiểu cột thì `@JdbcTypeCode(SqlTypes.ENUM)`/`columnDefinition`, không đổi sang VARCHAR).
- `spring.jpa.open-in-view: false`, `ddl-auto: validate` (không bao giờ `update/create`); schema chỉ đổi qua `schema_init.sql`. `hibernate.jdbc.batch_size: 50`.
- Phản hồi luôn `ApiResponse<T>` (`status, errorCode, message, data, timestamp`; thành công không có `errorCode`), phân trang `PageResponse<T>` (`items, page, size, totalElements, totalPages, last`). Tạo mới: `ResponseEntity.status(CREATED)` (header phải 201). Lỗi nghiệp vụ: `throw new AppException(ErrorCode.X[, message])`; không trả stacktrace.
- Chuyển trạng thái dùng `POST /{id}/<action>`, **không `PUT`** (ngoại lệ có trong docs: `PUT .../clinical-info`, `PUT .../medicine-invoices/{id}/items`, `PUT` đơn thuốc là sửa dữ liệu, không phải chuyển trạng thái). **Mỗi lần đổi trạng thái ghi bảng history tương ứng trong cùng transaction** (trừ phân công/đẩy lùi, vốn không đổi trạng thái).
- Feign: không gọi trong `@Transactional`, timeout 3000/5000, chỉ đọc. Hồ sơ từ `WAITING_PAYMENT` trở đi bị khóa. Không xóa cứng master đã có giao dịch (services, medicines, species): `is_active = false`.
- Mọi nghiệp vụ tiền/kho nằm trọn trong một service và một `@Transactional(rollbackFor = Exception.class)`. `@PreAuthorize` trên mọi endpoint nghiệp vụ; JWT claim `role` không tiền tố.

---

## 13. Cách làm việc trong repo

- Trước khi code một use case: đọc mục UC + State Machine + EX tương ứng trong docs v3.0, đối chiếu §4–§6 và task trong Lộ trình (có sẵn `curl` kiểm thử và mã lỗi kỳ vọng). Làm theo từng task của Lộ trình, tích `[x]` khi kiểm thử đạt.
- Sửa schema/enum: cập nhật **cùng lúc** `schema_init.sql`, `THIET_KE_CO_SO_DU_LIEU_ERD.md`, `DANH_SACH_KIEU_ENUM_HE_THONG.md`, rồi `down -v`. Thêm event mới: cập nhật bảng §6, Kiến trúc §5.2 và Quy chuẩn §9.2.
- Git (đã `git init` ngày 12/11/2026): `main` / `develop` / `feature/<service>-<uc>`; tag `v2-baseline` = trạng thái trước khi migrate; đang làm việc trên `develop`, mỗi bước của `LO_TRINH_SUA_CODE_V3.md` = 1 commit. PR vào `develop` tự rà theo 11 chương Quy chuẩn (trừ kho nguyên tử, event bắn sau commit, không trả Entity, đủ `@PreAuthorize`).
- Test tối thiểu cho luồng dễ lỗi: EX-15 (thiếu kho → rollback, sửa hóa đơn rồi xác nhận lại), EX-16 (bán/thu trùng → `409`, kể cả bấm đúp đồng thời), slot hết chỗ khi đặt đồng thời, `RESULTS_READY` khi hủy 1 trong 2 dịch vụ CLS (EX-08) và khi hai KTV hoàn tất cùng lúc, refresh token bị dùng lại, **Đẩy lùi 1 bậc** (đủ 3 trường hợp công thức `queue_order`, ca cấp cứu, không còn ai phía sau), ca có kết quả đứng đầu hàng đợi, thu hóa đơn cuối → `COMPLETED` cùng giao dịch, khách bỏ về → công nợ thu được ở lần sau.
- Ngôn ngữ: message lỗi và UI viết tiếng Việt có dấu; tên code, commit và log kỹ thuật viết tiếng Anh. Khi thêm vào CLAUDE.md, giữ nguyên nguyên tắc: tài liệu `docs/` v3.0 là đích, báo người dùng mọi chỗ làm khác docs.
