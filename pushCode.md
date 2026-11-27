# Lộ trình đẩy code VetClinic theo từng thành viên

Mục tiêu: dựng lại repo từ bộ mã đầy đủ (đã test xong) thành lịch sử commit theo nhánh chức năng của từng người.
Mỗi commit chỉ là **chép lại file từ bản đầy đủ** rồi `git add` / `git commit` / `git push`, không cần build hay test lại.

- Tổng: **49 commit nội dung + 15 commit merge = 64 commit** (< 80).
- Người làm:
  - **Khởi**: khởi tạo repo, nền tảng backend, appointment, hàng đợi Redis, notification, reporting, Docker, kịch bản kiểm thử.
  - **Hiển**: tài liệu + CSDL, auth, clinical (lõi, hóa đơn, CLS, đơn thuốc), billing-pharmacy, phát hành.
  - **Linh**: toàn bộ `fe/`.
- Mỗi người tự commit và push bằng tài khoản Git của mình.

---

## 0. Chuẩn bị (làm một lần)

### 0.1. Đóng gói bản mã đầy đủ (người giữ mã nguồn hiện tại làm)

```bash
cd ~/Desktop/Doan
git archive --format=zip -o ~/Desktop/Doan-full.zip HEAD    # chỉ file đang được Git theo dõi (không node_modules, target, .env)
mkdir -p ~/Desktop/Doan-full && cd ~/Desktop/Doan-full && unzip -q ../Doan-full.zip
```

Gửi `Doan-full.zip` cho hai bạn còn lại. Mỗi người giải nén ra `~/Desktop/Doan-full`.

### 0.2. Sửa vài dòng chú thích trong `Doan-full` (trước khi chép)

Repo mới **không** chứa các file ghi chú nội bộ (xem 0.5), nên các dòng chú thích dưới đây sẽ trỏ tới file không tồn tại. Chạy một lần trong `Doan-full` (Git Bash). Mỗi người chạy trên bản của mình, hoặc người gửi sửa trước rồi mới nén.

```bash
cd ~/Desktop/Doan-full
sed -i 's/UUID cố định theo quyết định D11 của LO_TRINH_SUA_CODE_V4.md; /UUID cố định; /' docs/seed_demo_data.sql
sed -i 's/LO_TRINH_SUA_CODE_V4.md bước 0.4, quyết định D3: //' vetclinic-backend/common-library/src/main/java/com/vetclinic/common/exception/ErrorCode.java
sed -i 's/ (CLAUDE.md §5.3)/ (THIET_KE_KIEN_TRUC_MICROSERVICES.md §5.2)/' vetclinic-backend/common-library/src/main/java/com/vetclinic/common/event/EventRouting.java
sed -i 's/, CLAUDE.md §5.2)/)/' vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/event/VisitCheckedInListener.java
sed -i 's/ (LO_TRINH 6\.1)//' vetclinic-backend/appointment-service/src/main/resources/application.yml
sed -i 's/Kiểm thử backend v3.0/Kiểm thử backend v4.0/; s/ + các luồng dễ lỗi của CLAUDE.md §13\./ + các luồng dễ lỗi./' e2e/backend_v4.mjs
sed -i 's/ Kiến trúc và quy ước: mục "Frontend" trong `..\/CLAUDE.md`\.//' fe/README.md

# kiểm tra: lệnh này phải không in ra gì (ngoài các file bị loại ở 0.5)
grep -rIl -i "claude\|LO_TRINH\|DANH_SACH_SUA" . | grep -v -E "^\./(CLAUDE|LO_TRINH|DANH_SACH_SUA|update\.md|pushCode\.md|\.claude/|fe/legacy-mock/)"
```

### 0.3. Tạo repo mới trên GitHub

Hiển hoặc Khởi tạo repo **trống** (không README, không .gitignore), ví dụ `vetclinic`, rồi thêm hai bạn còn lại làm collaborator.
Không dùng lại repo cũ: repo cũ vẫn giữ lịch sử commit cũ.

### 0.4. Mỗi người: clone repo, đặt danh tính Git, nạp hàm chép file

