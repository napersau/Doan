# LỘ TRÌNH SỬA MÃ NGUỒN BACKEND TỪ v2 → v3.0 (SỬA TỪNG BƯỚC NHỎ)

> Lập ngày 11/11/2026 sau khi đọc toàn bộ `vetclinic-backend/` (≈13,1k dòng main, 288 `@Test`) và đối chiếu với `docs/` v3.0 + `CLAUDE.md`.
> Hạn nộp GVHD: **20/11/2026**. Còn 9 ngày làm việc (12–20/11).

---

## ▶ TRẠNG THÁI HIỆN TẠI (cập nhật sau MỖI phase/bước — Claude đọc mục này trước khi làm tiếp)

| Phase | Trạng thái | Ghi chú |
|---|---|---|
| **Phase 0** Chuẩn bị | ✅ **ĐÃ XONG** (12/11/2026) | Chi tiết ở mục Phase 0 bên dưới; kết quả đo thực tế ghi kèm từng bước |
| Phase 1 `common-library` | ⬜ chưa làm | **Bước kế tiếp: 1.1 (đánh số lại `ErrorCode`)** |
| Phase 2 `appointment-service` | ⬜ chưa làm | |
| Phase 3 `clinical-service` | ⬜ chưa làm | |
| Phase 4 `billing-pharmacy-service` | ⬜ chưa làm | |
| Phase 5 `notification-service` | ⬜ chưa làm | |
| Phase 6 Gateway + FE | ⬜ chưa làm | |
| Phase 7 Dọn dẹp + kiểm thử cuối | ⬜ chưa làm | |

**Quy ước:** `[x]` = đã làm **và đã kiểm tra**; `[ ]` = chưa làm. Phase chỉ đổi sang ✅ khi mọi bước của nó là `[x]`. Branch làm việc: `develop` (mốc quay lại: tag `v2-baseline`). Mỗi bước = 1 commit.

**Môi trường dev đã dùng (để tái lập):** JDK 21 (`export JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"`; máy mặc định là JDK 22); MySQL của Docker chạy ở cổng **3307** vì máy host đã có `mysqld` chiếm 3306 → bật hạ tầng bằng `MYSQL_HOST_PORT=3307 docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mysql-server rabbitmq-broker mailpit` và chạy service trong IDE/jar với `DB_PORT=3307 DB_HOST=localhost DB_USERNAME=root DB_PASSWORD=<MYSQL_ROOT_PASSWORD trong .env> RABBITMQ_HOST=localhost TZ=UTC` (+ `JWT_SECRET`). **Quên `DB_PORT=3307` thì service sẽ nối nhầm vào MySQL của máy host.**

---

## 0. Kết luận: KHÔNG viết lại từ đầu

| Module | Dòng main | Mức phải đụng | Ghi chú |
|---|---:|:---:|---|
| `auth-service` | 1.723 | **~0%** | Không có bảng nào đổi. 28 test đã pass. Chỉ cần dùng lại. |
| `service-registry`, `api-gateway` | 199 | ~5% | Chỉ thêm `GET /appointments/slots` vào whitelist. |
| `common-library` | 1.171 | ~15% | `ErrorCode` + bộ event. |
| `appointment-service` | 1.975 | ~20% | Bỏ 7 cột, đổi công thức, đổi tên event. Khung giờ/khóa/scheduler giữ nguyên. |
| `notification-service` | 1.009 | ~35% | Hạ tầng WebSocket/Mail giữ; đổi listener theo 9 sự kiện. |
| `clinical-service` | 5.072 | **~45%** | Danh mục, thú cưng, Lab, CĐHA, lưu ảnh, bệnh án, khóa: giữ. **Viết lại:** lõi hàng đợi/`VisitServiceImpl`, `DoctorStatus`, phần hàng đợi CLS; **thêm mới:** `service_invoices`. |
| `billing-pharmacy-service` | 3.111 | **~55%** | Thuốc, kho, specification, báo cáo kỳ: giữ. **Viết lại:** `Invoice*`, `InvoiceCalculator`, `dispense`, listener `visit.closed_to_payment`. |

