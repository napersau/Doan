# LỘ TRÌNH SỬA MÃ NGUỒN BACKEND TỪ v2 → v3.0 (SỬA TỪNG BƯỚC NHỎ)

> Lập ngày 11/11/2026 sau khi đọc toàn bộ `vetclinic-backend/` (≈13,1k dòng main, 288 `@Test`) và đối chiếu với `docs/` v3.0 + `CLAUDE.md`.
> Hạn nộp GVHD: **20/11/2026**. Còn 9 ngày làm việc (12–20/11).

---

## ▶ TRẠNG THÁI HIỆN TẠI (cập nhật sau MỖI phase/bước — Claude đọc mục này trước khi làm tiếp)

| Phase | Trạng thái | Ghi chú |
|---|---|---|
| **Phase 0** Chuẩn bị | ✅ **ĐÃ XONG** (12/11/2026) | Chi tiết ở mục Phase 0 bên dưới; kết quả đo thực tế ghi kèm từng bước |
| **Phase 1** `common-library` | ✅ **ĐÃ XONG** (12/11/2026) | 1.1 đánh số lại `ErrorCode`, 1.2 thêm 7 event mới + 9 routing key chuẩn; 288 test vẫn pass |
| **Phase 2** `appointment-service` | ✅ **ĐÃ XONG** (12/11/2026) | Khối A + B xong; 68 test pass + chạy thật trên schema v3.0 |
| Phase 3 `clinical-service` | ✅ **ĐÃ XONG (12/11/2026)** — Khối A + Khối B (3.B1–3.B14); 183 unit test clinical; **đã chạy kiểm chứng thật trên MySQL** (luồng chính + đẩy lùi Lab + T11/T12/T14 + thu tiền T10 + công nợ + báo cáo doanh thu: 100% đạt, phát hiện và sửa 1 lỗi lazy ở `skip` phòng Lab) | |
| Phase 4 `billing-pharmacy-service` | ✅ **ĐÃ XONG (12/11/2026)** — Khối A + Khối B (4.B1–4.B6); 56 unit test billing (+183 clinical vẫn đạt); **đã chạy kiểm chứng thật trên MySQL** (kê đơn + `stockWarnings`, khóa đơn `PHARM_1411`, lập/sửa/hủy hóa đơn thuốc, thu tiền + trừ kho nguyên tử, EX-15 rollback rồi sửa hóa đơn thu lại, EX-16 kể cả 3 yêu cầu song song, nhập/cân chỉnh kho, mở lại hồ sơ T14, báo cáo doanh thu thuốc, bệnh án ghép đơn: 65/65 đạt) | |
| Phase 5 `notification-service` | ✅ **ĐÃ XONG (12/11/2026)** — 5.1–5.5; 45 unit test notification (+ appointment 68, clinical 183, billing 56 vẫn đạt); **đã chạy kiểm chứng thật** với RabbitMQ + Mailpit + 5 service (gọi số / đẩy lùi / có kết quả / hoàn tất / bán thuốc / tồn kho thấp / đặt lịch / duyệt / vắng mặt, 5 loại email, `POST /{id}/read`, 9 queue + DLQ rỗng) | **Bước kế tiếp: Phase 6 (Gateway + Frontend)** |
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

> **PHASE 1 = ✅ ĐÃ XONG ngày 12/11/2026.** Kết quả thực tế ghi ở cuối mục Phase 1. Báo Linh: FE hiện **không** tham chiếu các mã đã đổi (tìm `fe/src` = 0 chỗ), chỉ `e2e/*` (v2, bị bỏ ở 7.5) có.

- [x] **1.1 Đánh số lại `ErrorCode` theo Quy chuẩn §5.1** (một commit, vì mã trùng số nhưng khác nghĩa) — **đã làm**, bảng dưới là kế hoạch ban đầu; thực tế: mã v2-only được giữ tạm ở số mới (`DOCTOR_PRIORITY_LOCKED`→`CLIN_1323`, `INVALID_PRESCRIPTION_STATUS`→`PHARM_1415`, `BILL_1413–1415` giữ nguyên) để code v2 còn biên dịch; `CLIN_1312` bỏ trống có chủ ý; **xóa các mã v2-only ở 3.A3 / 4.A2**:

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
- [x] **1.2 Thêm event mới, CHƯA xóa event cũ** — **đã làm** (7 record + 9 routing key, khớp Kiến trúc §5.2; `VisitCheckedInEvent` **chưa** thêm `doctorId`: làm ở 3.B1 khi cần để khỏi vỡ constructor sớm; event/key v2 còn nguyên, xóa ở 7.1): `AppointmentBookedEvent`, `AppointmentNoShowEvent`, `PatientCalledEvent`, `ParaclinicalResultsReadyEvent`, `VisitCompletedEvent`, `MedicineInvoicePaidEvent`, `MedicineLowStockEvent` + routing key trong `EventRouting` (`appointment.booked`, `appointment.no_show`, `visit.patient_called`, `visit.results_ready`, `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`). `VisitCheckedInEvent` và `AppointmentStatusChangedEvent` giữ, chỉ thêm trường nếu cần (`doctorId`).
- [x] **1.3** Đổi `InventoryTransactionType`/`PaymentStatus` nằm ở billing (xem 4.A), không ở common. *(không có việc ở Phase 1)*
- **Kết quả đo:** `mvn clean test` 8/8 module BUILD SUCCESS, **288 test, 0 lỗi**; không có 2 mã nào trùng chuỗi. Lưu ý kỹ thuật: build gia tăng (`mvn test` không `clean`) từng gây `NoClassDefFoundError: LabTestItem` ở clinical → **luôn dùng `mvn clean test`**.
- **Xong khi:** `mvn -q clean install -DskipTests` xanh; test của `auth-service` vẫn xanh (nếu đỏ do đổi mã lỗi thì sửa test).