```bash
cd ~/Desktop
git clone https://github.com/<nhom>/vetclinic.git && cd vetclinic
git config user.name  "Họ Tên Của Bạn"
git config user.email "email-github-cua-ban@..."
```

Tạo file `~/cpf.sh` (một lần), rồi `source ~/cpf.sh` mỗi khi mở terminal Git Bash mới:

```bash
# ~/cpf.sh
export SRC=~/Desktop/Doan-full
# cpf <đường dẫn tương đối>...  : chép file / thư mục từ bản đầy đủ vào đúng vị trí trong repo mới
cpf() {
  for p in "$@"; do
    if [ -d "$SRC/$p" ]; then mkdir -p "$p" && cp -r "$SRC/$p/." "$p/"
    elif [ -f "$SRC/$p" ]; then mkdir -p "$(dirname "$p")" && cp "$SRC/$p" "$p"
    else echo "KHÔNG THẤY: $p" >&2; return 1; fi
  done
}
```

Quy trình chuẩn cho **mỗi commit** trong tài liệu này:

```bash
source ~/cpf.sh
cpf <các đường dẫn ghi trong bước>
git add -A
git status --short          # nhìn lại: chỉ có file của bước này
git commit -m "<message gợi ý>"
git push                    # lần đầu trên nhánh mới: git push -u origin <tên nhánh>
```

Nếu `cpf` báo `KHÔNG THẤY` thì dừng lại, kiểm tra đường dẫn. Đừng commit phần đã chép dở.

Quy trình chuẩn cho **mỗi lần merge** (người được ghi tên làm, hoặc mở Pull Request trên GitHub rồi bấm "Create a merge commit"):

```bash
git checkout develop && git pull
git merge --no-ff <nhánh> -m "<message merge gợi ý>"
git push
git branch -d <nhánh> && git push origin --delete <nhánh>     # tùy chọn: dọn nhánh đã merge
```

Trước khi **tạo nhánh mới**, luôn lấy develop mới nhất: `git checkout develop && git pull && git checkout -b <nhánh>`.

### 0.5. File KHÔNG đưa vào repo mới

Các file ghi chú làm việc nội bộ, không phải mã nguồn: `CLAUDE.md`, `CLAUDE.v2-backup-2026-10-29.md`, `CLAUDE.v3-backup-2026-11-22.md`, `.claude/`, `LO_TRINH_SUA_CODE_V3.md`, `LO_TRINH_SUA_CODE_V4.md`, `DANH_SACH_SUA_TAI_LIEU_V4.md`, `update.md`, `pushCode.md`, `fe/legacy-mock/` (bản mock cũ, không build), `.env` (bí mật).
Các bước dưới đây chỉ chép đúng đường dẫn liệt kê nên không chạm tới những file này.

---

## 1. Thứ tự tổng thể và điểm merge

Các nhánh không sửa chung file nào nên merge không xung đột. Chỉ cần giữ đúng **thứ tự merge** (cột "Chờ") để lịch sử hợp lý.

| Đợt | Khởi | Hiển | Linh | Merge vào `develop` (thứ tự) |
|---|---|---|---|---|
| 1 | K0 khởi tạo `main` + `develop` | | | (không) |
| 1 | `feature/backend-foundation` (8) | `feature/docs-database` (4) | `feature/fe-setup` (3) | **M1** docs-database → **M2** backend-foundation → **M3** fe-setup |
| 2 | `feature/appointment-service` (3) | `feature/auth-service` (4) | `feature/fe-public-customer` (2) | **M4** auth → **M5** appointment → **M6** fe-public-customer |
| 3 | `feature/notification-service` (2) | `feature/clinical-core` (4) → `feature/clinical-billing-paraclinic` (2) | `feature/fe-staff` (4) | **M7** clinical-core → **M8** clinical-billing-paraclinic → **M9** notification |
| 4 | `feature/clinical-queue-redis` (3, tạo **sau M8**) | `feature/billing-pharmacy` (3) | (tiếp `fe-staff`) | **M10** billing-pharmacy → **M11** clinical-queue-redis → **M12** fe-staff |
| 5 | `feature/reporting-service` (3) → `feature/devops-testing` (3) | phát hành | | **M13** reporting → **M14** devops-testing → **M15** `develop` → `main` + tag `v4.0` |

