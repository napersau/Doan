# LỘ TRÌNH SỬA MÃ NGUỒN v3.0 → v4.0 (TỪNG BƯỚC NHỎ, HỆ THỐNG LUÔN CHẠY ĐƯỢC)

> Lập ngày 20/11/2026 sau khi đọc toàn bộ `docs/` v4.0 (18/11), file "Giải trình thay đổi thiết kế v4.0 theo nhận xét của GVHD" và đo lại mã nguồn hiện tại (commit `83045e8`: v3.0 + phiếu xếp hàng MySQL).
> **Đích:** mã nguồn khớp `docs/schema_init.sql` v4.0 (52 bảng / 5 CSDL + Redis), `THIET_KE_KIEN_TRUC_MICROSERVICES.md` v4.0 (17 sự kiện, Feign, Outbox, `reporting-service`), Quy chuẩn v4.0 (49 mã lỗi, UUIDv7, PriceBook, Lua) và Lộ trình v4.0 (Task 1.2 → 5.5).
> **File này là nguồn duy nhất về tiến độ v4.0.** `LO_TRINH_SUA_CODE_V3.md` chỉ còn là lịch sử.

---

## ▶ TRẠNG THÁI HIỆN TẠI (Claude đọc mục này TRƯỚC khi làm; cập nhật SAU mỗi bước)

| Phase | Trạng thái | Ghi chú |
|---|---|---|
| Phase 0 Chuẩn bị (không đổi hành vi) | 🟨 0.1, 0.2 xong (20/11) | Bước tiếp theo: **0.3** (FE không giả định id số) |
| Phase 1 Outbox + consumer chống trùng | 🟨 1.1 xong (20/11) | 1.1 làm trước Phase 0 theo yêu cầu; 1.2 cần 0.1 xong trước (thêm bảng vào `db/schema_current.sql`) |
| Phase 2 auth-service v4 + id người dùng UUID | ⬜ | |
| Phase 3 Danh mục dịch vụ + bảng giá có lịch sử | ⬜ | |
| Phase 4 Thú cưng, lịch hẹn, đợt khám (UUID + tách 3 bảng) | ⬜ | |
| Phase 5 Thuốc v4 + đơn thuốc về clinical + hóa đơn nháp | ⬜ | |
| Phase 6 Hàng đợi trên Redis | ⬜ | |
| Phase 7 `reporting-service` | ⬜ | |
| Phase 8 Hoàn thiện, khóa đích v4.0 | ⬜ | |

**Quy ước:** `[ ]` chưa làm · `[x]` đã làm **và đã qua toàn bộ "Điều kiện xong"** · `[~]` đang làm dở (ghi lý do dừng ngay dưới bước). Phase chỉ đổi ✅ khi mọi bước `[x]`. Mỗi bước = **1 commit** trên `develop` (mốc quay lại: tag `v3-final`, tạo ở bước 0.1). Sau mỗi bước ghi 1–3 dòng "Kết quả:" (số test, số kiểm tra e2e, điều chỉnh khác docs).

---

## 1. Nguyên tắc (đọc kỹ, áp dụng cho MỌI bước)

1. **Hệ thống phải chạy được sau mỗi bước.** "Chạy được" = đủ cả 5 điều trong mục 2. Không có bước nào được để lại test đỏ, e2e đỏ hoặc container không `healthy`.
2. **Lược đồ tiến dần, không nhảy cóc.** Docker không đọc thẳng `docs/schema_init.sql` (đã là đích v4.0) cho tới Phase 8. Từ bước 0.1, compose đọc **`db/schema_current.sql`** và **`db/seed_current.sql`**. Mỗi bước chỉ chép sang `db/schema_current.sql` **đúng phần bảng của bước đó, giống hệt từng chữ trong `docs/schema_init.sql`** (tên cột, kiểu, ràng buộc, chú thích), rồi `docker compose down -v`. Đến bước 8.2, hai file phải trùng nhau (trừ dữ liệu demo).
3. **Đổi kiểu id theo từng ngữ cảnh, kèm mọi chỗ tham chiếu.** Khi một thực thể đổi sang UUID, **mọi cột tham chiếu đến nó ở mọi CSDL** (khóa ngoại logic `LFK:`), mọi DTO, Feign, sự kiện, giao diện và e2e đổi theo **trong cùng bước**. Thứ tự: người dùng (Phase 2) → dịch vụ (3) → thú cưng + lịch hẹn → đợt khám (4) → thuốc, đơn thuốc, hóa đơn thuốc (5).
4. **Thêm trước, xóa sau.** Bảng/API mới chạy song song cái cũ trong cùng bước; cái cũ chỉ xóa ở cuối bước khi giao diện + e2e đã chuyển.
5. **Để compiler dẫn đường:** đổi kiểu (`Long` → `UUID`) hoặc xóa trường ở entity/DTO trước, sửa mọi chỗ báo đỏ sau. Luôn `mvn -o clean ...` (không `clean` thì `target/` cũ cho kết quả sai, đã gặp nhiều lần).
6. **Docs là đích.** Tên bảng, cột, API, mã lỗi, sự kiện, routing key lấy đúng từ docs v4.0. Chỗ docs mâu thuẫn: theo mục 4 "Quyết định đã chốt"; gặp mâu thuẫn mới thì ghi thêm vào mục 4 và báo người dùng.
7. **Không phá giao diện.** Bước đổi API nào thì sửa luôn trang FE gọi API đó trong cùng bước; `npm run build` phải sạch.
8. **Giữ bài học cũ** (CLAUDE.md §8): JVM chạy UTC, `Clock` giờ VN, Jackson 3, `NamedLockService` kết nối riêng, trừ kho bằng `UPDATE ... WHERE qty >= :qty` (không `clearAutomatically`), khóa dòng đợt khám rồi mới đếm CLS (đua `RESULTS_READY`), Feign ngoài `@Transactional`, refresh token xếp hàng ở FE.

---