Tổng phần phải viết lại/viết mới ≈ **35–40%** code, tập trung ở `clinical` và `billing`. Viết lại từ đầu sẽ làm mất các bài học đã tốn công sửa (JVM chạy UTC, Jackson 3, `NamedLockService` kết nối riêng, `HeaderAuthenticationFilter`, Feign error decoder, token rotation, magic-byte ảnh CĐHA, đua `RESULTS_READY`…) và không kịp 9 ngày.

---

## 1. Nguyên tắc sửa (để mỗi lần chỉ đụng ít file)

1. **Một bước = một nghiệp vụ nhỏ**, kết thúc bằng: `mvn -pl <module> -am clean test` xanh + chạy đúng lệnh `curl` của Task tương ứng trong `LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md`.
2. **Thêm trước, xóa sau.** Event/DTO mới thêm cạnh cái cũ; chỉ xóa cái cũ khi consumer cuối cùng đã chuyển (Phase 7).
3. **Để compiler dẫn đường:** xóa trường khỏi entity → sửa mọi chỗ báo đỏ → xong. Không tìm tay.
4. **Mỗi service chỉ chạy lại được khi *tất cả* entity của nó khớp schema** (`ddl-auto: validate`). Vì vậy mỗi service chia 2 khối: **Khối A – "khớp schema, chưa thêm nghiệp vụ mới"** rồi **Khối B – từng nghiệp vụ**.
5. **Chạy kiểu dev:** hạ tầng trong Docker (`docker-compose.dev.yml`), service đang sửa chạy trong IDE. Không chờ cả stack `healthy` (stack đầy đủ chỉ lên được khi xong Phase 6).
6. **`git init` + commit ngay trước khi sửa** (thư mục hiện chưa là git repo; `main`/`develop`/`feature/<service>-<uc>` theo CLAUDE.md §13). Mỗi bước = 1 commit.
7. **Test:** test của hành vi bị bỏ thì xóa; test còn lại sửa theo; chỉ viết test mới cho các luồng dễ lỗi (CLAUDE.md §13).

---

## 2. Thứ tự tổng thể và phân việc gợi ý

```
Phase 0  Chuẩn bị (git, schema fix, seed)            ── cả nhóm, 0,5 ngày
Phase 1  common-library                               ── Hiển, 0,5 ngày  (chặn mọi phase sau)
   ├─ Phase 2  appointment-service                    ── Hiển, 1 ngày
   ├─ Phase 3  clinical-service  (Khối A → Khối B)    ── Hiển + Khởi, 4–5 ngày  ← đường găng
   ├─ Phase 4  billing-pharmacy-service               ── Khởi, 2 ngày (làm song song Phase 3 sau bước 3.A)
   ├─ Phase 5  notification-service                   ── Khởi, 0,5 ngày (rải theo từng event mới)
   └─ Phase 6  Gateway + FE                           ── Linh, song song, bám API đã xong
Phase 7  Dọn code cũ + e2e + seed mở rộng             ── cả nhóm, 1 ngày
```

**Lịch đề xuất:** 12/11 Phase 0–1 · 12–13/11 Phase 2 + 3.A + 4.A · 13–16/11 Phase 3.B + 4.B + 5 · 16–17/11 Phase 7 · **17/11 đóng băng code** · 18–19/11 test E2E, chụp ảnh, quay video · 20/11 nộp.

**Ưu tiên khi thiếu thời gian:** P0 = luồng chuẩn Task 5.4 (đặt lịch → check-in → xếp ca → gọi số → khám → CLS → GĐ 2 → kê đơn → chốt → thu 530.000đ → bán thuốc 157.500đ). P1 = EX-03, 06, 08, 11, 14, 15, 16. P2 = báo cáo, mở lại hồ sơ T14, bệnh án trọn đời mở rộng.