Đếm: K0 1 + Khởi 8+3+2+3+3+3 = 23 · Hiển 4+4+4+2+3 = 17 · Linh 3+2+4 = 9 → **49 commit nội dung** + **15 merge** = **64**.

---

## 2. Khởi: khởi tạo repo (K0, nhánh `main`)

```bash
cpf .gitignore .env.example
git add -A && git commit -m "chore: initialize repository with gitignore and env template"
git push -u origin main
git checkout -b develop && git push -u origin develop
```

Trên GitHub: Settings → Branches → đặt `develop` làm nhánh mặc định (tùy chọn).

---

## 3. Đợt 1

### 3.1. Hiển: `feature/docs-database` (4 commit) → M1

```bash
git checkout develop && git pull && git checkout -b feature/docs-database
```

| # | Chép | Commit message |
|---|---|---|
| H1 | `cpf docs/TAI_LIEU_MO_TA_NGHIEP_VU_PHONG_KHAM_THU_Y.md docs/DAC_TA_USE_CASE_CORE.md docs/YEU_CAU_CHUC_NANG_VA_PHI_CHUC_NANG.md docs/KE_HOACH_VA_LICH_TRINH_DO_AN_TOT_NGHIEP.md docs/BANG_TONG_HOP_LICH_TRINH_TOAN_KHOA.md` | `docs: business description, core use cases, functional and non-functional requirements, project plan` |
| H2 | `cpf docs/THIET_KE_KIEN_TRUC_MICROSERVICES.md docs/QUY_CHUAN_THIET_KE_VA_LAP_TRINH_BACKEND_SPRINGBOOT.md docs/THIET_KE_SO_DO_TRANG_THAI_STATE_MACHINE.md docs/THIET_KE_SO_DO_HOAT_DONG_ACTIVITY.md docs/TONG_HOP_NGOAI_LE_VA_XU_LY_HE_THONG.md docs/LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md` | `docs: microservice architecture, backend coding standard, state machines, activity diagrams, exception handling` |
| H3 | `cpf docs/THIET_KE_CO_SO_DU_LIEU_ERD.md docs/DANH_SACH_KIEU_ENUM_HE_THONG.md docs/schema_init.sql docs/.dockerignore` | `feat(db): ERD, enum catalogue and schema_init.sql (5 databases, 52 tables, 4 views)` |
| H4 | `cpf docs/seed_demo_data.sql docs/demo-images` | `feat(db): demo seed data with fixed ids and sample imaging files` |

**M1** (Hiển): `git merge --no-ff feature/docs-database -m "Merge branch 'feature/docs-database' into develop"`

### 3.2. Khởi: `feature/backend-foundation` (8 commit) → M2 (sau M1)

```bash
git checkout develop && git pull && git checkout -b feature/backend-foundation
source ~/cpf.sh
B=vetclinic-backend
C=$B/common-library/src/main/java/com/vetclinic/common
```

| # | Chép | Commit message |
|---|---|---|
| K1 | `cpf $B/pom.xml` | `build: maven multi-module parent (Java 21, Spring Boot 4.1, Spring Cloud 2025.1)` |
| K2 | `cpf $B/common-library/pom.xml $C/dto $C/exception $C/util` | `feat(common): ApiResponse, PageResponse, ErrorCode catalogue, global exception handlers, UUIDv7 and time utils` |
| K3 | `cpf $C/security` | `feat(common): header-based authentication filter, role codes and security utilities` |
| K4 | `cpf $C/feign $C/lock` | `feat(common): Feign interceptor and error decoder, Resilience4j circuit breaker wrapper, MySQL named lock` |
| K5 | `cpf $C/event` | `feat(common): domain events, RabbitMQ routing with DLQ, transactional outbox relay and idempotent consumer` |
| K6 | `cpf $B/common-library/src/test` | `test(common): unit tests for outbox, idempotent consumer, error codes, security filter, UUIDv7` |
| K7 | `cpf $B/service-registry` | `feat(registry): Eureka service registry` |
| K8 | `cpf $B/api-gateway` | `feat(gateway): API gateway with JWT validation, header propagation, routes and CORS` |