## 2. "Điều kiện xong" của MỘT bước (Definition of Done)

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.6"
cd vetclinic-backend && mvn -o clean install                 # 1. mọi unit test xanh (ghi số test vào "Kết quả")
cd ../fe && npm run build && CI=true npm test -- --watchAll=false   # 2. build sạch, 22+ test xanh
cd .. && docker compose down -v && docker compose up -d --build     # 3. từ CSDL sạch (db/schema_current.sql)
docker compose ps                                              #    11 container healthy (12 từ Phase 6 có Redis, 13 từ Phase 7)
bash e2e/run_all.sh                                            # 4. 5 bộ e2e xanh (đã sửa theo bước)
git add -A ':!update.md' && git commit                         # 5. commit "v4 <bước>: ..." (không commit update.md)
git push origin develop                                        # 6. push lên GitHub sau mỗi bước (yêu cầu của người dùng 20/11)
```
- Bước nào thêm nghiệp vụ mới thì thêm kiểm tra e2e cho nghiệp vụ đó (lấy kịch bản `curl` của Task tương ứng trong `docs/LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md`).
- e2e để lại dữ liệu → muốn chạy lại phải `down -v` (bình thường, không phải lỗi).
- Bước lớn (2.1, 4.2, 5.2, 6.2) được phép commit nháp trên nhánh phụ `feature/v4-<bước>` khi chưa xanh, nhưng **chỉ merge vào `develop` khi đủ 5 điều kiện**.

---

## 3. Bản đồ tổng thể

```
Phase 0  Chuẩn bị: tách lược đồ đang chạy khỏi docs, e2e/FE không phụ thuộc id số, 49 mã lỗi
Phase 1  Outbox + processed_events (hạ tầng; chưa đổi id, chưa đổi tên sự kiện)
Phase 2  auth v4: 9 bảng, nhiều vai trò, X-User-Roles; id người dùng → UUID ở MỌI service
Phase 3  clinical: service_categories, services UUID, service_prices (PriceBook), lab danh mục SMALLINT
Phase 4  pets + appointments UUID + snapshot; pet_visits UUID + vital_signs + medical_records; file_key
Phase 5  billing v4 (thuốc tách 4 bảng, giá có lịch sử, tồn riêng); đơn thuốc về clinical; hóa đơn nháp từ sự kiện
Phase 6  Redis: phiếu xếp hàng bằng Lua, phát phiếu qua ServiceInvoicePaidEvent, queue_ticket_histories
Phase 7  reporting-service (CQRS), báo cáo đọc 1 CSDL
Phase 8  Feign 2s + Circuit Breaker, khớp schema đích, seed v4, e2e EX-17…20 + NFR-09/10, CLAUDE.md v4
```
Ước lượng (1 người làm cùng Claude): Phase 0 ≈ 0,5 ngày · 1 ≈ 1 · 2 ≈ 2 · 3 ≈ 1 · 4 ≈ 2 · 5 ≈ 2 · 6 ≈ 2 · 7 ≈ 1 · 8 ≈ 1 → **≈ 12–13 ngày làm việc**.
Đo mã nguồn lúc lập (main/test, dòng): common 1.282/0 · gateway 185/0 · auth 1.724/566 · appointment 2.208/1.421 · clinical 7.446/4.229 · billing 2.630/1.242 · notification 1.194/809 · FE ≈ 5.000 · có **335** chỗ khai báo `Long xxxId` trong main.

---

## 4. Quyết định đã chốt (chỗ docs mâu thuẫn / thiếu)

| # | Vấn đề | Cách làm |
|---|---|---|
| D1 | Lược đồ trung gian giữa v3 và v4 | `db/schema_current.sql` tiến dần (nguyên tắc 2). Phần đã chuyển phải giống hệt docs |
| D2 | `outbox_events.aggregate_id` là `BINARY(16)` nhưng ở Phase 1 thực thể còn id `BIGINT` | Phase 1 dùng tạm `aggregate_id VARCHAR(36)` (ghi id dạng chuỗi), đổi về `BINARY(16)` ở bước 8.1 khi mọi thực thể đã là UUID. `event_id` dùng `BINARY(16)` ngay từ đầu |
| D3 | Mã lỗi chỉ-có-trong-code trùng số với 8 mã mới của docs | Bước 0.4: `AUTH_1107 ACCOUNT_LOCKED` + `AUTH_1108 ACCOUNT_INACTIVE` → gộp thành `AUTH_1107 ACCOUNT_DISABLED` (401, theo docs); `AUTH_1108` = `VET_PROFILE_REQUIRED`; `CLIN_1316 INVALID_DOCTOR` gộp vào `CLIN_1316 DOCTOR_INACTIVE`; `CLIN_1315 SPECIES_NOT_FOUND` → `CLIN_1325`; `CLIN_1317 LAB_ITEM_NOT_FOUND` → `CLIN_1326`; `CLIN_1318 CODE_ALREADY_EXISTS` → `CLIN_1327`; `PHARM_1412 INVALID_STOCK_ADJUSTMENT` → `PHARM_1415`. Mã chỉ-có-trong-code khác giữ nguyên số |
| D4 | EX-17: file Giải trình ghi "cảnh báo Lễ tân chuyển phiếu", còn EX-17, SM-07, Kiến trúc §5.2 ghi clinical **tự chuyển** phiếu sang `UNASSIGNED` | Làm theo docs (3 tài liệu): tự chuyển, giữ số và thứ tự, gỡ `doctor_id` ca `WAITING`, rồi báo Lễ tân. Báo người dùng sửa file Giải trình cho khớp |
| D5 | Đường dẫn Feign nội bộ | Theo Kiến trúc §5.1 v4.0: `/internal/users/...`, `/internal/pets/{id}`, `/internal/appointments/{id}`, `/internal/medicines...`, `/internal/medicine-invoices/status`. Đổi khi chạm tới từng service (2.1, 4.1, 5.1, 5.2) |
| D6 | Gateway Rate Limiting (Kiến trúc §3 có, Quy chuẩn/Lộ trình không có task) | Không làm trong lộ trình này; bước 8.4 hỏi người dùng: làm `RequestRateLimiter` in-memory hay xóa khỏi tài liệu |
| D7 | Kênh WebSocket (Lộ trình 5.1 ghi `/topic/lobby`, `/topic/doctor/{id}`...) | Giữ mô hình hiện tại `/user/queue/notifications` + `/topic/role/<ROLE>` (CLAUDE.md §10 #6). Màn hình sảnh ngoài phạm vi |
| D8 | FE: docs ghi React Vite/Axios/CORS 5173 | Giữ CRA + `fetch` (CLAUDE.md §2). Không sửa |
| D9 | `QueueTicketIssuedEvent`, `PatientCalledEvent` | Gửi thẳng (không qua outbox) đúng Kiến trúc §5.2; mọi sự kiện khác qua outbox |
| D10 | notification-service không có CSDL | Giữ chống trùng bằng `ProcessedEvents` trong bộ nhớ như hiện tại (docs không có bảng cho notification) |
| D11 | UUID dữ liệu demo | Dịch vụ/thuốc/admin: đúng UUID trong `docs/schema_init.sql` (`@s_exam`, `@m_amox`, `@u_admin`...). Tài khoản demo: `019a9442-d810-7000-8000-0000000000NN` (NN = id cũ: `bs_tien`=02 … `khach_lan`=08, chủ nuôi bổ sung 09–13); thú cưng: `019a9442-d820-7000-8000-0000000000NN` (Lu=01, Milo=02, …) theo Lộ trình Task 1.2.3 |

---

## PHASE 0 — Chuẩn bị (không đổi hành vi)

- [x] **0.1 Tách lược đồ đang chạy khỏi `docs/`.**
  - `git tag v3-final` (mốc quay lại).
  - Tạo thư mục `db/`: `git show 83045e8:docs/schema_init.sql > db/schema_current.sql`; `cp docs/seed_demo_data.sql db/seed_current.sql`. Đầu mỗi file ghi chú: "Lược đồ trung gian của lộ trình v4 – xem LO_TRINH_SUA_CODE_V4.md; đích là docs/schema_init.sql".
  - `docker-compose.yml`: mount `./db/schema_current.sql` → `01-schema_init.sql`, `./db/seed_current.sql` → `02-seed_demo_data.sql` (thay cho `./docs/...`); cập nhật chú thích đầu file.
  - Điều kiện xong (mục 2) – đây là lần đầu `down -v` lại được phép.
  - **Kết quả (20/11/2026):** tag `v3-final` = `83045e8`; `db/schema_current.sql` (628 dòng = 620 dòng `docs/schema_init.sql` v3.0 tại `v3-final` + 8 dòng ghi chú đầu file) và `db/seed_current.sql` (seed tại `v3-final` + ghi chú); `docker-compose.yml` mount `./db/...` thay `./docs/...` (chú thích đầu file cập nhật); CLAUDE.md quy tắc 4, §8.6, §11 trỏ sang `db/`. Kiểm chứng: `mvn -o clean install` 404 unit test; `npm run build` sạch + 22 test FE; `docker compose down -v && up -d --build` → 11 container `healthy`, 27 bảng, 13 tài khoản, 14 thú cưng; `bash e2e/run_all.sh` backend **72/72**, notification **28/28**, giao diện **74/74**, chức năng phụ **170/170**, quét trang **48/48** (cũng là phần e2e còn treo của bước 1.1).

- [x] **0.2 e2e không phụ thuộc id số.** Tạo `e2e/lib/api.mjs` (`call`, `login`, `check`, `step`, `sql`) và `e2e/lib/ids.mjs` tra id **qua mã ổn định**: tài khoản theo `username`, thú cưng theo `petCode`, dịch vụ theo `code`, thuốc theo `code` (gọi API danh sách, cache trong lần chạy). Sửa 5 bộ e2e dùng helper thay cho số cố định (`petId 1`, `serviceIds [5,7]`, `medicineId 1`, `doctorId 2`...). Mục đích: các Phase đổi UUID chỉ phải sửa helper và vài chỗ. e2e vẫn xanh.
  - **Kết quả (20/11/2026):** thêm `e2e/lib/api.mjs` (`call`, `login`, `loginRes`, `check`, `step`, `finish`, `run`, `sql`, hằng đường dẫn `CLIN/PHARM/BILL/AUTH/APPT/NOTI`) và `e2e/lib/ids.mjs` (`seedIds()` tra id theo mã ổn định: tài khoản theo `username` qua `GET /auth/users?keyword=`, thú cưng theo `petCode`, loài/dịch vụ/thuốc theo `code`, có cache; khớp tuyệt đối trên trường mã vì từ khóa là LIKE; `medicineIdSql(code)` cho câu SQL đối chiếu). `backend_v3`, `features_v3`, `notification_v3`, `fe_pages_v3` dùng helper và `ID.user.* / ID.pet.* / ID.service.* / ID.medicine.* / ID.species.*` thay cho mọi số cứng (`ownerId 8`, `doctorId 2/3/4`, `serviceIds [5,7]`, `medicineId 1/4`, `petId 1`, `speciesId 1/2`, `/users/1/status`, `WHERE medicine_id=1`...); `fe_smoke_v3` không có id số nên giữ nguyên; `e2e/README.md` ghi quy ước mới. Kiểm chứng: `mvn -o clean install` **404 unit test**; `npm run build` sạch + 22 test FE; `docker compose down -v && up -d --build` → 11 container `healthy`; `bash e2e/run_all.sh` backend **72/72**, notification **28/28**, giao diện **74/74**, chức năng phụ **170/170**, quét trang **48/48** (bằng đúng trước khi sửa). Bài học: chạy `backend_v3` lần hai trên CSDL chưa reset sẽ đỏ (khung 10:00 đã đầy, Lu còn đợt khám mở) — đó là hành vi bình thường, phải `down -v`. Đã thêm bước push `git push origin develop` vào Điều kiện xong, `/v4-next` và CLAUDE.md theo yêu cầu người dùng.

- [ ] **0.3 Giao diện không phụ thuộc id số.** Rà `fe/src`: `Number(id)`, `parseInt`, `+id`, so sánh id từ `useParams()` (chuỗi) với id từ API (số) bằng `===`, `key` dựa trên phép cộng số. Chuẩn hóa bằng so sánh `String(a) === String(b)` và không ép kiểu số cho id. Build + `fe_smoke_v3` + `fe_pages_v3` xanh.

- [ ] **0.4 49 mã lỗi theo Quy chuẩn 5.1 v4.0.** Thêm `ACCOUNT_DISABLED AUTH_1107` (401), `VET_PROFILE_REQUIRED AUTH_1108`, `SERVICE_PRICE_NOT_FOUND CLIN_1315`, `DOCTOR_INACTIVE CLIN_1316`, `MEDICAL_RECORD_LOCKED CLIN_1317`, `QUEUE_UNAVAILABLE CLIN_1318` (503), `PRICE_EFFECTIVE_DATE_INVALID BILL_1410`, `MEDICINE_PRICE_NOT_FOUND PHARM_1412`; dời mã trùng theo D3 (sửa chỗ dùng + unit test). Kiểm tra `fe/src/api/client.js`: 401 ở `/auth/login` **không** được kích hoạt refresh. Cập nhật `fe/src/lib/labels.js` (`ERROR_HINTS`).

---

## PHASE 1 — Transactional Outbox + consumer chống trùng (Quy chuẩn 9.2, Lộ trình Task 1.3.5)

Chưa đổi kiểu id, chưa đổi tên sự kiện: chỉ thay "gửi sau commit" (`DomainEventRabbitPublisher` `AFTER_COMMIT`) bằng outbox, hành vi nhìn từ ngoài không đổi.

- [x] **1.1 `common-library`: hạ tầng outbox.** Entity `OutboxEvent` (`aggregate_id` tạm `VARCHAR(36)` theo D2), `OutboxStatus` (`PENDING|PUBLISHED|FAILED`), `OutboxWriter` (`@Transactional(propagation = MANDATORY)`, sinh `eventId` UUIDv7, ghi JSON payload + `routing_key`), `OutboxRelay` (`@Scheduled(fixedDelay = 500)`, `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100`, publisher confirm, lùi dần 1s→5 phút, `FAILED` sau 10 lần, xóa `PUBLISHED` > 7 ngày), `ProcessedEvent` + `IdempotentConsumer` (INSERT `processed_events` cùng giao dịch, trùng khóa → bỏ qua). Nạp có điều kiện (chỉ service có JPA + Rabbit; không làm sập Gateway/Eureka – CLAUDE.md §8.1). Bật `publisher-confirm-type: correlated`. Unit test cho Writer/Relay/IdempotentConsumer. Chưa service nào dùng.
  - **Kết quả (20/11/2026, làm trước Phase 0 theo yêu cầu người dùng – bước này không phụ thuộc Phase 0):** package `com.vetclinic.common.event.outbox` (`OutboxEvent`, `OutboxStatus`, `OutboxEventRepository`, `OutboxWriter`, `OutboxRelay`, `OutboxProperties`, `OutboxConfiguration`, `@EnableOutbox`) và `com.vetclinic.common.event.processed` (`ProcessedEvent`, `ProcessedEventId`, `ProcessedEventRepository`, `IdempotentConsumer`, `@EnableIdempotentConsumer`); `BaseEvent` (`DomainEvent` kế thừa); `UuidV7`. **Không tự nạp**: cấu hình không gắn `@Configuration`/`@Component`, service phải gắn `@EnableOutbox` / `@EnableIdempotentConsumer` (registrar thêm package entity vào auto-configuration packages, không cần `@EntityScan`) – service chưa có bảng không bị `ddl-auto: validate` làm hỏng. Relay gửi message giống `JacksonJsonMessageConverter` (header `__TypeId__` = `com.vetclinic.common.event.<event_type>`) nên consumer hiện có không phải sửa; có publisher confirm thì chờ ack, không bật thì gửi không lỗi là xong. Lịch: `vetclinic.outbox.fixed-delay-ms` (500), `initial-delay-ms` (5000), dọn 03:00 VN. **Lỗi tìm ra nhờ kiểm thử MySQL thật:** cột `TIMESTAMP` làm tròn phần lẻ giây lên → `next_attempt_at` nằm ở tương lai, relay bỏ qua sự kiện vừa ghi; đã sửa bằng cắt thời điểm về giây trong `OutboxWriter`. Test: 22 unit test mới (`UuidV7Test`, `OutboxEventTest`, `OutboxWriterTest`, `OutboxRelayTest`, `IdempotentConsumerTest`) + `OutboxMySqlIT` 8/8 trên MySQL 8 thật (DDL đúng docs, `ddl-auto: validate`, JSON, `BINARY(16)`, rollback, `FOR UPDATE SKIP LOCKED`, chống trùng) – IT chỉ chạy khi có `OUTBOX_IT_JDBC_URL` (lệnh trong javadoc); `mvn -o clean install` toàn bộ **404 unit test** đạt (22 + 382). Docker: dựng lại image, các service khởi động bình thường (không `down -v` vì bước 0.1 chưa xong; e2e chạy lại sau 0.1).
- [ ] **1.2 appointment-service dùng outbox.** Thêm `outbox_events`, `processed_events` (giống docs, trừ D2) vào phần appointment của `db/schema_current.sql`; mọi chỗ phát sự kiện đổi sang `OutboxWriter`; consumer `visit.checked_in` dùng `IdempotentConsumer`. Thử tay: dừng `rabbitmq-broker` → đặt lịch → bật lại → notification vẫn nhận `APPOINTMENT_BOOKED`.
- [ ] **1.3 clinical-service dùng outbox** cho `VisitCheckedInEvent`, `ParaclinicalResultsReadyEvent`, `VisitCompletedEvent`; `QueueTicketIssuedEvent`, `PatientCalledEvent` vẫn gửi thẳng (D9).
- [ ] **1.4 billing-pharmacy-service dùng outbox** cho `MedicineInvoicePaidEvent`, `MedicineLowStockEvent`.
- [ ] **1.5 auth-service:** thêm bảng `outbox_events` + cấu hình (chưa phát sự kiện). Gỡ `DomainEventRabbitPublisher` nếu không còn nơi dùng. Ghi vào e2e `backend_v3`: kiểm tra `outbox_events` của luồng chuẩn đều `PUBLISHED`.

---

## PHASE 2 — auth-service v4 + id người dùng UUID ở mọi service (Lộ trình Task 2.1–2.4)

- [ ] **2.1 Tài khoản v4 và id người dùng UUID (bước lớn – dùng nhánh `feature/v4-2.1`).**
  - **DB:** phần auth của `db/schema_current.sql` = đúng 9 bảng docs (`roles` SMALLINT, `users` UUID, `user_roles`, `user_profiles`, `staff_profiles`, `veterinarian_profiles`, `pet_owner_profiles`, `refresh_tokens.token_hash`, `outbox_events`). Seed: `db/seed_current.sql` viết lại phần auth theo D11 (7 tài khoản demo + 5 chủ nuôi; Bác sĩ có `veterinarian_profiles`, KTV có `room_number`).
  - **auth-service:** entity `Role`(Short), `User`(UUID v7, `@ManyToMany` roles), các profile `@MapsId`; login (kiểm `ACTIVE`, `AUTH_1107`), refresh lưu SHA-256, register tạo `users + user_roles(PET_OWNER) + user_profiles + pet_owner_profiles`; JWT: `userId` chuỗi UUID, `roles` **danh sách**, `fullName`, `roomNumber`; `/me` đọc profile; API nội bộ đổi theo D5 (`/internal/users/active-doctors/count`, `/internal/users/{id}`, `/internal/users/doctors?status=ACTIVE`, `/internal/users?ids=`).
  - **common-library + Gateway:** Gateway xóa rồi gắn `X-User-Id` (UUID) và `X-User-Roles` (phân tách dấu phẩy); `HeaderAuthenticationFilter` gắn `ROLE_<mỗi vai trò>`; `SecurityUtils.getCurrentUserId()` trả `UUID`, thêm `getCurrentRoles()`; STOMP (notification) đọc `roles` danh sách.
  - **Mọi cột tham chiếu người dùng → `BINARY(16)` / `UUID`** (chỉ đổi kiểu cột, chưa đổi cấu trúc bảng khác): appointment `appointment_status_histories.changed_by_user_id`; clinical `pets.owner_id`, `pet_visits.doctor_id/receptionist_id`, `pet_visit_status_histories.changed_by_user_id`, `visit_service_detail_histories.changed_by_user_id`, `lab_test_results.performed_by`, `imaging_results.performed_by`, `service_invoices.cashier_id`, `queue_tickets.doctor_id/issued_by`; billing `prescriptions.doctor_id` (nếu có), `medicine_invoices.cashier_id`, `inventory_transactions.created_by`. Sửa entity, DTO, Feign client, record sự kiện (`actorUserId`, `doctorId`, `ownerId`... → `UUID`), notification (gửi STOMP theo userId chuỗi).
  - **FE:** `session` lưu `roles` (mảng); `RequireRole` khớp khi giao với vai trò được phép khác rỗng; mọi id người dùng là chuỗi. **e2e:** sửa `lib/ids.mjs` (id người dùng lấy từ login / API).
  - Kiểm thêm: token rotation, khóa 5 lần sai (`AUTH_1112`), refresh dùng lại vẫn đúng.
- [ ] **2.2 Quản lý nhân sự & hồ sơ (Task 2.4).** `POST /api/v1/staff` (1 giao dịch: users + roles + user_profiles + staff_profiles [+ veterinarian_profiles, thiếu → `AUTH_1108`]), `GET /api/v1/staff`, `/staff/{id}`, `/staff/doctors`, `PUT /api/v1/users/{id}/profile`, `PUT /api/v1/staff/{id}/vet-profile`, `PUT /api/v1/users/{id}/roles`, `POST /api/v1/users/{id}/lock|unlock|deactivate` (không xóa cứng, thu hồi refresh token), `GET /api/v1/roles`; Lễ tân tạo khách (`pet_owner_profiles`, `AT_CLINIC`). Gateway route `/api/v1/users/**`, `/staff/**`, `/roles/**`. FE: trang Admin "Tài khoản & nhân sự" (hồ sơ nhân sự + chuyên môn), trang Lễ tân tạo khách, trang hồ sơ cá nhân. Xóa API cũ không còn dùng (`/users/{id}/status`, `/role`) khi FE đã chuyển.
- [ ] **2.3 Sự kiện của auth qua outbox.** `UserProfileUpdatedEvent` (`user.profile_updated`) khi đổi họ tên/SĐT; `UserStatusChangedEvent` (`user.status_changed`) khi lock/unlock/deactivate. Thêm record + routing key vào `common-library`. Consumer làm ở 4.1, 4.4, 6.3, 7.1; ở bước này notification nhận `UserStatusChangedEvent` để báo Lễ tân.

---

## PHASE 3 — Danh mục dịch vụ & bảng giá có lịch sử (Lộ trình Task 3.1, Quy chuẩn 7.4)

- [ ] **3.1 `service_categories`, `services` UUID, `service_prices`, lab danh mục.**
  - **DB (clinical):** `species` SMALLINT, `service_categories` (`queue_type`, `requires_prepayment`), `services` UUID (bỏ `category/price/vat_rate/requires_prepayment`; thêm `category_id, unit, estimated_minutes, preparation_notes, created_at, updated_at`), `service_prices`, VIEW `v_current_service_prices`, `v_service_invoice_amounts` mới; `lab_test_items` SMALLINT, `service_lab_test_items`, `lab_test_reference_ranges` khóa ghép. `visit_service_details.service_id` → `BINARY(16)`. Seed như docs (UUID `@s_*`).
  - **Code:** entity `ServiceCategory`(Short), `ClinicService`(UUID), `ServicePrice`; `ServicePriceBook.current()` (`CLIN_1315`) và `changePrice()` (`BILL_1410`, đóng dòng cũ + thêm dòng mới); lập hóa đơn (T01, T04, dịch vụ tại phòng) chụp giá từ PriceBook; chính sách thu trước / loại hàng đợi đọc từ `service_categories` (thay `services.requires_prepayment` và `QueueType.ofRoom(category)`). Bỏ enum `ServiceCategory` cứng ở các chỗ so sánh nhóm → so sánh `category.code`.
  - **API:** `GET /api/v1/catalog/services?categoryCode=` (kèm giá hiện hành), `GET/POST /api/v1/catalog/services/{id}/prices`, CRUD `service_categories`, dịch vụ (xóa mềm `is_active`); Gateway route `/api/v1/catalog/**`; xóa `/api/v1/clinical/services` cũ sau khi FE chuyển.
  - **FE:** Admin "Dịch vụ" (nhóm, đơn vị, thời gian, lịch sử giá, đổi giá có ngày hiệu lực), "Cấu hình xét nghiệm"; mọi ô chọn dịch vụ (chỉ định CLS, dịch vụ tại phòng) đọc API mới. **e2e:** dịch vụ tra theo `code`; thêm kiểm tra EX-20 phần dịch vụ (đổi giá sau khi chỉ định → hóa đơn giữ giá cũ).

---

## PHASE 4 — Thú cưng, lịch hẹn, đợt khám (Lộ trình Task 2.5–2.7, 3.1.2, 3.2–3.9)

- [ ] **4.1 `pets` + `appointments` UUID, snapshot, chống N+1.**
  - **clinical:** `pets` UUID + `owner_name_snapshot`, `owner_phone_snapshot`, `microchip_number`, `allergies`, `deleted_at` (xóa mềm); `PetUpdatedEvent` (outbox) khi đổi tên/chủ; tìm khách theo SĐT dùng snapshot; `pet_visits.pet_id`, `pet_visits.appointment_id` → `BINARY(16)`; API nội bộ `/internal/pets/{id}` (D5); consumer `UserProfileUpdatedEvent` cập nhật snapshot chủ nuôi (`IdempotentConsumer`).
  - **appointment-service v4:** `appointments` đúng 14 cột docs (UUID, `owner_id`, 3 snapshot, `version`); bỏ Feign "thú cưng theo chủ nuôi" (đã có `owner_id`); đặt lịch chụp snapshot qua Feign `/internal/pets/{id}`; consumer `UserProfileUpdatedEvent`, `PetUpdatedEvent` cập nhật snapshot; `appointment_status_histories.appointment_id` → `BINARY(16)`.
  - **FE + e2e:** id thú cưng/lịch hẹn là chuỗi; trang thú cưng thêm vi mạch, dị ứng; xóa thú cưng = xóa mềm.
- [ ] **4.2 `pet_visits` UUID + `vital_signs` + `medical_records` (bước lớn – nhánh `feature/v4-4.2`).**
  - **DB (clinical):** `pet_visits` đúng docs (UUID, `doctor_name_snapshot`, `reason_for_visit`; bỏ sinh hiệu/chuyên môn), `vital_signs`, `medical_records`, `pet_visit_status_histories`; `visit_service_details` UUID + `version`, `visit_service_detail_histories`, `lab_test_results`, `imaging_results` UUID, `service_invoices` UUID; `queue_tickets/queue_ticket_items` chỉ đổi cột tham chiếu sang `BINARY(16)` (bảng này bỏ ở Phase 6). **billing:** `prescriptions.visit_id`, `medicine_invoices` tham chiếu đợt khám → `BINARY(16)`; Feign billing→clinical (`/internal/pet-visits/{id}/summary`) đổi kiểu (bỏ hẳn ở 5.2).
  - **Nghiệp vụ:** check-in nhận `reasonForVisit`; T02 tạo `medical_records`; `POST /pet-visits/{id}/vital-signs` (mỗi lần đo một dòng), `GET .../vital-signs`; `PUT /pet-visits/{id}/medical-record` (`@Version`, chỉ Bác sĩ phụ trách, khóa sau chốt → `CLIN_1317`); T04 kiểm tra "có lần đo đủ cân nặng + thân nhiệt" (`CLIN_1308`); `close-visit` nhận `{finalDiagnosis, treatmentPlan, doctorAdvice, followUpDate}` (`CLIN_1307` nếu thiếu chẩn đoán xác định); `doctor_name_snapshot` gán khi phân công; bệnh án trọn đời đọc 3 bảng.
  - **FE:** Tiếp đón ("Lý do đến khám"); hồ sơ ca khám: tab "Sinh hiệu" (bảng các lần đo + thêm lần đo), "Hồ sơ khám" (bệnh sử, khám lâm sàng, chẩn đoán sơ bộ / xác định, phác đồ, lời dặn, ngày tái khám); bệnh án của chủ nuôi. **e2e:** id đợt khám chuỗi, thay `weightAtVisit` bằng API sinh hiệu.
- [ ] **4.3 Ảnh CĐHA lưu `file_key`, URL ký tạm (Quy chuẩn 7.5).** `imaging_attachments` UUID + `file_key` (`imaging/yyyy/MM/<uuidv7>.<đuôi>`), `original_file_name`, `content_type(100)`, `uploaded_by`, `uploaded_at`; `GET /api/v1/clinical/imaging-attachments/{id}/url` (kiểm quyền, HMAC `app.storage.secret`, hạn 1 giờ); `/files/**` kiểm chữ ký; FE xin URL rồi hiển thị (xin lại khi hết hạn). Giữ kiểm magic-byte, rollback xóa tệp.
- [ ] **4.4 Nhất quán phân tán phía clinical (EX-17, BR-39).** Phân công / đổi Bác sĩ luôn gọi Feign `/internal/users/{id}` ngay lúc ghi (không fallback) → `CLIN_1316` nếu không `ACTIVE`; consumer `UserStatusChangedEvent`: ca `WAITING` của Bác sĩ bị khóa → gỡ `doctor_id`, phiếu `DOCTOR` đang chờ chuyển về chờ phân công (giữ số, giữ thứ tự – bản MySQL, Phase 6 chuyển sang Redis), notification báo Lễ tân; consumer `UserProfileUpdatedEvent` cập nhật `doctor_name_snapshot`. Giao diện hiển thị "Đã khóa" cạnh tên Bác sĩ đã khóa. e2e: kịch bản EX-17.

---

## PHASE 5 — Thuốc v4, đơn thuốc về clinical, hóa đơn nháp (Lộ trình Task 4.1–4.4)

- [ ] **5.1 Danh mục thuốc v4 (billing).** `medicine_categories`, `medicines` UUID (không giá, không tồn), `medicine_ingredients`, `medicine_prices` + `MedicinePriceBook` (`PHARM_1412`, `BILL_1410`), `medicine_stocks` (`@MapsId`, `@Version`; trừ kho nguyên tử chuyển sang bảng này), `inventory_transactions.unit_cost`, VIEW `v_current_medicine_prices`, `v_medicine_invoice_amounts`. API `/api/v1/pharmacy/medicines` (kèm thành phần, giá, tồn), `/{id}/prices`, `/{id}/import`, `/{id}/adjust`; nội bộ `/internal/medicines?keyword=&active=true`, `/internal/medicines/{id}`. Đơn thuốc **tạm vẫn ở billing**: `prescription_items.medicine_id` → `BINARY(16)`, hóa đơn thuốc lấy giá từ PriceBook. FE: Admin "Thuốc" (thành phần, quy cách, lịch sử giá, tồn kho, nhật ký). e2e: thuốc tra theo `code`; luồng 157.500đ, tồn 200 → 190.
- [ ] **5.2 Đơn thuốc về clinical + hóa đơn thuốc nháp từ `PrescriptionCreatedEvent` (bước lớn – nhánh `feature/v4-5.2`).**
  - **clinical:** bảng `prescriptions` (FK vật lý `visit_id`), `prescription_items` (snapshot tên, đơn vị); `PUT /api/v1/clinical/pet-visits/{id}/prescription` (lưu cả đơn, chỉ khi `IN_PROGRESS` và đúng Bác sĩ; `PHARM_1411` nếu khóa), `GET .../prescription`, `.../prescription/print`; Facade gọi Feign `/internal/medicines/{id}` (ngoài giao dịch), trả `stockWarnings`, `allergyWarning`; chốt ca ghi outbox `PrescriptionCreatedEvent` (`prescription.created`); T14 gọi Feign `/internal/medicine-invoices/status?prescriptionId=` (đã `PAID` → không cho sửa đơn).
  - **billing:** listener `PrescriptionCreatedEvent` (`IdempotentConsumer`) tạo `medicine_invoices` `PENDING` (snapshot khách / thú cưng / Bác sĩ, `visit_id`) + `medicine_invoice_items` (`medicine_id`, `prescribed_quantity = quantity`); nhận trùng → log `PHARM_1407`; chốt lại sau T14 → dựng lại các dòng nháp; `GET /api/v1/billing/medicine-invoices?visitId=`; `PUT .../{id}/items`, `POST .../{id}/cancel`, `POST .../{id}/confirm-payment` (cập nhật giá hiện hành EX-20, trừ `medicine_stocks`, `EXPORT_SALE`). **Xóa** bảng/entity/API đơn thuốc cũ của billing, API lập hóa đơn thuốc, Feign billing→clinical.
  - **FE:** tab "Đơn thuốc" gọi API clinical; màn hình Thu ngân hiện hóa đơn thuốc nháp (sửa số lượng, bỏ dòng, thu, khách mua ngoài = hủy). e2e: chốt ca → (chờ ~1 s) có hóa đơn nháp; EX-15, EX-16, EX-19 (gửi lại sự kiện không tạo hóa đơn thứ hai).

---

## PHASE 6 — Hàng đợi trên Redis (Quy chuẩn 9.3, SM-07, Lộ trình Task 3.2–3.8)

- [ ] **6.1 Hạ tầng Redis + kho phiếu.** Compose thêm `redis:7-alpine --appendonly yes --appendfsync everysec`, volume `redis-data`, healthcheck; chỉ `clinical-service` nối. `spring-boot-starter-data-redis` (`timeout: 1s`). `QueueRedisRepository` + 5 Lua script (`issue_ticket`, `move_ticket`, `call_ticket`, `start_ticket`, `finish_ticket`) đúng cấu trúc khóa ở ERD Mục 3 / `schema_init.sql` cuối file (`score = (2 − priority) × 10^13 + paidAtEpochMillis`). Redis lỗi → `CLIN_1318`. Unit test phần dựng tham số/khóa; chưa nối vào nghiệp vụ.
- [ ] **6.2 Chuyển hàng đợi sang Redis (bước lớn – nhánh `feature/v4-6.2`).**
  - Thu tiền chỉ ghi `service_invoices` + outbox `ServiceInvoicePaidEvent` (`invoice.service_paid`); hoàn tiền ghi `ServiceInvoiceRefundedEvent`; hủy ca ghi `VisitCancelledEvent` (thêm 3 record + routing key).
  - `QueueIssuerListener` (khóa chống trùng `queue:dedupe:{eventId}`): `ServiceInvoicePaidEvent` → phiếu `DOCTOR` / `LABORATORY` / `IMAGING` (gộp dịch vụ cùng phòng); `VisitCheckedInEvent` cấp cứu → phiếu `priority 2`; `ParaclinicalResultsReadyEvent` → phiếu `priority 1`. Gửi thẳng `QueueTicketIssuedEvent`.
  - API đọc (`/pet-visits/queue`, `/my-queue`, `/lab-queue`, `/imaging-queue`, `/cashier/pending`, `/doctors/status` `waitingCount`) đọc Redis; `call`/`call-next` tăng `calledCount`; `start` (phòng) / `start-exam` / `start-phase2` chạy `start_ticket.lua` sau commit; phân công chạy `move_ticket.lua`; phiếu kết thúc → ghi `queue_ticket_histories` (+ `_items`) trong giao dịch MySQL, `finish_ticket.lua` sau commit.
  - **DB:** xóa `queue_tickets`, `queue_ticket_items`; thêm `queue_ticket_histories`, `queue_ticket_history_items`. Xóa `TicketIssuer` cũ, `QueueTicket` entity.
  - **FE:** id phiếu là chuỗi; sau khi thu tiền hiển thị "Đang cấp số thứ tự…" tới khi phiếu xuất hiện (thông báo `TICKET_ISSUED` / làm mới). **e2e:** chờ ~1 s sau thu tiền; kiểm tra 10 thao tác "Bắt đầu" đồng thời chỉ 1 thành công (`CLIN_1313`), không có lỗi `SYS_1005` ở hàng đợi.
- [ ] **6.3 Bền vững hàng đợi.** `QueueRebuildJob` (khi khởi động nếu thiếu `queue:meta:{date}` + nút quản trị): dựng phiếu từ MySQL theo Quy chuẩn 9.3 mục 6; EX-17 chuyển phiếu trên Redis (`move_ticket.lua` sang `UNASSIGNED`); `QueueReconcileJob` (tùy chọn). e2e/thử tay EX-18: dừng Redis → thu tiền vẫn thành công → bật lại → đúng 1 phiếu, thứ tự theo `paidAt`; xóa sạch Redis → rebuild đúng.

---

## PHASE 7 — `reporting-service` (Lộ trình Task 4.5)

- [ ] **7.1 Module và mô hình đọc.** Module Maven `reporting-service` (:8086, package `com.vetclinic.reporting`), `Dockerfile` dùng chung `MODULE`, service compose + healthcheck, Eureka; phần `pet_clinic_reporting_db` của `db/schema_current.sql` (5 bảng); entity `VisitFact`, `RevenueFact`, `DailyRevenueSummary`, `DoctorDailyPerformance`; listener idempotent (mỗi listener một queue + DLQ) cho `VisitCheckedInEvent`, `VisitCompletedEvent`, `VisitCancelledEvent`, `ServiceInvoicePaidEvent`, `ServiceInvoiceRefundedEvent` (dòng âm), `MedicineInvoicePaidEvent`, `UserProfileUpdatedEvent`, `PetUpdatedEvent`; cộng dồn `daily_revenue_summaries` bằng `INSERT ... ON DUPLICATE KEY UPDATE` cùng giao dịch.
- [ ] **7.2 API báo cáo + giao diện.** `GET /api/v1/reports/revenue/daily`, `/revenue/by-category`, `/doctors/performance`, `/visits/summary` (chỉ `ADMIN`); Gateway route `/api/v1/reports/**`; FE "Báo cáo" chuyển sang API mới (một nguồn, đã gộp dịch vụ + thuốc). Xóa các endpoint báo cáo cũ ở clinical/billing khi FE đã chuyển (giữ báo cáo lịch hẹn nếu FR-70 cần tỷ lệ NO_SHOW mà reporting chưa có – ghi lại quyết định). e2e: doanh thu luồng chuẩn = 530.000 + 157.500, hoàn tiền trừ đúng, nhận trùng sự kiện không cộng hai lần (EX-19).
- [ ] **7.3 (tùy chọn) Dựng lại báo cáo** từ `outbox_events` (≤ 7 ngày) – Task 4.5.4.

---

## PHASE 8 — Hoàn thiện, khóa đích v4.0

- [ ] **8.1 Dọn kỹ thuật.** Feign: `connectTimeout/readTimeout 2s` + Resilience4j Circuit Breaker (Kiến trúc §5.1); `outbox_events.aggregate_id` → `BINARY(16)`/`UUID` (D2); `grep` không còn `Long .*Id` cho thực thể nghiệp vụ, không còn cột `ENUM`; 18 enum Java + 4 bảng danh mục khớp `DANH_SACH_KIEU_ENUM_HE_THONG.md`.
- [ ] **8.2 Khớp lược đồ đích.** `diff` phần DDL của `db/schema_current.sql` với `docs/schema_init.sql` = rỗng; viết `docs/seed_demo_data.sql` v4 (Task 1.2.3, UUID theo D11) từ `db/seed_current.sql`; compose mount lại `./docs/schema_init.sql` + `./docs/seed_demo_data.sql`; xóa `db/`; MySQL healthcheck theo seed mới. `down -v` + `up` từ docs chạy được.
- [ ] **8.3 Kiểm thử ngoại lệ phân tán & NFR.** e2e (hoặc `e2e/v4_resilience.mjs`): EX-17 (khóa Bác sĩ còn phiếu chờ), EX-18 (dừng RabbitMQ / Redis giữa chừng), EX-19 (phát lại sự kiện), EX-20 (đổi giá khi ca đang mở), NFR-09, NFR-10 (10 thao tác hàng đợi đồng thời ≤ 50 ms, danh sách 0 lời gọi chéo). Đổi tên bộ e2e `*_v3.mjs` → `*_v4.mjs`, cập nhật `e2e/README.md`.
- [ ] **8.4 Tài liệu & bàn giao.** Viết lại CLAUDE.md theo v4.0 (thu gọn phần v3 thành lịch sử), hỏi người dùng D6 (Rate Limiting), báo các chỗ docs cần sửa (file Giải trình EX-17, bản " (1)" trùng, mục §10 còn mở), `git tag v4.0`.

---

## Rủi ro cần biết

- **Bước 2.1, 4.2, 5.2, 6.2 chạm gần như mọi lớp** → làm trên nhánh `feature/v4-<bước>`, chia commit nháp theo lớp (DB → entity → service → controller → FE → e2e) nhưng chỉ merge khi xanh.
- **Hibernate + UUID `BINARY(16)`:** dùng `@JdbcTypeCode(SqlTypes.BINARY)` hoặc cấu hình `hibernate.type.preferred_uuid_jdbc_type=BINARY` (kiểm tra với `ddl-auto: validate` ngay ở bước đầu dùng UUID); truy vấn tay dùng `UUID_TO_BIN/BIN_TO_UUID`.
- **Jackson 3 + UUID trong sự kiện/Feign:** kiểm tra serialize/deserialize `UUID` và `List<String>` roles trong JWT (jjwt-gson) ngay ở 2.1.
- **Nhất quán sau (outbox, Redis):** e2e phải chờ có thời hạn (poll ≤ 5 s) thay vì đọc ngay sau thao tác.
- **Hiệu năng máy dev:** từ Phase 7 có 13 container; giữ `mem_limit` cho `reporting-service` như các service khác.
- **Không sửa `docs/`** trừ khi bước ghi rõ (8.2 viết `seed_demo_data.sql`) hoặc người dùng yêu cầu; mâu thuẫn mới ghi vào mục 4 và báo người dùng.