---

## PHASE 0 — Chuẩn bị (không đụng logic)

> **PHASE 0 = ✅ ĐÃ XONG ngày 12/11/2026.**

- [x] **0.1** `git init`, commit nguyên trạng (729 file, không có `.env`), tag `v2-baseline`, tạo nhánh `develop`. Thêm `build/`, `__pycache__/` vào `.gitignore`.
- [x] **0.2** Sửa `docs/schema_init.sql`: thêm `SET NAMES utf8mb4;`; hash cũ xác nhận **không khớp** `Admin@123`, đã thay bằng hash BCrypt(12) mới (đã `checkpw`). → CLAUDE.md §10 #1 **đã đóng**.
- [x] **0.3** Tạo `docs/seed_demo_data.sql` (id 2–8 + thú cưng #1 Lu, #2 Milo; **mật khẩu chung mọi tài khoản demo = `Admin@123`**), mount vào `docker-compose.yml` làm `02-seed_demo_data.sql`. → CLAUDE.md §10 #2 **đã đóng** (phần ~100 dòng mở rộng vẫn là việc của Khởi, Phase 7).
- [x] **0.4** `down -v` (không có volume nào trước đó) → dựng hạ tầng → kiểm tra thật: **25 bảng (3+2+14+6), 2 view, 25 FK, tồn kho = SUM(quantity) (200/50/40/100), tiếng Việt lưu đúng**. Phát sinh 2 lỗi nền đã sửa: (a) healthcheck MySQL trong `docker-compose.yml` trỏ vào bảng `invoice_items` đã bỏ ở v3.0 → MySQL không bao giờ `healthy`; đổi sang kiểm tra bảng `pets` có ≥ 2 dòng (đợi cả schema lẫn seed); (b) cổng 3306 bị `mysqld` của máy host chiếm → `docker-compose.dev.yml` dùng biến `MYSQL_HOST_PORT` (mặc định 3306).
- [x] **0.5** `mvn clean install -DskipTests` (JDK 21): **BUILD SUCCESS** 8/8 module (~2 phút). Baseline `mvn test`: **288 test, 0 lỗi** (auth 28 · appointment 54 · clinical 103 · billing 68 · notification 35).
- [x] **0.6** Chạy riêng `auth-service` (jar v2, không sửa code) trên schema v3.0: khởi động ~28 giây, `POST /api/v1/auth/login` `admin`/`Admin@123` → `200`, có access + refresh token. **Phương pháp sửa dần được xác nhận.**

---

## PHASE 1 — `common-library` (làm một lần, các module khác dùng chung)

- [ ] **1.1 Đánh số lại `ErrorCode` theo Quy chuẩn §5.1** (một commit, vì mã trùng số nhưng khác nghĩa):

  | Mã | Code hiện tại | Theo docs v3.0 | Việc |
  |---|---|---|---|
  | `APPT_1205` | `SLOT_BUSY` | `APPOINTMENT_NOT_CHECKABLE` | `SLOT_BUSY` → `APPT_1209`; thêm `APPOINTMENT_NOT_CHECKABLE` (nơi đang dùng `CLIN_1307 APPOINTMENT_NOT_READY_FOR_CHECKIN` chuyển sang mã này) |
  | `CLIN_1303` | `DOCTOR_PRIORITY_LOCKED` | `QUEUE_EMPTY` | đổi nghĩa |
  | `CLIN_1306` | `VISIT_ALREADY_ACTIVE` | `PET_HAS_OPEN_VISIT` | đổi tên |
  | `CLIN_1307` | `APPOINTMENT_NOT_READY_FOR_CHECKIN` | `DIAGNOSIS_REQUIRED` | đổi nghĩa (hiện `DIAGNOSIS_REQUIRED` ở `CLIN_1312`) |
  | `CLIN_1308` | `VISIT_NOT_ASSIGNED_TO_YOU` | `VITAL_SIGNS_REQUIRED` | đổi nghĩa |
  | `CLIN_1309` | `SERVICE_NOT_FOUND` | `IN_ROOM_SERVICE_PENDING` | đổi nghĩa; `SERVICE_NOT_FOUND` → số mới ≥ 1312 |
  | `CLIN_1310` | `SERVICE_DETAIL_NOT_FOUND` | `PAID_SERVICE_CANNOT_BE_CANCELLED` | đổi nghĩa; mã cũ → số mới |
  | `CLIN_1311` | `INVALID_SERVICE_DETAIL_STATUS` | `NOT_ASSIGNED_DOCTOR` | đổi nghĩa; mã cũ → số mới |
  | `BILL_1402` | `DISCOUNT_EXCEEDS_TOTAL` | `DISCOUNT_EXCEEDS_INVOICE_AMOUNT` | đổi tên |
  | `BILL_1408/1409` | *(chưa có; `BILL_1413–1415` khác)* | `INVOICE_ALREADY_CANCELLED`, `VISIT_NOT_READY_FOR_PAYMENT` | thêm; `BILL_1415 VISIT_NOT_READY_FOR_INVOICE` → thay bằng `1409` |
  | `PHARM_1407` | `PRESCRIPTION_ALREADY_DISPENSED` | `PRESCRIPTION_ALREADY_INVOICED` | đổi nghĩa |
  | `PHARM_1408` | `PRESCRIPTION_ALREADY_EXISTS` | *(không có trong docs)* | chuyển sang số mới |
  | `PHARM_1409` | `PRESCRIPTION_LOCKED` | → `PHARM_1411` | đổi số |
  | `PHARM_1410` | `INVALID_PRESCRIPTION_STATUS` | `PURCHASE_QUANTITY_EXCEEDS_PRESCRIBED` | đổi nghĩa |
  | `PHARM_1411` | `MEDICINE_CODE_ALREADY_EXISTS` | `PRESCRIPTION_LOCKED` | mã thuốc trùng → số mới |

  Quy tắc: số của docs thắng; mã chỉ có trong code thì dời xuống số tiếp theo trong nhóm (CLAUDE.md §5.1). Báo FE (Linh) bảng này ngay.
- [ ] **1.2 Thêm event mới, CHƯA xóa event cũ:** `AppointmentBookedEvent`, `AppointmentNoShowEvent`, `PatientCalledEvent`, `ParaclinicalResultsReadyEvent`, `VisitCompletedEvent`, `MedicineInvoicePaidEvent`, `MedicineLowStockEvent` + routing key trong `EventRouting` (`appointment.booked`, `appointment.no_show`, `visit.patient_called`, `visit.results_ready`, `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`). `VisitCheckedInEvent` và `AppointmentStatusChangedEvent` giữ, chỉ thêm trường nếu cần (`doctorId`).
- [ ] **1.3** Đổi `InventoryTransactionType`/`PaymentStatus` nằm ở billing (xem 4.A), không ở common.
- **Xong khi:** `mvn -q clean install -DskipTests` xanh; test của `auth-service` vẫn xanh (nếu đỏ do đổi mã lỗi thì sửa test).

---

## PHASE 2 — `appointment-service` (nhỏ nhất, làm trước để rút kinh nghiệm)

### Khối A — khớp schema (`appointments` còn 9 cột)
- [ ] **2.A1** `Appointment`: bỏ `endTime`, `ownerId`, `cancelReason`, `confirmedBy/At`, `cancelledBy/At`, `updatedAt`. Sửa chỗ báo đỏ (chủ yếu `AppointmentServiceImpl`, `AppointmentMapper`, `AppointmentResponse`).
- [ ] **2.A2** `transition()`/`recordHistory()` ghi **lý do hủy vào `notes`**, người thao tác vào `changed_by_user_id` (bảng lịch sử đã có sẵn 2 trường này). Response lấy `cancelReason`, `confirmedBy`… từ lịch sử (`AppointmentEnricher`).
- [ ] **2.A3** `endTime` suy ra = `startTime + 30'` (hằng từ `BookingProperties`), chỉ khi cần hiển thị.
- [ ] **2.A4** `ownerId`: tạm thời lấy qua `PetClient.getPet(petId).ownerId` (đã có endpoint `/internal/clinical/pets/{id}`); bộ lọc "lịch của tôi" cần `GET /internal/clinical/pets?ownerId=` → **làm cùng bước 3.A5** (bên clinical). Đến khi đó, `AppointmentSpecifications` lọc theo `petId IN (...)`.
- **Xong khi:** service khởi động trên schema mới; test sửa xong; đặt lịch + duyệt + hủy bằng `curl` (Task 2.7).

### Khối B — nghiệp vụ mới
- [ ] **2.B1 Sức chứa `N_active × 2`** (`SlotPolicy`/`SlotService`) — sửa 1 chỗ + test.
- [ ] **2.B2 NO_SHOW = `appointment_date + start_time + 30'`** (`AppointmentServiceImpl.isNoShowDue`, `AppointmentNoShowScheduler`), cron `0 */5 7-21 * * *`, phát `AppointmentNoShowEvent`.
- [ ] **2.B3** Đổi sự kiện tạo lịch sang `AppointmentBookedEvent` (`appointment.booked`). Thêm listener tương ứng ở notification (bước 5.1) rồi mới xóa `AppointmentCreatedEvent`.
- [ ] **2.B4** `GET /appointments/slots` public ở service (`vetclinic.security.public-paths`) — Gateway làm ở 6.1.
- [ ] **2.B5** Consumer `VisitCheckedInEvent`: giữ; thêm log cảnh báo nếu lịch đã `NO_SHOW`.
- [ ] **2.B6** Chỉ để ý: `reschedule`, `expirePendingBySystem`, `reports/summary` giữ nguyên (CLAUDE.md §10 #8).

---

## PHASE 3 — `clinical-service` (đường găng: chia thật nhỏ)

### Khối A — cho service chạy lại trên schema mới (chủ yếu cơ học)

- [ ] **3.A1 Thú cưng / xét nghiệm:** `Pet` bỏ `weight`; `LabTestResult` bỏ `evaluation` → tính `LabEvaluation` trong `VisitAssembler`/`LabResultResponse` từ `resultValue` so `min/maxValueSnapshot`. (~5 file)
- [ ] **3.A2 Lịch sử:** `PetVisitStatusHistory.changedByUserId` cho phép `null`. Thêm tiện ích `VisitTimeline` (1 query lịch sử) để lấy "thời điểm bắt đầu khám / chốt / hoàn tất / lý do hủy" thay cho `startedAt/closedAt/completedAt/cancelReason`.
- [ ] **3.A3 `PetVisit`:** bỏ `assignedRoom` (phòng lấy từ `AuthClient` theo bác sĩ), `startedAt`, `closedAt`, `completedAt`, `cancelReason`, `scheduledAt`, `callbackPostponedAt`; thêm `queueOrder BIGINT`, `skipCount INT`. Thêm `QueueOrders.now()` (epoch micro giây) và gán trong `createVisit`.
  - Xóa: `QueueSchedule`, `QueueState`, `DoctorStatus.AVAILABLE_PRIORITY_CALLBACK`, `callBack()`/`postponeCallback()` và 2 endpoint tương ứng, `existsByDoctorIdAndStatusAndCallbackPostponedAtIsNull`, `PaymentCompletedListener`.
  - `findQueueByStatus` đổi thành `ORDER BY (emergency AND skipCount = 0) DESC, queueOrder ASC`.
- [ ] **3.A4 `VisitServiceDetail`:** bỏ `unitPrice`, `subtotal`; thêm `queueOrder`, `skipCount` (gán ở `createDetail`). `ClinicService` thêm `vatRate`.
- [ ] **3.A5 Entity `ServiceInvoice`** (+ repository, mã `SINV-YYYY-XXXX` từ id, `@Version`) và gắn vào `createDetail`: **tạo chỉ định ⇒ tạo hóa đơn `PENDING` trong cùng giao dịch** (snapshot `unit_price = services.price`, `vat_rate`). Thêm `GET /internal/clinical/pets?ownerId=` (cho 2.A4).
- [ ] **3.A6** `billableServices` (đang đọc `unitPrice`) tạm trả từ `services.price`; sẽ xóa ở 3.B13.
- **Xong khi:** `mvn -pl clinical-service test` xanh (xóa/sửa test của hành vi đã bỏ), **service khởi động**, `POST /pet-visits/check-in` chạy (Task 3.2 bước 1).

### Khối B — từng nghiệp vụ (mỗi bước có `curl` ở Lộ trình)

| Bước | Nghiệp vụ | Việc chính | Kiểm thử |
|---|---|---|---|
| [ ] **3.B1** | Check-in + hai danh sách | `CheckInRequest` thêm `doctorId?`; chặn `CLIN_1306`; `GET /pet-visits/queue` (chờ phân công + hàng đợi từng bác sĩ + `waitingCount` + `suggestedDoctorId`), `GET /my-queue` | Task 3.2 |
| [ ] **3.B2** | Phân công | đổi `/assign` → `/assign-doctor` (không kiểm tra bác sĩ rảnh, giữ `queue_order`, kiểm `version`), thêm `/unassign-doctor`; `doctors/status` chỉ `BUSY`/`AVAILABLE` | Task 3.3.1–3.3.2b |
| [ ] **3.B3** | Gọi số + Đẩy lùi (phòng khám) | `POST /call-next` (`CLIN_1302`/`CLIN_1303`, `PatientCalledEvent`), `POST /{id}/skip` — công thức Q4 (3 trường hợp), dùng `@Version` | Task 3.3.2c–d; test đủ 3 trường hợp công thức |
| [ ] **3.B4** | Bắt đầu khám (T02) | kiểm tra đúng bác sĩ được gán (`CLIN_1311`), không có ca `IN_PROGRESS`; tự tạo dịch vụ `EXAM_GEN` + hóa đơn; `PUT /{id}/clinical-info` | Task 3.3.3–3.3.4 |
| [ ] **3.B5** | Chỉ định CLS (T04) + dịch vụ tại phòng | `paraclinical-orders`: kiểm `weight/temperature` (`CLIN_1308`), dịch vụ tại phòng xong (`CLIN_1309`); `in-room-services`; `service-details/{id}/perform`; ca → `WAITING_RESULTS` | Task 3.4 |
| [ ] **3.B6** | Hàng đợi Lab/CĐHA | `lab-queue`/`imaging-queue` sắp theo `queue_order`; `call-next`, `service-details/{id}/skip` (`action=SKIPPED`), `start`, `sample-collected`, huy hiệu liên phòng `labStatus`; **xóa** `postpone`/`recall`/tab hoãn | Task 3.5, 3.6 |
| [ ] **3.B7** | Kết quả CLS | Lab: bắt buộc đủ chỉ số của gói (`service_lab_test_items`); CĐHA multipart giữ nguyên | Task 3.5, 3.6 |
| [ ] **3.B8** | Hủy dịch vụ CLS (EX-08) | hủy dịch vụ ⇒ hóa đơn `CANCELLED` cùng giao dịch; `CLIN_1310` nếu hóa đơn đã `PAID` | Task 3.5/EX-08 |
| [ ] **3.B9** | `WAITING_RESULTS → RESULTS_READY` (T06, Q5) | **khóa dòng `pet_visits` trước rồi mới đếm** (bài học đua); đặt `queue_order = MIN − 1`, `skip_count = 0`, history `changed_by = NULL`; phát `ParaclinicalResultsReadyEvent` | Task 3.7 |
| [ ] **3.B10** | GĐ 2 + chốt ca | `start-phase2` (T07); `close-visit` (T05/T09): chỉ validate, ghi lịch sử, **bỏ** gọi billing và `VisitClosedToPaymentEvent` | Task 3.8 |
| [ ] **3.B11** | Thoát ca | `abandon` (T08/T12: hủy CLS `PENDING` kèm hóa đơn, giữ công nợ), `cancel-exam` (T11, guard chưa có dịch vụ tại phòng `COMPLETED`), `cancel` (T03), `reopen` (T14: bác sĩ **hoặc ADMIN**, bỏ Feign hủy hóa đơn billing) | Task 3.8, EX-03/11 |
| [ ] **3.B12** | Thu tiền dịch vụ (T10) | `GET /{id}/service-invoices`, `POST /service-invoices/{id}/discount` (`BILL_1402`), `POST /{id}/service-invoices/confirm-payment {invoiceIds}`: `PAID` + nếu hết `PENDING` thì `COMPLETED` **cùng giao dịch**; `GET /with-debt`; phát `VisitCompletedEvent` | Task 3.9, EX-14/16 |
| [ ] **3.B13** | API nội bộ | thêm `/internal/pet-visits/{id}/summary`; **xóa** `/visits/{id}/billable-services`; thu gọn `BillingClient` | Task 4.2 |
| [ ] **3.B14** | Báo cáo | doanh thu dịch vụ (nhóm theo `services.category`) + công nợ từ `service_invoices`; vận hành giữ | FR-69/70 |

---

## PHASE 4 — `billing-pharmacy-service`

### Khối A — khớp schema
- [ ] **4.A1** `Medicine` thêm `vatRate`. `Prescription` bỏ `status`, `dispensedBy/At`. `PrescriptionItem` bỏ `unitPrice`, `subtotal`. `InventoryTransaction` bỏ `balanceAfter`, `referenceId`, thêm `medicineInvoiceId`; `InventoryTransactionType`: `EXPORT_PRESCRIPTION → EXPORT_SALE`; `quantity` có dấu.
- [ ] **4.A2** Xóa `Invoice`, `InvoiceItem`, `InvoiceItemType`, `PrescriptionStatus`, `InvoiceCalculator`, `InvoiceServiceImpl`, `InvoiceController`, `VisitClosedListener`, `prescriptions/{id}/dispense`, `removeFromInvoice`, `cancelForReopen`. Thêm entity `MedicineInvoice` (`@Version`) + `MedicineInvoiceItem` + repository.
- **Xong khi:** service khởi động trên schema mới; test cũ của phần đã xóa bị loại.

### Khối B
| Bước | Nghiệp vụ | Việc chính | Kiểm thử |
|---|---|---|---|
| [ ] **4.B1** | Thuốc + kho | CRUD thêm `vat_rate`; `import`/`adjust` ghi `quantity` có dấu, giữ bất biến `stock = SUM(quantity)` | Task 4.1 |
| [ ] **4.B2** | Đơn thuốc | không giá/không trạng thái; trả `stockWarnings` thay vì chặn (EX-13); khóa theo `/internal/pet-visits/{id}/summary` (`PHARM_1411`); `print` | Task 4.2 |
| [ ] **4.B3** | Lập hóa đơn thuốc | `POST /prescriptions/{id}/medicine-invoices`: chỉ khi ca `WAITING_PAYMENT`/`COMPLETED`; `PHARM_1407`, `PHARM_1410`; snapshot giá/VAT; mã `MINV-` | Task 4.3 |
| [ ] **4.B4** | Sửa/hủy | `PUT /medicine-invoices/{id}/items` (`quantity = 0` bỏ dòng, còn ≥ 1 dòng); `POST …/cancel` (hủy là kết thúc) | Task 4.3.4 |
| [ ] **4.B5** | Thu tiền + trừ kho | **một `@Transactional`**: sắp theo `medicineId` → `UPDATE … WHERE stock >= :qty` → `inventory_transactions EXPORT_SALE` → `PAID`; thiếu kho ⇒ rollback toàn bộ (`PHARM_1406`); phát `MedicineInvoicePaidEvent` + `MedicineLowStockEvent`. **Giữ lại logic trừ kho hiện có**, chỉ đổi nguồn dữ liệu | Task 4.4, EX-15/16 |
| [ ] **4.B6** | Báo cáo | doanh thu thuốc từ `medicine_invoices` `PAID` (tái dùng `RevenuePeriods`) | FR-69 |

---

## PHASE 5 — `notification-service` (làm rải, mỗi lần một event)

- [ ] **5.1** Thêm listener cho `appointment.booked` → (xóa `appointment.created` ở 7.1).
- [ ] **5.2** `appointment.no_show`, `visit.patient_called` (gọi số: phòng + STT; báo bị đẩy lùi), `visit.results_ready`.
- [ ] **5.3** `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`.
- [ ] **5.4** `POST /notifications/{id}/read`; **bỏ** `POST /notifications/send-email`.
- [ ] **5.5** Cập nhật `NotificationType`; chưa làm `/topic/lobby` (CLAUDE.md §10 #6).

---

## PHASE 6 — Gateway + Frontend (Linh, song song)

- [ ] **6.1** Gateway: thêm `GET /api/v1/appointments/slots` vào `vetclinic.gateway.public-paths`.
- [ ] **6.2** FE theo thứ tự backend hoàn thành (không chờ hết): ① bảng mã lỗi (`lib/labels.js`) + `api/endpoints.js` → ② Lễ tân: hàng đợi/điều phối (sau 3.B1–2) → ③ Bác sĩ: hàng đợi riêng, Gọi số/Đẩy lùi (3.B3–4) → ④ chỉ định, dịch vụ tại phòng (3.B5) → ⑤ KTV Lab/CĐHA (3.B6–7) → ⑥ Thu ngân hóa đơn dịch vụ (3.B12) → ⑦ Bán thuốc (4.B3–5) → ⑧ báo cáo, kho.

---

## PHASE 7 — Dọn dẹp và kiểm thử cuối

- [ ] **7.1** Xóa event cũ khỏi `common-library` và listener cũ ở `notification` (`appointment.created`, `doctor.priority_callback`, `visit.closed_to_payment`, `payment.completed`, `stock.low_alert`).
- [ ] **7.2** `mvn clean install` toàn bộ; `docker compose down -v && docker compose up -d --build` → mọi service `healthy`.
- [ ] **7.3** Chạy luồng Task 5.4 (9 bước) trên trình duyệt; đối chiếu `v_service_invoice_amounts` = 530.000 và `v_medicine_invoice_amounts` = 157.500.
- [ ] **7.4** Test các luồng dễ lỗi: EX-15, EX-16, đua `RESULTS_READY`, đặt lịch đồng thời, refresh token tái sử dụng, 3 trường hợp công thức Đẩy lùi, ca có kết quả đứng đầu, thu hóa đơn cuối ⇒ `COMPLETED`, công nợ.
- [ ] **7.5** Viết lại `e2e/` ngắn gọn theo Task 5.4–5.5 (script cũ nhắm v2, bỏ).
- [ ] **7.6** Cập nhật `CLAUDE.md` §9 (đánh dấu phần đã xong), §11.

---

## Rủi ro cần biết

1. **Đường găng là `clinical-service`** (3.A + 3.B1–B12 ≈ 15 bước nhỏ). Nếu trễ, cắt P2 trước (3.B14, `reopen`), không cắt Khối A.
2. **Mã lỗi đổi (1.1)** làm vỡ test và FE cùng lúc: làm sớm, báo Linh trước khi bắt đầu Phase 6.
3. **Cột Tình trạng trong Bảng tổng hợp** ghi Tuần 8–11 "ĐÃ XONG" nhưng code mới là v2 — cập nhật cho trung thực trước khi nộp (CLAUDE.md §10 #16).
4. **Chưa có git** — làm 0.1 trước mọi thứ khác.