**M2** (Khởi): `git merge --no-ff feature/backend-foundation -m "Merge branch 'feature/backend-foundation' into develop"`

### 3.3. Linh: `feature/fe-setup` (3 commit) → M3 (sau M2)

```bash
git checkout develop && git pull && git checkout -b feature/fe-setup
source ~/cpf.sh
F=fe/src
```

| # | Chép | Commit message |
|---|---|---|
| L1 | `cpf fe/package.json fe/package-lock.json fe/.gitignore fe/.env.example fe/public $F/index.js $F/index.css $F/reportWebVitals.js $F/setupTests.js $F/config.js $F/assets` | `chore(fe): bootstrap React app (CRA) with public assets and config` |
| L2 | `cpf $F/api $F/lib $F/hooks $F/constants $F/contexts` | `feat(fe): fetch API client with refresh queue, STOMP client, auth and notification contexts, shared hooks` |
| L3 | `cpf $F/App.js $F/layouts $F/components $F/features $F/styles/ui.css` | `feat(fe): routing, role-based layouts and shared UI components` |

**M3** (Linh): `git merge --no-ff feature/fe-setup -m "Merge branch 'feature/fe-setup' into develop"`

---

## 4. Đợt 2

### 4.1. Hiển: `feature/auth-service` (4 commit) → M4

```bash
git checkout develop && git pull && git checkout -b feature/auth-service
source ~/cpf.sh
S=vetclinic-backend/auth-service
P=$S/src/main/java/com/vetclinic/auth
```

| # | Chép | Commit message |
|---|---|---|
| H5 | `cpf $S/pom.xml $S/src/main/resources $P/AuthServiceApplication.java $P/config $P/constant $P/entity $P/repository` | `feat(auth): user, role and profile entities with repositories` |
| H6 | `cpf $P/security $P/dto $P/mapper $P/service $P/scheduler` | `feat(auth): JWT login, refresh token rotation, lockout after failed attempts, user and staff management` |
| H7 | `cpf $P/controller` | `feat(auth): REST API for auth, users, staff, roles and internal endpoints` |
| H8 | `cpf $S` | `test(auth): unit tests for authentication, token rotation and user services` |

**M4** (Hiển): `git merge --no-ff feature/auth-service -m "Merge branch 'feature/auth-service' into develop"`

### 4.2. Khởi: `feature/appointment-service` (3 commit) → M5 (sau M4)

```bash
git checkout develop && git pull && git checkout -b feature/appointment-service
source ~/cpf.sh
S=vetclinic-backend/appointment-service
P=$S/src/main/java/com/vetclinic/appointment
```

| # | Chép | Commit message |
|---|---|---|
| K9 | `cpf $S/pom.xml $S/src/main/resources $P/AppointmentServiceApplication.java $P/config $P/constant $P/entity $P/repository` | `feat(appointment): appointment entity, status history and repositories` |
| K10 | `cpf $P/dto $P/mapper $P/client $P/service $P/event $P/scheduler` | `feat(appointment): booking with slot capacity per active doctor, cancel/reschedule rules, NO_SHOW job, event listeners` |
| K11 | `cpf $S` | `feat(appointment): REST API and unit tests` |

**M5** (Khởi): `git merge --no-ff feature/appointment-service -m "Merge branch 'feature/appointment-service' into develop"`

### 4.3. Linh: `feature/fe-public-customer` (2 commit) → M6 (sau M5)

```bash
git checkout develop && git pull && git checkout -b feature/fe-public-customer
source ~/cpf.sh
F=fe/src
```