---

## PHASE 2 — `appointment-service` (nhỏ nhất, làm trước để rút kinh nghiệm)

### Khối A — khớp schema (`appointments` còn 9 cột)
- [x] **2.A1** `Appointment` còn đúng 9 cột (bỏ `endTime`, `ownerId`, `cancelReason`, `confirmedBy/At`, `cancelledBy/At`, `updatedAt`). `ddl-auto: validate` đạt trên schema v3.0.
- [x] **2.A2** Lý do hủy ghi **nguyên văn** vào `appointment_status_histories.notes`, người thao tác vào `changed_by_user_id` (`null` = hệ thống). `AppointmentEnricher` suy ra `cancelReason`/`confirmedBy/At`/`cancelledBy/At`/`updatedAt` từ lịch sử bằng **một** truy vấn theo lô (`findByAppointmentIdInOrderByIdAsc`); hình dạng `AppointmentResponse` giữ nguyên nên FE và clinical không phải đổi. Controller `enrich` MỌI response (create/confirm/cancel/reschedule/no-show).
- [x] **2.A3** `endTime` suy ra = `startTime + slotMinutes` (`SlotPolicy.endOf`); repository `findIdsEndedBefore` → `findIdsStartedBefore` (lọc theo giờ bắt đầu; hai scheduler tự trừ độ dài khung/thời gian chờ). Hành vi v2 giữ nguyên (hạn NO_SHOW còn là *kết thúc khung + 30 phút*; đổi sang *bắt đầu + 30 phút* ở 2.B2).
- [x] **2.A4** `ownerId` suy ra từ thú cưng qua `PetClient` — **luôn ngoài transaction** (các hàm `confirm/cancel/reschedule/markNoShow/*BySystem` đổi sang `transactionTemplate`, tra chủ nuôi trước). Chủ nuôi (PET_OWNER) tra không được ⇒ **đóng cửa** (404 `APPT_1202` / 503), nhân viên tra không được ⇒ chỉ mất thông báo (CLAUDE.md §10 #3). "Lịch của tôi"/lọc theo chủ = `pet_id IN (...)` qua `GET /internal/clinical/pets?ownerId=`; tra không được ⇒ danh sách rỗng, **không bao giờ bỏ điều kiện lọc**. **Endpoint phía clinical đã làm luôn ở bước này** (`PetService.getSummariesByOwner`, `PetRepository.findByOwnerId`, `@GetMapping(params="ownerId")`) nên 3.A5 không cần làm lại phần đó.
- **Kết quả đo Khối A (12/11):** `mvn clean test`: appointment **63 test** (54 cũ + 9 mới), clinical **106** (103 + 3 mới), 0 lỗi. **Chạy thật** appointment-service (jar) trên MySQL schema v3.0 + auth-service thật + clinical *giả* (Python, đúng hợp đồng `/internal/clinical/pets…` vì clinical thật chưa boot được trên schema mới): đặt lịch `201` (ownerId 8 suy ra từ thú cưng, endTime 09:30, tên chủ từ auth) → lễ tân duyệt (`confirmedBy` 5 từ lịch sử) → "lịch của tôi" đúng 2 lịch → chủ nuôi khác thấy 0 lịch dù gửi `ownerId=8`, xem/hủy lịch người khác = `404 APPT_1202` → chủ nuôi hủy kèm lý do (`cancelReason`, `cancelledBy` từ lịch sử) → lịch sử đúng 3 dòng. Không có ERROR trong log. Dữ liệu thử đã xóa khỏi DB. *(Lưu ý kiểm thử: `curl` trên Git Bash/Windows làm hỏng byte tiếng Việt trong body → 400 `SYS_1001`; dùng ASCII khi test bằng `curl`.)*

### Khối B — nghiệp vụ mới
- [x] **2.B1 Sức chứa `N_active × 2`** — `SlotPolicy.APPOINTMENTS_PER_DOCTOR_PER_SLOT = 2` (hằng có tên, kèm lý do k=2). 3 bác sĩ ACTIVE ⇒ 6 chỗ/khung (đã đo thật qua `/slots`). Có test riêng: 1 bác sĩ ⇒ nhận lịch thứ 2, từ chối lịch thứ 3.
- [x] **2.B2 NO_SHOW = `appointment_date + start_time + 30 phút`** (trước là *giờ kết thúc khung + 30'*); cron mặc định `0 */5 7-21 * * *`; phát `AppointmentNoShowEvent` (`appointment.no_show`). Test phân biệt hai công thức (hẹn 08:00, bây giờ 09:00: v3 quá hạn, v2 chưa). Đã đo thật bằng scheduler cron 10 giây: lịch 08:00 ⇒ `NO_SHOW` (người thao tác `NULL`), lịch 11:30 vẫn `CONFIRMED`. **Lưu ý:** hiện phát SONG SONG với `appointment.status_changed` (NO_SHOW) — xem bước 5.2.
- [x] **2.B3** Phát `AppointmentBookedEvent` (`appointment.booked`) **song song** với `AppointmentCreatedEvent` (notification vẫn đang nghe `appointment.created`). Đã đo thật: một lần đặt lịch phát đủ hai event. **Còn lại (làm ở 5.1 / 7.1):** notification nghe `appointment.booked`, rồi xóa `AppointmentCreatedEvent`.
- [x] **2.B4** `GET /appointments/slots` công khai **ở phía appointment-service** (`vetclinic.security.public-paths` + `@PreAuthorize("permitAll()")`): đã đo thật `200` không cần header, trong khi `GET /appointments` không header vẫn `401`. **Chưa công khai qua Gateway** — làm ở 6.1.
- [x] **2.B5** Consumer `VisitCheckedInEvent` giữ nguyên (idempotent); thêm nhánh `NO_SHOW`: log cảnh báo *giữ nguyên NO_SHOW, khách được tiếp nhận như vãng lai* (FR-18), không ghi gì; có test.
- [x] **2.B6** `reschedule`, `expirePendingBySystem`, `reports/summary` giữ nguyên hành vi (CLAUDE.md §10 #8). `expirePendingBySystem` chạy đúng khi đo thật (PENDING quá hạn ⇒ `CANCELLED`, người thao tác `NULL`).

---

## PHASE 3 — `clinical-service` (đường găng: chia thật nhỏ)

### Khối A — cho service chạy lại trên schema mới (chủ yếu cơ học)

- [x] **3.A1 Thú cưng / xét nghiệm** — `Pet` bỏ `weight` (và bỏ `weight` khỏi `CreatePetRequest`/`UpdatePetRequest`); `PetResponse.weight` giữ nhưng được **suy ra** = `weight_at_visit` của đợt khám gần nhất có cân (một truy vấn theo lô `findLatestWeights`). `LabTestResult` bỏ `evaluation`; `LabEvaluation.of(value, min, max)` tính khi hiển thị trong `VisitAssembler`.
- [x] **3.A2 Lịch sử** — `PetVisitStatusHistory.changed_by_user_id` cho phép `null`; thêm `VisitTimeline` (một truy vấn lịch sử theo lô) suy ra `startedAt`/`closedAt`/`completedAt`/`cancelReason`: bắt đầu = lần vào `IN_PROGRESS` đầu tiên; chốt = lần `WAITING_PAYMENT` mới nhất (ca mở lại thì `null`); hoàn tất = lần `COMPLETED`; lý do hủy = `notes` dòng `CANCELLED`.
- [x] **3.A3 `PetVisit`** — bỏ `assignedRoom`, `startedAt`, `closedAt`, `completedAt`, `cancelReason`, `scheduledAt`, `callbackPostponedAt`; thêm `queueOrder`, `skipCount`; `QueueOrders.of/now` (epoch micro giây) gán ở `createVisit`. **Số phòng** do `VisitEnricher` (Feign `auth`, theo lô, ngoài transaction) gắn ở controller. `findQueueByStatus`/`findDoctorVisits` sắp theo `(emergency AND skipCount = 0) DESC, queueOrder ASC`.
  - **Đã xóa:** `QueueSchedule`+test, `QueueState`, `DoctorStatus.AVAILABLE_PRIORITY_CALLBACK`, `ErrorCode.DOCTOR_PRIORITY_LOCKED`, `callBack()`/`postponeCallback()` + endpoint `call-back`/`postpone-callback`, `completeAfterPayment()`, `PaymentCompletedListener`+`ClinicalRabbitConfig`, truy vấn `...CallbackPostponedAt...`. **Xóa thêm so với kế hoạch ban đầu:** `postpone`/`recall` của phòng Lab/CĐHA (phụ thuộc `QueueState`, không thể giữ).
  - **Hàm hiển thị mới:** `VisitEnricher` (số phòng), `VisitTimeline` (mốc thời gian). `VisitResponse` đổi hình dạng: bỏ `callbackPostponed`, `scheduledAt`; thêm `skipCount`, `queueOrder`.
- [x] **3.A4 `VisitServiceDetail`** — bỏ `unitPrice`, `subtotal`; thêm `queueOrder` (= lúc chỉ định, gán ở `createDetail`), `skipCount`. `ClinicService` thêm `vatRate` (có trong `ServiceResponse`, `CreateServiceRequest`, `UpdateServiceRequest`; mặc định 0.00; đổi chỉ áp cho chỉ định mới).
- [x] **3.A5 `ServiceInvoice`** — entity + `ServiceInvoiceRepository` + enum `PaymentStatus`; `@Version`; mã `SINV-YYYY-XXXX` sinh từ id. **Tạo chỉ định ⇒ tạo hóa đơn `PENDING` trong cùng giao dịch** (`VisitSupport.createInvoice`, snapshot `unit_price`/`vat_rate`). `ServiceDetailResponse` đọc giá/VAT từ hóa đơn và thêm `vatRate`, `invoiceId`, `invoiceCode`, `paymentStatus`. **Làm thêm cho giữ bất biến (BR-33):** hủy dịch vụ (cả `cancel` đợt khám lẫn `cancel` của KTV/Bác sĩ) ⇒ `VisitSupport.cancelInvoiceOf` hủy hóa đơn cùng giao dịch; hóa đơn `PAID` ⇒ `CLIN_1310`.
- [x] **3.A6** `billableServices` tạm trả giá từ hóa đơn snapshot (fallback `services.price`); sẽ xóa ở 3.B13.
- **Kết quả đo Khối A (12/11):** `mvn clean test` clinical **117 test** (106 trước Khối A − 15 test của tính năng đã xóa/thay thế + 26 test mới), toàn repo **316 test, 0 lỗi** (auth 28, appointment 68, clinical 117, billing 68, notification 35). **Chạy thật** clinical-service (jar) + auth-service thật trên MySQL schema v3.0: khởi động `ddl-auto: validate` đạt; check-in vãng lai `201` (`queueOrder` = epoch micro, `skipCount` 0); check-in lần 2 `409 CLIN_1306`; phân công ⇒ `assignedRoom` P101 qua `VisitEnricher`; bắt đầu khám ⇒ dòng khám + hóa đơn `SINV-2026-0001` 100.000 `PENDING`; chỉ định CBC + siêu âm + tiêm ⇒ mỗi dịch vụ 1 hóa đơn (`0002`–`0004`); xét nghiệm đánh giá `HIGH/NORMAL/NORMAL/LOW` **tính khi hiển thị** (DB không có cột); Bác sĩ hủy siêu âm ⇒ hóa đơn `0003` `CANCELLED`, ca `RESULTS_READY`; `GET /pets/1` ⇒ `weight` 4.6 suy ra; các endpoint `call-back`/`postpone-callback`/`postpone`/`recall` ⇒ 404; hủy ca ở sảnh ⇒ `cancelReason` suy ra từ lịch sử. Không có ERROR trong log; dữ liệu thử đã xóa (thú cưng seed giữ nguyên).

> **Ghi chú điều chỉnh sau Khối A (12/11) — đọc trước khi làm Khối B:**
> - **3.B2:** `DoctorStatusServiceImpl.statusOf` hiện vẫn tính BUSY khi có ca `WAITING` đã gán (điều kiện v2, ghi chú tại `DoctorStatusService`) và `assignDoctor` vẫn từ chối bác sĩ BUSY. Bước này phải đổi: BUSY chỉ khi có ca `IN_PROGRESS`, `assign-doctor` KHÔNG kiểm tra bác sĩ rảnh.
> - **3.B3 / 3.B10:** hiện KHÔNG còn đường nào để vào Giai đoạn 2 (`call-back` đã xóa); `RESULTS_READY → IN_PROGRESS` chỉ quay lại khi có `start-phase2` (3.B10) sau `call-next` (3.B3). Giữa hai bước này luồng chuẩn dừng ở `RESULTS_READY`.
> - **3.B6:** `postpone`/`recall` và `QueueState` đã bị xóa ở 3.A3; bước này chỉ còn **thêm** `call-next`, `skip` (`SKIPPED`), `sample-collected`, huy hiệu `labStatus`. `VisitServiceDetailRepository.findQueue` đã sắp đúng `(cấp cứu chưa lùi) DESC, queue_order`.
> - **3.B8:** phần lõi đã làm ở 3.A5 (`cancelInvoiceOf`, `CLIN_1310`); bước này còn lại: API hủy dịch vụ tại phòng và test EX-08 qua API.
> - **3.B9 / 3.B10:** `VisitSupport.evaluateParaclinicalCompletion` vẫn phát `DoctorPriorityCallbackEvent` và `closeVisit`/`abandon` vẫn phát `VisitClosedToPaymentEvent` + `reopen` vẫn gọi `BillingClient` (cả ba là v2, billing v2 chưa chạy được trên schema mới cho tới Phase 4). Đổi sang `ParaclinicalResultsReadyEvent` (kèm Q5: `queue_order = MIN − 1`) và bỏ gọi billing ở các bước này.
> - **3.B6–3.B8 (đã làm):** `POST /lab-queue/call-next`, `/imaging-queue/call-next`, `POST /service-details/{id}/skip` (Q4, `action=SKIPPED`, chỉ định giữ `PENDING`), `POST /service-details/{id}/sample-collected` (action `SAMPLE_COLLECTED`, trạng thái vẫn `IN_PROGRESS`). `QueueItemResponse` đổi `otherRoomStatus` → `stage` (chỉ Lab: `QUEUED/SAMPLING/ANALYZING`, suy ra từ action mới nhất) + `labStatus` (chỉ hàng đợi CĐHA: `SAMPLING/SAMPLE_COLLECTED`). Kết quả: `POST/GET /lab-results/{detailId}` (body `{results:[{labTestItemId, resultValue}]}`, vẫn nhận `items`/`value`; gói đã cấu hình phải đủ chỉ số, thiếu → `SYS_1001`) và `POST /imaging-results/{detailId}` (multipart). **Đường dẫn cũ `/service-details/{id}/lab-results` và `/imaging-result` còn giữ làm bí danh** tới khi FE đổi (Phase 6/7.1 xóa). 3.B8 (hủy CLS kèm hóa đơn, `CLIN_1310`) đã xong từ Khối A.
> - **3.B9–3.B11 (đã làm):** T06 do hệ thống tự chuyển (`changed_by_user_id = NULL`) + Q5 (`queue_order = MIN(hàng đợi bác sĩ, không tính ca này) − 1`, `skip_count = 0`; hàng đợi trống thì giữ nguyên); phát `ParaclinicalResultsReadyEvent` **và vẫn phát `DoctorPriorityCallbackEvent` song song** tới khi notification đổi listener (Phase 5, xóa ở 7.1). `start-phase2` (T07). `close-visit` (T05/T09) không còn gọi billing/phát event; thêm kiểm `CLIN_1308`/`CLIN_1309` và hoàn tất dịch vụ khám. `abandon` (T08 từ `RESULTS_READY`, T12 từ `WAITING_RESULTS`): hủy CLS `PENDING` kèm hóa đơn, `CLIN_1304` nếu còn CLS `IN_PROGRESS`, phát `VisitCompletedEvent(abandoned=true)`. `cancel-exam` (T11, Bác sĩ, chỉ GĐ 1, chưa có dịch vụ tại phòng `COMPLETED`). `cancel` (T03) chỉ còn Lễ tân + `WAITING`. `reopen` (T14) Bác sĩ phụ trách **hoặc ADMIN**, bỏ `BillingClient`/`DoctorStatusService`/khóa tên khỏi `VisitServiceImpl`. `VisitStatus`: thêm `WAITING_RESULTS→COMPLETED`, **bỏ** `WAITING_RESULTS→CANCELLED`. **FE v2 đang gọi `/cancel` cho ca đang khám và `/reopen` kiểu ADMIN sẽ phải đổi sang `/cancel-exam` (Phase 6).**
> - **3.B12–3.B14 (đã làm):** `ServiceInvoiceController`: `GET /pet-visits/{id}/service-invoices` (bảng kê + `totalPending`/`totalPaid`), `POST /service-invoices/{id}/discount` (trần = `qty × unit × (1+vat)` của chính hóa đơn, `BILL_1402`), `POST /pet-visits/{id}/service-invoices/confirm-payment {invoiceIds}` (kiểm hết rồi mới ghi; hết `PENDING` + ca `WAITING_PAYMENT` ⇒ `COMPLETED` cùng giao dịch + `VisitCompletedEvent`), `GET /pet-visits/with-debt`. Tiền tính bằng `InvoiceAmounts` (cùng công thức VIEW, `HALF_UP` từng hóa đơn). `GET /internal/pet-visits/{id}/summary` thêm mới; `/internal/clinical/visits/{id}` giữ tạm cho billing v2 (xóa ở 7.1); **đã xóa** `billable-services` + `BillableServiceResponse` + `BillingClient.cancelInvoiceForReopen`. `GET /reports/revenue` (ADMIN): doanh thu dịch vụ `PAID` theo `paid_at` nhóm `services.category` + công nợ hiện tại; **doanh thu thuốc ở billing (Phase 4), Admin FE cộng hai nguồn (§10 #5)**.
> - **3.B1:** `CheckInRequest` vẫn dùng trường `emergency` và chưa có `doctorId`; `VisitCheckedInEvent` chưa có `doctorId`.
> - **FE (Phase 6) bị ảnh hưởng bởi hình dạng response mới:** `VisitResponse` (bỏ `callbackPostponed`, `scheduledAt`; thêm `skipCount`, `queueOrder`), `QueueItemResponse` (`queueState` → `skipCount`), `ServiceDetailResponse` (thêm `vatRate`, `invoiceId`, `invoiceCode`, `paymentStatus`), `ServiceResponse`/request dịch vụ (thêm `vatRate`), request thú cưng (bỏ `weight`, response vẫn có `weight` suy ra).

### Khối B — từng nghiệp vụ (mỗi bước có `curl` ở Lộ trình)

| Bước | Nghiệp vụ | Việc chính | Kiểm thử |
|---|---|---|---|
| [x] **3.B1** | Check-in + hai danh sách | `CheckInRequest` thêm `doctorId?`; chặn `CLIN_1306`; `GET /pet-visits/queue` (chờ phân công + hàng đợi từng bác sĩ + `waitingCount` + `suggestedDoctorId`), `GET /my-queue` | Task 3.2 |
| [x] **3.B2** | Phân công | đổi `/assign` → `/assign-doctor` (không kiểm tra bác sĩ rảnh, giữ `queue_order`, kiểm `version`), thêm `/unassign-doctor`; `doctors/status` chỉ `BUSY`/`AVAILABLE` | Task 3.3.1–3.3.2b |
| [x] **3.B3** | Gọi số + Đẩy lùi (phòng khám) | `POST /call-next` (`CLIN_1302`/`CLIN_1303`, `PatientCalledEvent`), `POST /{id}/skip` — công thức Q4 (3 trường hợp), dùng `@Version` | Task 3.3.2c–d; test đủ 3 trường hợp công thức |
| [x] **3.B4** | Bắt đầu khám (T02) | kiểm tra đúng bác sĩ được gán (`CLIN_1311`), không có ca `IN_PROGRESS`; tự tạo dịch vụ `EXAM_GEN` + hóa đơn; `PUT /{id}/clinical-info` | Task 3.3.3–3.3.4 |
| [x] **3.B5** | Chỉ định CLS (T04) + dịch vụ tại phòng | `paraclinical-orders`: kiểm `weight/temperature` (`CLIN_1308`), dịch vụ tại phòng xong (`CLIN_1309`); `in-room-services`; `service-details/{id}/perform`; ca → `WAITING_RESULTS` | Task 3.4 |
| [x] **3.B6** | Hàng đợi Lab/CĐHA | `lab-queue`/`imaging-queue` sắp theo `queue_order`; `call-next`, `service-details/{id}/skip` (`action=SKIPPED`), `start`, `sample-collected`, huy hiệu liên phòng `labStatus`; **xóa** `postpone`/`recall`/tab hoãn | Task 3.5, 3.6 |
| [x] **3.B7** | Kết quả CLS | Lab: bắt buộc đủ chỉ số của gói (`service_lab_test_items`); CĐHA multipart giữ nguyên | Task 3.5, 3.6 |
| [x] **3.B8** | Hủy dịch vụ CLS (EX-08) | hủy dịch vụ ⇒ hóa đơn `CANCELLED` cùng giao dịch; `CLIN_1310` nếu hóa đơn đã `PAID` | Task 3.5/EX-08 |
| [x] **3.B9** | `WAITING_RESULTS → RESULTS_READY` (T06, Q5) | **khóa dòng `pet_visits` trước rồi mới đếm** (bài học đua); đặt `queue_order = MIN − 1`, `skip_count = 0`, history `changed_by = NULL`; phát `ParaclinicalResultsReadyEvent` | Task 3.7 |
| [x] **3.B10** | GĐ 2 + chốt ca | `start-phase2` (T07); `close-visit` (T05/T09): chỉ validate, ghi lịch sử, **bỏ** gọi billing và `VisitClosedToPaymentEvent` | Task 3.8 |
| [x] **3.B11** | Thoát ca | `abandon` (T08/T12: hủy CLS `PENDING` kèm hóa đơn, giữ công nợ), `cancel-exam` (T11, guard chưa có dịch vụ tại phòng `COMPLETED`), `cancel` (T03), `reopen` (T14: bác sĩ **hoặc ADMIN**, bỏ Feign hủy hóa đơn billing) | Task 3.8, EX-03/11 |
| [x] **3.B12** | Thu tiền dịch vụ (T10) | `GET /{id}/service-invoices`, `POST /service-invoices/{id}/discount` (`BILL_1402`), `POST /{id}/service-invoices/confirm-payment {invoiceIds}`: `PAID` + nếu hết `PENDING` thì `COMPLETED` **cùng giao dịch**; `GET /with-debt`; phát `VisitCompletedEvent` | Task 3.9, EX-14/16 |
| [x] **3.B13** | API nội bộ | thêm `/internal/pet-visits/{id}/summary`; **xóa** `/visits/{id}/billable-services`; thu gọn `BillingClient` | Task 4.2 |
| [x] **3.B14** | Báo cáo | doanh thu dịch vụ (nhóm theo `services.category`) + công nợ từ `service_invoices`; vận hành giữ | FR-69/70 |

---

## PHASE 4 — `billing-pharmacy-service`

### Khối A — khớp schema
- [x] **4.A1** `Medicine` thêm `vatRate`. `Prescription` bỏ `status`, `dispensedBy/At`. `PrescriptionItem` bỏ `unitPrice`, `subtotal`. `InventoryTransaction` bỏ `balanceAfter`, `referenceId`, thêm `medicineInvoiceId`; `InventoryTransactionType`: `EXPORT_PRESCRIPTION → EXPORT_SALE`; `quantity` có dấu.
- [x] **4.A2** Xóa `Invoice`, `InvoiceItem`, `InvoiceItemType`, `PrescriptionStatus`, `InvoiceCalculator`, `InvoiceServiceImpl`, `InvoiceController`, `VisitClosedListener`, `prescriptions/{id}/dispense`, `removeFromInvoice`, `cancelForReopen`. Thêm entity `MedicineInvoice` (`@Version`) + `MedicineInvoiceItem` + repository.
- **Xong khi:** service khởi động trên schema mới; test cũ của phần đã xóa bị loại.

### Khối B
| Bước | Nghiệp vụ | Việc chính | Kiểm thử |
|---|---|---|---|
| [x] **4.B1** | Thuốc + kho | CRUD thêm `vat_rate`; `import`/`adjust` ghi `quantity` có dấu, giữ bất biến `stock = SUM(quantity)` | Task 4.1 |
| [x] **4.B2** | Đơn thuốc | không giá/không trạng thái; trả `stockWarnings` thay vì chặn (EX-13); khóa theo `/internal/pet-visits/{id}/summary` (`PHARM_1411`); `print` | Task 4.2 |
| [x] **4.B3** | Lập hóa đơn thuốc | `POST /prescriptions/{id}/medicine-invoices`: chỉ khi ca `WAITING_PAYMENT`/`COMPLETED`; `PHARM_1407`, `PHARM_1410`; snapshot giá/VAT; mã `MINV-` | Task 4.3 |
| [x] **4.B4** | Sửa/hủy | `PUT /medicine-invoices/{id}/items` (`quantity = 0` bỏ dòng, còn ≥ 1 dòng); `POST …/cancel` (hủy là kết thúc) | Task 4.3.4 |
| [x] **4.B5** | Thu tiền + trừ kho | **một `@Transactional`**: sắp theo `medicineId` → `UPDATE … WHERE stock >= :qty` → `inventory_transactions EXPORT_SALE` → `PAID`; thiếu kho ⇒ rollback toàn bộ (`PHARM_1406`); phát `MedicineInvoicePaidEvent` + `MedicineLowStockEvent`. **Giữ lại logic trừ kho hiện có**, chỉ đổi nguồn dữ liệu | Task 4.4, EX-15/16 |
| [x] **4.B6** | Báo cáo | doanh thu thuốc từ `medicine_invoices` `PAID` (tái dùng `RevenuePeriods`) | FR-69 |


> **Ghi chú điều chỉnh sau Phase 4 (12/11/2026):**
> - **Bỏ so với v2 (đã xóa khỏi code):** `Invoice`/`InvoiceItem`, `InvoiceCalculator`, `VisitClosedListener` (và `BillingRabbitConfig`: billing không còn consumer nào), `prescriptions/{id}/dispense`, hủy đơn thuốc (`/prescriptions/{id}/cancel`), danh sách "chờ phát", `cancel-for-reopen`, `vetclinic.billing.vat.*` (VAT là dữ liệu: `medicines.vat_rate`). Docs v3.0 không có API hủy đơn thuốc nên không giữ.
> - **Đơn thuốc:** `PUT` ghép dòng theo thuốc và **sửa tại chỗ** (giữ id dòng) thay vì xóa-rồi-thêm, để hóa đơn đã hủy còn tham chiếu được (`fk_mii_pitem`); bỏ một thuốc mà hóa đơn (kể cả đã hủy) còn tham chiếu → `PHARM_1411`. Đơn bị khóa khi ca không còn `IN_PROGRESS` **hoặc** đã có hóa đơn thuốc `PENDING`/`PAID`; hóa đơn `CANCELLED` không khóa (dùng với "Mở lại hồ sơ" T14).
> - **Hóa đơn thuốc:** lập khi ca `WAITING_PAYMENT`/`COMPLETED` (sai → `BILL_1409`); `PUT …/items` chỉ sửa dòng **đã có** trong hóa đơn (dòng lạ → `SYS_1001`), `quantity = 0` bỏ dòng, luôn kiểm tra lại giảm giá; sửa/hủy/thu đều khóa dòng (`SELECT … FOR UPDATE`) + `@Version`. Thu tiền: `UPDATE … WHERE stock >= :qty` (đã thêm `version = version + 1` theo docs 4.4.2), sắp theo `medicineId`.
> - **Kho:** `POST /pharmacy/inventory/import` và `/adjust` (ADMIN, body có `medicineId`) thay `/medicines/{id}/import|adjust` (đã xóa); `inventory_transactions.quantity` có dấu, `EXPORT_SALE` có `medicine_invoice_id`; `InventoryTransactionResponse` không còn `balanceAfter`/`referenceId`.
> - **Báo cáo:** `GET /billing/reports/revenue` chỉ còn phần **thuốc** (`totals`, `periods`, `byStatus` = số hóa đơn theo trạng thái); phần dịch vụ + công nợ ở `clinical /reports/revenue` — Admin FE cộng hai nguồn (CLAUDE.md §10 #5).
> - **Feign billing→clinical** đổi sang `/internal/pet-visits/{id}/summary` + **mới** `/internal/pet-visits/summaries?ids=` (theo lô, thêm vào `InternalVisitController` của clinical). Đã xóa `/internal/clinical/visits?ids=` (chỉ billing dùng). **Còn giữ** `/internal/clinical/visits/{id}` vì notification-service v2 vẫn gọi (xóa ở 7.1 sau Phase 5).
> - **clinical:** `PrescriptionSummary` (bệnh án) bỏ `status/totalAmount/dispensedAt`; đơn thuốc xem được cả khi khách mua ngoài.
> - **Phát event:** `MedicineInvoicePaidEvent`, `MedicineLowStockEvent` đã phát sau commit (RabbitMQ); notification-service chưa nghe (Phase 5.3). Event/routing key v2 của billing không còn ai phát.
> - **FE (Phase 6) bị ảnh hưởng:** `PrescriptionResponse` (không `status/unitPrice/subtotal/totalAmount/dispensedAt`, thêm `stockWarnings`, `medicineInvoiceId/Status`), `MedicineResponse.vatRate`, endpoint kho mới, toàn bộ API hóa đơn gộp `/billing/invoices` biến mất → màn hình "bán thuốc theo đơn" mới.

---

## PHASE 5 — `notification-service` (làm rải, mỗi lần một event)

- [x] **5.1** Thêm listener cho `appointment.booked` → (xóa `appointment.created` ở 7.1). *(appointment-service đang phát song song `appointment.created` + `appointment.booked` từ 12/11; sau khi listener mới chạy, KHÔNG cần sửa gì thêm ở appointment — chỉ xóa event cũ ở 7.1.)*
- [x] **5.2** `appointment.no_show`, `visit.patient_called` (gọi số: phòng + STT; báo bị đẩy lùi), `visit.results_ready`. **BẮT BUỘC kèm theo:** khi listener `appointment.no_show` đã chạy, vào `AppointmentServiceImpl.transition()` (appointment-service) **bỏ việc phát `AppointmentStatusChangedEvent` cho `NO_SHOW`** (hiện phát song song với `AppointmentNoShowEvent`, comment ghi rõ tại chỗ) — nếu không chủ nuôi nhận hai thông báo; đồng thời bỏ nhánh `NO_SHOW` trong listener `status_changed` của notification.
- [x] **5.3** `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`.
- [x] **5.4** `POST /notifications/{id}/read`; **bỏ** `POST /notifications/send-email`.
- [x] **5.5** Cập nhật `NotificationType`; chưa làm `/topic/lobby` (CLAUDE.md §10 #6).


> **Ghi chú điều chỉnh sau Phase 5 (12/11/2026):**
> - **9 queue** `notification-service.<routing-key>` (+ DLQ): `appointment.booked`, `appointment.status_changed`, `appointment.no_show`, `visit.checked_in`, `visit.patient_called`, `visit.results_ready`, `visit.completed`, `pharmacy.medicine_invoice_paid`, `pharmacy.low_stock`. Đã bỏ 5 queue v2 (`appointment.created`, `doctor.priority_callback`, `visit.closed_to_payment`, `payment.completed`, `stock.low_alert`).
> - **Ngừng phát event trùng/cũ ở nơi phát:** appointment-service không còn phát `AppointmentCreatedEvent`, và chuyển sang `NO_SHOW` chỉ phát `AppointmentNoShowEvent` (không còn `status_changed` cho NO_SHOW, đúng yêu cầu BẮT BUỘC của 5.2); clinical-service không còn phát `DoctorPriorityCallbackEvent`. Các **class** event/routing key v2 vẫn còn trong `common-library`, xóa ở 7.1 (không còn ai phát hay nghe).
> - **Feign notification→clinical** đổi sang `/internal/pet-visits/{id}/summary`; đã xóa `/internal/clinical/visits/{id}` của clinical (không còn consumer).
> - **Nội dung thông báo:** `visit.patient_called` → chủ nuôi nhận "Mời vào <phòng> (phòng <số>)" kèm mã ca làm số thứ tự, hoặc "lùi một lượt" khi `PUSHED_BACK`; bác sĩ / KTV Lab / KTV CĐHA của đúng hàng đợi nhận bản làm mới. `visit.results_ready` → bác sĩ của ca (user) + chủ nuôi. `visit.checked_in` → thêm thông báo riêng cho bác sĩ được xếp ngay lúc tiếp nhận. `visit.completed` → chủ nuôi + lễ tân; **khách bỏ về (`abandoned`) thì lễ tân nhận cảnh báo công nợ, chủ nuôi không nhận email phiếu thu**. `medicine_invoice_paid` → chủ nuôi (push + email có số tiền). `low_stock` → Admin. Không còn thông báo "Có ca chờ thanh toán" cho lễ tân (v3.0 không có event khi bác sĩ chốt ca).
> - **API:** `GET /notifications` trả thêm trường `read`; `POST /notifications/{id}/read` (idempotent, trạng thái đọc riêng từng người, id không thuộc người đó → 404 `SYS_1006`); **đã xóa** `POST /notifications/send-email` và `SendEmailRequest`. Lịch sử vẫn trong bộ nhớ (mất khi khởi động lại).
> - **`NotificationType` đổi (FE Phase 6 bị ảnh hưởng):** `APPOINTMENT_CREATED→APPOINTMENT_BOOKED`; thêm `APPOINTMENT_NO_SHOW`, `PATIENT_CALLED`, `RESULTS_READY`, `MEDICINE_INVOICE_PAID`; bỏ `PRIORITY_CALLBACK`, `PAYMENT_PENDING`. FE đang dùng `APPOINTMENT_CREATED`, `PAYMENT_PENDING`, `PRIORITY_CALLBACK` ở `AppointmentsPage`, `DispensePage`, `InvoicesPage`, `QueuePage`, `TodayPage` (hook `useNotificationEvents`) và `notificationsApi.sendEmail` ở `api/endpoints.js`.
> - **Chưa làm (CLAUDE.md §10 #6):** `/topic/lobby` và màn hình sảnh công cộng; vẫn dùng `/user/queue/notifications` + `/topic/role/<ROLE>`.

---

## PHASE 6 — Gateway + Frontend (Linh, song song)

- [ ] **6.1** Gateway: thêm `GET /api/v1/appointments/slots` vào `vetclinic.gateway.public-paths`. *(Phía appointment-service đã công khai từ 2.B4; còn thiếu Gateway thì Portal vẫn bị 401 khi gọi qua :8080.)*
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