| # | Chép | Commit message |
|---|---|---|
| L4 | `cpf $F/pages/Public $F/pages/Auth $F/styles/home.css $F/styles/auth.css` | `feat(fe): landing page, login and register` |
| L5 | `cpf $F/pages/Customer $F/styles/customer.css $F/styles/customer-records.css $F/styles/pets.css $F/styles/profile.css $F/styles/slots.css $F/styles/notifications.css` | `feat(fe): pet owner portal (pets, booking, appointments, medical history, notifications, profile)` |

**M6** (Linh): `git merge --no-ff feature/fe-public-customer -m "Merge branch 'feature/fe-public-customer' into develop"`

---

## 5. Đợt 3

### 5.1. Hiển: `feature/clinical-core` (4 commit) → M7

```bash
git checkout develop && git pull && git checkout -b feature/clinical-core
source ~/cpf.sh
S=vetclinic-backend/clinical-service
P=$S/src/main/java/com/vetclinic/clinical
```

| # | Chép | Commit message |
|---|---|---|
| H9 | `cpf $S/pom.xml $S/src/main/resources/application.yml $P/ClinicalServiceApplication.java $P/config $P/constant $P/entity $P/repository` | `feat(clinical): domain model for catalog, pets, visits, service invoices and prescriptions` |
| H10 | `cpf $P/dto $P/mapper $P/client` | `feat(clinical): request/response DTOs, mapper and Feign clients for auth, appointment and billing` |
| H11 | `cpf $P/service/CatalogService.java $P/service/impl/CatalogServiceImpl.java $P/service/ServicePriceBook.java $P/service/PetService.java $P/service/impl/PetServiceImpl.java $P/controller/CatalogController.java $P/controller/PetController.java $P/controller/InternalClinicalController.java` | `feat(clinical): species, services with price history, lab test catalog and pet management` |
| H12 | `cpf $P/service/VisitService.java $P/service/impl/VisitServiceImpl.java $P/service/VisitSupport.java $P/service/VisitAssembler.java $P/service/VisitEnricher.java $P/service/VisitTimeline.java $P/service/MedicalHistoryService.java $P/service/impl/MedicalHistoryServiceImpl.java $P/service/DoctorAvailabilityService.java $P/service/DoctorStatusService.java $P/service/impl/DoctorStatusServiceImpl.java $P/controller/VisitController.java $P/controller/InternalVisitController.java $P/controller/DoctorController.java` | `feat(clinical): visit workflow (check-in, examination phases, close, reopen) and medical history` |

**M7** (Hiển): `git merge --no-ff feature/clinical-core -m "Merge branch 'feature/clinical-core' into develop"`

### 5.2. Hiển: `feature/clinical-billing-paraclinic` (2 commit) → M8 (sau M7)

```bash
git checkout develop && git pull && git checkout -b feature/clinical-billing-paraclinic
source ~/cpf.sh
P=vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical
```

| # | Chép | Commit message |
|---|---|---|
| H13 | `cpf $P/service/InvoiceAmounts.java $P/service/ServiceInvoiceService.java $P/service/impl/ServiceInvoiceServiceImpl.java $P/controller/ServiceInvoiceController.java $P/service/ParaclinicService.java $P/service/impl/ParaclinicServiceImpl.java $P/controller/ParaclinicController.java $P/controller/FileController.java $P/service/FileUrlSigner.java $P/service/ImagingStorage.java` | `feat(clinical): service invoices with discount/refund, lab and imaging results with signed file URLs` |
| H14 | `cpf $P/service/PrescriptionService.java $P/service/impl/PrescriptionServiceImpl.java $P/controller/PrescriptionController.java` | `feat(clinical): prescriptions with stock warning and PrescriptionCreatedEvent on visit close` |

**M8** (Hiển): `git merge --no-ff feature/clinical-billing-paraclinic -m "Merge branch 'feature/clinical-billing-paraclinic' into develop"`

### 5.3. Khởi: `feature/notification-service` (2 commit) → M9 (sau M8)

```bash
git checkout develop && git pull && git checkout -b feature/notification-service
source ~/cpf.sh
S=vetclinic-backend/notification-service
P=$S/src/main/java/com/vetclinic/notification
```

| # | Chép | Commit message |
|---|---|---|
| K12 | `cpf $S/pom.xml $S/src/main/resources $P/NotificationServiceApplication.java $P/config $P/security $P/model $P/dto $P/client` | `feat(notification): STOMP WebSocket with JWT on CONNECT and subscription rules, mail config` |
| K13 | `cpf $S` | `feat(notification): event listeners pushing in-app and email notifications, REST API and tests` |

**M9** (Khởi): `git merge --no-ff feature/notification-service -m "Merge branch 'feature/notification-service' into develop"`

### 5.4. Linh: `feature/fe-staff` (4 commit, merge ở đợt 4 là M12)

```bash
git checkout develop && git pull && git checkout -b feature/fe-staff
source ~/cpf.sh
F=fe/src
```

| # | Chép | Commit message |
|---|---|---|
| L6 | `cpf $F/pages/Reception $F/styles/reception.css $F/styles/intake.css $F/styles/billing.css` | `feat(fe): reception screens (appointments, intake, queue board, customers, pets, invoices, history)` |
| L7 | `cpf $F/pages/Veterinarian $F/pages/Paraclinic $F/styles/veterinarian.css $F/styles/care.css` | `feat(fe): veterinarian workspace and paraclinic (lab, imaging) queue and result entry` |
| L8 | `cpf $F/pages/Admin $F/styles/admin.css` | `feat(fe): admin dashboard, users, catalog, medicines, lab config, visits and reports` |
| L9 | `cpf fe/Dockerfile fe/nginx.conf fe/security-headers.conf fe/.dockerignore fe/README.md` | `build(fe): production Dockerfile with nginx and security headers, README` |

---

## 6. Đợt 4

### 6.1. Hiển: `feature/billing-pharmacy` (3 commit) → M10

```bash
git checkout develop && git pull && git checkout -b feature/billing-pharmacy
source ~/cpf.sh
S=vetclinic-backend/billing-pharmacy-service
P=$S/src/main/java/com/vetclinic/billing
```

| # | Chép | Commit message |
|---|---|---|
| H15 | `cpf $S/pom.xml $S/src/main/resources $P/BillingPharmacyServiceApplication.java $P/config $P/constant $P/entity $P/repository` | `feat(billing): medicine catalog, prices, stock, medicine invoices and inventory transaction model` |
| H16 | `cpf $P/dto $P/service $P/event` | `feat(billing): draft invoice from prescription event, atomic payment with stock deduction, low-stock event` |
| H17 | `cpf $S` | `feat(billing): REST API for pharmacy and medicine invoices, unit tests` |

**M10** (Hiển): `git merge --no-ff feature/billing-pharmacy -m "Merge branch 'feature/billing-pharmacy' into develop"`

### 6.2. Khởi: `feature/clinical-queue-redis` (3 commit) → M11 (sau M10)

```bash
git checkout develop && git pull && git checkout -b feature/clinical-queue-redis
source ~/cpf.sh
S=vetclinic-backend/clinical-service
P=$S/src/main/java/com/vetclinic/clinical
```

| # | Chép | Commit message |
|---|---|---|
| K14 | `cpf $S/src/main/resources/queue $P/queue $P/service/QueueTicketService.java $P/service/impl/QueueTicketServiceImpl.java $P/service/DoctorQueueService.java $P/service/impl/DoctorQueueServiceImpl.java $P/controller/QueueTicketController.java` | `feat(clinical): Redis queue tickets with Lua scripts, priority scoring, call/start/finish and rebuild jobs` |
| K15 | `cpf $P/event` | `feat(clinical): user profile and doctor status listeners (snapshot update, reassign waiting tickets)` |
| K16 | `cpf $S` | `test(clinical): unit tests for visits, invoices, paraclinic, prescriptions and Redis queue` |

**M11** (Khởi): `git merge --no-ff feature/clinical-queue-redis -m "Merge branch 'feature/clinical-queue-redis' into develop"`

### 6.3. Linh → M12 (sau M11)

**M12** (Linh): `git merge --no-ff feature/fe-staff -m "Merge branch 'feature/fe-staff' into develop"`

---

## 7. Đợt 5

### 7.1. Khởi: `feature/reporting-service` (3 commit) → M13

```bash
git checkout develop && git pull && git checkout -b feature/reporting-service
source ~/cpf.sh
S=vetclinic-backend/reporting-service
P=$S/src/main/java/com/vetclinic/reporting
```

| # | Chép | Commit message |
|---|---|---|
| K17 | `cpf $S/pom.xml $S/src/main/resources $P/ReportingServiceApplication.java $P/config $P/constant $P/entity $P/repository` | `feat(reporting): CQRS read model (visit and revenue facts, daily summaries, doctor performance)` |
| K18 | `cpf $P/event $P/service $P/client $P/dto $P/controller` | `feat(reporting): event projections, report API and rebuild from service outboxes` |
| K19 | `cpf $S` | `test(reporting): projection, query and rebuild tests` |

**M13** (Khởi): `git merge --no-ff feature/reporting-service -m "Merge branch 'feature/reporting-service' into develop"`

### 7.2. Khởi: `feature/devops-testing` (3 commit) → M14 (sau M13)

```bash
git checkout develop && git pull && git checkout -b feature/devops-testing
source ~/cpf.sh
```

| # | Chép | Commit message |
|---|---|---|
| K20 | `cpf vetclinic-backend/Dockerfile vetclinic-backend/.dockerignore docker-compose.yml docker-compose.dev.yml docker-compose.db.yml docker-compose.nomount.yml` | `build: shared backend Dockerfile and Docker Compose for MySQL, RabbitMQ, Redis and all services` |
| K21 | `cpf scripts` | `test: service-level check scripts against real MySQL, RabbitMQ and Redis, enum audit` |
| K22 | `cpf e2e` | `test: end-to-end suites (backend, notification, UI flows, pages, resilience)` |

**M14** (Khởi): `git merge --no-ff feature/devops-testing -m "Merge branch 'feature/devops-testing' into develop"`

### 7.3. Hiển: kiểm tra đủ file, phát hành (M15)

```bash
git checkout develop && git pull
source ~/cpf.sh
# so sánh repo mới với bản đầy đủ: chỉ được phép còn các dòng "Only in .../Doan-full" của file bị loại ở 0.5
diff -rq "$SRC" . -x .git -x node_modules -x target -x build
```

Nếu `diff` còn file nào khác (không thuộc danh sách 0.5), chép nốt bằng `cpf` rồi commit trên `develop`, ví dụ `chore: add missing files`. Commit này làm tổng thành 65, vẫn dưới 80.

```bash
git checkout main && git pull
git merge --no-ff develop -m "Release v4.0: merge develop into main"        # M15
git push
git tag -a v4.0 -m "VetClinic v4.0"
git push origin v4.0
```

---

## 8. Lưu ý

- **Không cần build hay test** giữa các commit. Vài commit trung gian có thể chưa biên dịch được một mình (ví dụ parent `pom.xml` đã liệt kê đủ module từ K1, `VisitServiceImpl` dùng hàng đợi Redis tới K14 mới có). Sau M14, mã giống hệt bản đã test.
- Luôn `git status --short` trước khi commit. Thấy file lạ (ví dụ `node_modules/`, `target/`, `.env`) thì **không** commit; `.gitignore` từ K0 đã chặn các thư mục này.
- Ba người có thể làm song song trong cùng một đợt. Chỉ cần merge theo đúng thứ tự cột cuối bảng mục 1. Người merge sau chạy `git checkout develop && git pull` trước khi merge.
- Muốn có Pull Request trên GitHub thay cho lệnh `git merge`: push nhánh, mở PR vào `develop`, người còn lại review, bấm **Create a merge commit** (không chọn Squash / Rebase để giữ đúng số commit).
- Lỡ commit nhầm file khi **chưa push**: `git reset --soft HEAD~1`, sửa lại, commit lại. Đã push rồi thì thêm một commit sửa, đừng force-push lên `develop` / `main`.
