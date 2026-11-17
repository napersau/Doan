# TÀI LIỆU QUY CHUẨN CÁC KIỂU DỮ LIỆU CỐ ĐỊNH (ENUM TYPES SPECIFICATION)
## HỆ THỐNG QUẢN LÝ PHÒNG KHÁM THÚ Y

> **Phiên bản:** 3.0 – Đồng bộ với lược đồ CSDL v3.0 (`schema_init.sql`, 27 bảng), cập nhật ngày 10/11/2026; **cập nhật 17/11/2026**: thêm 2 enum của phiếu xếp hàng (`QueueType`, `TicketStatus`) và giá trị `REFUNDED` cho hóa đơn dịch vụ.  
> **Mục đích:** Tài liệu này tổng hợp toàn bộ các trường dữ liệu có tập giá trị cố định (Domain Constraints / ENUM) trong hệ thống, nhằm chuẩn hóa việc lưu trữ trong Cơ sở dữ liệu MySQL 8.0, lập trình Backend (Java 21 / Spring Boot) và hiển thị giao diện Frontend React.

---

## TỔNG HỢP DANH SÁCH 12 KIỂU ENUM LƯU TRONG CƠ SỞ DỮ LIỆU

| STT | Tên ENUM | Bảng & Cột áp dụng | Danh sách các giá trị hợp lệ |
| :---: | :--- | :--- | :--- |
| **1** | `RoleCode` | `roles.code` | `'ADMIN'`, `'VETERINARIAN'`, `'RECEPTIONIST'`, `'LAB_TECHNICIAN'`, `'IMAGING_TECHNICIAN'`, `'PET_OWNER'` |
| **2** | `UserStatus` | `users.status` | `'ACTIVE'`, `'INACTIVE'`, `'LOCKED'` |
| **3** | `PetGender` | `pets.gender` | `'MALE'`, `'FEMALE'`, `'UNKNOWN'` |
| **4** | `BookingChannel` | `appointments.booking_channel` | `'ONLINE'`, `'PHONE'`, `'WALK_IN'` |
| **5** | `AppointmentStatus` | `appointments.status`; `appointment_status_histories.from_status`, `to_status` | `'PENDING'`, `'CONFIRMED'`, `'CHECKED_IN'`, `'CANCELLED'`, `'NO_SHOW'` |
| **6** | `VisitStatus` | `pet_visits.status`; `pet_visit_status_histories.from_status`, `to_status` | `'WAITING'`, `'IN_PROGRESS'`, `'WAITING_RESULTS'`, `'RESULTS_READY'`, `'WAITING_PAYMENT'`, `'COMPLETED'`, `'CANCELLED'` |
| **7** | `ServiceCategory` | `services.category` | `'EXAMINATION'`, `'VACCINATION'`, `'DEWORMING'`, `'EXTERNAL_PARASITE'`, `'INJECTION_INFUSION'`, `'LABORATORY'`, `'IMAGING'`, `'SURGERY'`, `'PROCEDURE'` |
| **8** | `ServiceDetailStatus` | `visit_service_details.status`; `visit_service_detail_histories.from_status`, `to_status` | `'PENDING'`, `'IN_PROGRESS'`, `'COMPLETED'`, `'CANCELLED'` |
| **9** | `PaymentStatus` | `service_invoices.payment_status`; `medicine_invoices.payment_status` | `'PENDING'`, `'PAID'`, `'CANCELLED'`, `'REFUNDED'` (`REFUNDED` chỉ có ở `service_invoices`) |
| **10** | `InventoryTransactionType` | `inventory_transactions.transaction_type` | `'IMPORT'`, `'EXPORT_SALE'`, `'ADJUSTMENT'` |
| **11** | `QueueType` | `queue_tickets.queue_type` | `'DOCTOR'`, `'LABORATORY'`, `'IMAGING'` |
| **12** | `TicketStatus` | `queue_tickets.status` | `'WAITING'`, `'IN_SERVICE'`, `'DONE'`, `'CANCELLED'` |

**Enum chỉ dùng ở tầng ứng dụng (không có cột trong CSDL):** `LabEvaluation` (`'LOW'`, `'NORMAL'`, `'HIGH'`) – xem Mục 13.

---

## CHI TIẾT TỪNG KIỂU DỮ LIỆU ENUM

### 1. `RoleCode` - Mã vai trò người dùng
- **Bảng:** `roles` | **Cột:** `code`
- **Cú pháp CSDL:** `ENUM('ADMIN', 'VETERINARIAN', 'RECEPTIONIST', 'LAB_TECHNICIAN', 'IMAGING_TECHNICIAN', 'PET_OWNER') NOT NULL UNIQUE`
- **Ý nghĩa các giá trị:**
  - `'ADMIN'`: Quản trị viên hệ thống (cấu hình danh mục, quản lý người dùng, xem thống kê).
  - `'VETERINARIAN'`: Bác sĩ thú y khám chính (khám lâm sàng, chỉ định cận lâm sàng, chẩn đoán xác định, kê đơn).
  - `'RECEPTIONIST'`: Lễ tân / Thu ngân (tiếp đón, điều phối phân công bác sĩ, thu phí khám / phí cận lâm sàng để phát phiếu xếp hàng, thu tiền hóa đơn dịch vụ khi chốt ca, bán thuốc theo đơn).
  - `'LAB_TECHNICIAN'`: Bác sĩ / Kỹ thuật viên Xét nghiệm (tiếp nhận mẫu bệnh phẩm, phân tích huyết học/sinh hóa và nhập kết quả).
  - `'IMAGING_TECHNICIAN'`: Bác sĩ / Kỹ thuật viên Chẩn đoán hình ảnh (thực hiện siêu âm, chụp X-quang, tải ảnh và kết luận hình thái).
  - `'PET_OWNER'`: Khách hàng / Chủ thú cưng (đặt lịch trực tuyến, tra cứu hồ sơ bệnh án).

---

### 2. `UserStatus` - Trạng thái tài khoản người dùng
- **Bảng:** `users` | **Cột:** `status`
- **Cú pháp CSDL:** `ENUM('ACTIVE', 'INACTIVE', 'LOCKED') NOT NULL DEFAULT 'ACTIVE'`
- **Ý nghĩa các giá trị:**
  - `'ACTIVE'`: Tài khoản đang hoạt động bình thường.
  - `'INACTIVE'`: Tài khoản chưa kích hoạt hoặc đã ngừng sử dụng (nhân viên đã nghỉ việc).
  - `'LOCKED'`: Tài khoản bị tạm khóa do vi phạm hoặc bảo mật (nhập sai mật khẩu nhiều lần).
- **Ghi chú:** Số Bác sĩ `ACTIVE` được dùng để tính sức chứa khung giờ đặt lịch (`Capacity = N_active × 2`).

---

### 3. `PetGender` - Giới tính thú cưng
- **Bảng:** `pets` | **Cột:** `gender`
- **Cú pháp CSDL:** `ENUM('MALE', 'FEMALE', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN'`
- **Ý nghĩa các giá trị:**
  - `'MALE'`: Thú cưng đực.
  - `'FEMALE'`: Thú cưng cái.
  - `'UNKNOWN'`: Chưa xác định rõ giới tính (thú non, chim, bò sát).

---

### 4. `BookingChannel` - Kênh đặt lịch hẹn
- **Bảng:** `appointments` | **Cột:** `booking_channel`
- **Cú pháp CSDL:** `ENUM('ONLINE', 'PHONE', 'WALK_IN') NOT NULL DEFAULT 'ONLINE'`
- **Ý nghĩa các giá trị:**
  - `'ONLINE'`: Khách hàng tự đặt trên Portal / Ứng dụng di động (lịch tạo ra ở trạng thái `PENDING`).
  - `'PHONE'`: Khách hàng gọi điện thoại đến phòng khám và Lễ tân tạo lịch giúp (lịch tạo ra ở trạng thái `CONFIRMED`).
  - `'WALK_IN'`: Khách hàng đến trực tiếp quầy để đặt lịch cho ngày khác (lịch tạo ra ở trạng thái `CONFIRMED`).

---

### 5. `AppointmentStatus` - Trạng thái lịch hẹn khám
- **Bảng & Cột:** `appointments.status`; `appointment_status_histories.from_status`, `appointment_status_histories.to_status`
- **Cú pháp CSDL:** `ENUM('PENDING', 'CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'NO_SHOW') NOT NULL DEFAULT 'PENDING'` (riêng `from_status` cho phép `NULL` khi lịch vừa được tạo)
- **Ý nghĩa các giá trị:**
  - `'PENDING'`: Yêu cầu vừa gửi từ Portal, chờ Lễ tân xác nhận.
  - `'CONFIRMED'`: Lễ tân đã gọi điện xác nhận, giữ khung giờ cho khách.
  - `'CHECKED_IN'`: Khách đã đưa thú cưng đến phòng khám (chuyển tiếp sang tạo `Pet Visit`).
  - `'CANCELLED'`: Lịch bị hủy (bởi khách hoặc do phòng khám từ chối). Lý do hủy ghi ở `appointment_status_histories.notes`.
  - `'NO_SHOW'`: Khách không đến sau quá 30 phút so với giờ bắt đầu khung hẹn (`start_time + 30 phút`) đã xác nhận.

---

### 6. `VisitStatus` - Vòng đời Đợt khám bệnh (Chu kỳ Y tế Bất đồng bộ)
- **Bảng & Cột:** `pet_visits.status`; `pet_visit_status_histories.from_status`, `pet_visit_status_histories.to_status`
- **Cú pháp CSDL:** `ENUM('WAITING', 'IN_PROGRESS', 'WAITING_RESULTS', 'RESULTS_READY', 'WAITING_PAYMENT', 'COMPLETED', 'CANCELLED') NOT NULL DEFAULT 'WAITING'` (riêng `from_status` cho phép `NULL`)
- **Ý nghĩa các giá trị:**
  - `'WAITING'`: Đã tiếp đón, chờ khám Giai đoạn 1. Gồm 2 trường hợp: **chờ nộp phí khám** (chưa có phiếu xếp hàng, hiện ở màn hình Thu ngân) hoặc **đã có phiếu `DOCTOR`** trong hàng đợi (của Bác sĩ được chọn, hoặc danh sách chờ phân công nếu `doctor_id IS NULL`). Thứ tự hàng đợi lấy từ `queue_tickets` (`priority DESC, issued_at`), không lưu trên `pet_visits`.
  - `'IN_PROGRESS'`: Bác sĩ đang trực tiếp khám (Giai đoạn 1 khám sơ bộ, hoặc Giai đoạn 2 kết luận chẩn đoán).
  - `'WAITING_RESULTS'`: Bác sĩ đã ra chỉ định cận lâm sàng (Bác sĩ tạm giải phóng để khám ca khác). Khách ra quầy nộp tiền xét nghiệm / CĐHA, thu xong mới được phát phiếu vào hàng đợi phòng tương ứng.
  - `'RESULTS_READY'`: Phòng Cận lâm sàng đã nhập đủ kết quả (hoặc dịch vụ bị hủy bớt); hệ thống phát **phiếu `DOCTOR` ưu tiên** (`priority = 1`, không thu tiền) cho Bác sĩ khám chính, đứng trước ca thường, sau ca cấp cứu.
  - `'WAITING_PAYMENT'`: Bác sĩ đã chốt chuyên môn và khóa chỉnh sửa, chờ Thu ngân thu các hóa đơn dịch vụ còn `PENDING` của ca khám (chủ yếu dịch vụ làm tại phòng, phí của ca cấp cứu; phí khám, XN, CĐHA đã thu trước).
  - `'COMPLETED'`: Tất cả hóa đơn dịch vụ của ca khám đã `PAID` (hoặc `CANCELLED` / `REFUNDED`), lưu vào lịch sử bệnh án vĩnh viễn. Việc khách mua thuốc tại phòng khám là tùy chọn và không ảnh hưởng tới trạng thái này. Ngoại lệ EX-11: ca đóng khi khách bỏ về, các hóa đơn dịch vụ còn `PENDING` được giữ làm công nợ.
  - `'CANCELLED'`: Ca khám bị hủy bỏ trước hoặc trong khi khám (khách bỏ về, chuyển viện khẩn cấp). Lý do hủy ghi ở `pet_visit_status_histories.notes`. Phí khám đã thu **không hoàn** (hóa đơn giữ `PAID`). Phí khám đã thu **không hoàn** (hóa đơn giữ `PAID`).
- **Ghi chú:** Thời điểm bắt đầu khám, chốt ca, hoàn tất và người thao tác được lấy từ `pet_visit_status_histories` (không lưu thành cột riêng trên `pet_visits`).

---

### 7. `ServiceCategory` - Phân nhóm dịch vụ kỹ thuật
- **Bảng:** `services` | **Cột:** `category`
- **Cú pháp CSDL:** `ENUM('EXAMINATION', 'VACCINATION', 'DEWORMING', 'EXTERNAL_PARASITE', 'INJECTION_INFUSION', 'LABORATORY', 'IMAGING', 'SURGERY', 'PROCEDURE') NOT NULL`
- **Ý nghĩa các giá trị:**
  - `'EXAMINATION'`: Khám lâm sàng tổng quát / chuyên sâu. Tạo tự động lúc check-in; **thu trước** – nộp phí xong mới được phát phiếu vào hàng đợi Bác sĩ. Tạo tự động lúc check-in; **thu trước** – nộp phí xong mới được phát phiếu vào hàng đợi Bác sĩ.
  - `'VACCINATION'`: Tiêm phòng vaccine (bắt buộc khám sàng lọc trước tiêm).
  - `'DEWORMING'`: Tẩy giun sán đường ruột định kỳ.
  - `'EXTERNAL_PARASITE'`: Trị ký sinh trùng ngoài da (ve, bọ chét, ghẻ).
  - `'INJECTION_INFUSION'`: Công tiêm thuốc điều trị, công truyền dịch.
  - `'LABORATORY'`: Các gói xét nghiệm huyết học, sinh hóa, nước tiểu. Các chỉ số của từng gói được khai báo ở bảng `service_lab_test_items`. Dịch vụ thuộc nhóm này **thu trước**: khách nộp tiền xong mới được phát phiếu vào **hàng đợi phòng Xét nghiệm**.
  - `'IMAGING'`: Chẩn đoán hình ảnh: Siêu âm ổ bụng, chụp phim X-quang kỹ thuật số (tải ảnh đính kèm). Dịch vụ thuộc nhóm này **thu trước**: khách nộp tiền xong mới được phát phiếu vào **hàng đợi phòng CĐHA**.
  - `'SURGERY'`: Phẫu thuật ngoại khoa (triệt sản, mổ đẻ, mổ u).
  - `'PROCEDURE'`: Thủ thuật điều trị (thay băng, rửa vết thương, cắt chỉ, nẹp xương).
- **Ghi chú:** Doanh thu theo nhóm dịch vụ được thống kê bằng cách nhóm theo cột này qua VIEW `v_service_invoice_amounts`. Việc thu trước hay thu sau **không suy ra từ nhóm** mà đọc cột `services.requires_prepayment` (seed: `TRUE` cho `EXAMINATION`, `LABORATORY`, `IMAGING`; `FALSE` cho các nhóm làm tại phòng), để đổi chính sách chỉ cần sửa danh mục. Việc thu trước hay thu sau **không suy ra từ nhóm** mà đọc cột `services.requires_prepayment` (seed: `TRUE` cho `EXAMINATION`, `LABORATORY`, `IMAGING`; `FALSE` cho các nhóm làm tại phòng), để đổi chính sách chỉ cần sửa danh mục.

---

### 8. `ServiceDetailStatus` - Trạng thái thực hiện chỉ định dịch vụ kỹ thuật
- **Bảng & Cột:** `visit_service_details.status`; `visit_service_detail_histories.from_status`, `visit_service_detail_histories.to_status`
- **Cú pháp CSDL:** `ENUM('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED') NOT NULL DEFAULT 'PENDING'` (riêng `from_status` cho phép `NULL`)
- **Ý nghĩa các giá trị:**
  - `'PENDING'`: Dịch vụ đã được chỉ định, chưa thực hiện. Với dịch vụ Lab / CĐHA có 2 giai đoạn: **chờ nộp tiền** (hóa đơn `PENDING`, chưa vào hàng đợi) và **đã có phiếu** (thu xong, dịch vụ thuộc một phiếu phòng qua `queue_ticket_items`). Các dịch vụ của **cùng một đợt khám trong cùng phòng** (vd. siêu âm + X-quang) gộp vào **một phiếu**: gọi một lần. Kỹ thuật viên bấm "Gọi" ở phiếu bất kỳ; gọi **không đổi trạng thái và thứ tự** (chỉ cập nhật `queue_tickets.last_called_at`), khách vắng thì gọi phiếu khác, khách quay lại thì gọi lại (EX-06). Dịch vụ khám tạo lúc check-in cũng ở `PENDING` cho đến khi Bác sĩ bấm "Bắt đầu khám".
  - `'IN_PROGRESS'`: Kỹ thuật viên đã tiếp nhận thú cưng / lấy mẫu bệnh phẩm, máy đang phân tích tự động hoặc thú cưng đang trong phòng siêu âm/X-quang.
  - `'COMPLETED'`: Kỹ thuật viên / Bác sĩ CĐHA đã nhập đầy đủ kết quả đo đạc hoặc tải tệp ảnh chẩn đoán lên và nhấn xác nhận hoàn tất.
  - `'CANCELLED'`: Chỉ định bị hủy bỏ (chủ nuôi từ chối / không nộp tiền, bác sĩ thay đổi hướng xử lý, thiết bị sự cố). Hóa đơn dịch vụ tương ứng: chưa thu → `CANCELLED`; đã thu mà khách tự hủy → giữ `PAID`; đã thu mà phòng khám không làm được → `REFUNDED`.

---

### 9. `PaymentStatus` - Trạng thái thanh toán hóa đơn
- **Bảng & Cột:** `service_invoices.payment_status` (hóa đơn từng dịch vụ); `medicine_invoices.payment_status` (hóa đơn thuốc)
- **Cú pháp CSDL:** `service_invoices`: `ENUM('PENDING', 'PAID', 'CANCELLED', 'REFUNDED') NOT NULL DEFAULT 'PENDING'`; `medicine_invoices`: `ENUM('PENDING', 'PAID', 'CANCELLED') NOT NULL DEFAULT 'PENDING'` (hóa đơn thuốc không có hoàn tiền vì thu tiền và trừ kho cùng một giao dịch).
- **Ý nghĩa các giá trị:**
  - `'PENDING'`: Hóa đơn vừa tạo, đang chờ thu tiền. Hóa đơn phí khám tạo lúc check-in; hóa đơn dịch vụ khác tạo ngay khi Bác sĩ chỉ định; hóa đơn thuốc được tạo khi khách chọn mua thuốc tại phòng khám.
  - `'PAID'`: Khách hàng đã thanh toán đủ tiền bên ngoài quầy, Thu ngân đã xác nhận (ghi nhận `cashier_id`, `paid_at`). Với phí khám và dịch vụ XN / CĐHA, việc chuyển `PAID` **phát phiếu xếp hàng** trong cùng giao dịch. Với hóa đơn thuốc, việc chuyển `PAID` và trừ tồn kho diễn ra trong cùng một giao dịch.
  - `'CANCELLED'`: Hóa đơn chưa thu bị hủy bỏ (dịch vụ bị hủy theo EX-08, khách đổi ý không mua thuốc, hoặc sai sót nghiệp vụ).
  - `'REFUNDED'` (chỉ `service_invoices`): Đã hoàn tiền vì **phòng khám không thực hiện được** dịch vụ XN / CĐHA đã thu (thiết bị hỏng, không lấy được mẫu, Bác sĩ hủy chỉ định trước khi làm). **Phí khám không hoàn**; khách tự bỏ về hoặc tự hủy dịch vụ đã thu thì hóa đơn giữ `PAID`. Không tính vào doanh thu.

---

### 10. `InventoryTransactionType` - Loại giao dịch biến động kho dược
- **Bảng:** `inventory_transactions` | **Cột:** `transaction_type`
- **Cú pháp CSDL:** `ENUM('IMPORT', 'EXPORT_SALE', 'ADJUSTMENT') NOT NULL`
- **Ý nghĩa các giá trị:**
  - `'IMPORT'`: Nhập thêm thuốc mới vào kho (`quantity` dương, tăng tồn kho).
  - `'EXPORT_SALE'`: Xuất kho khi bán thuốc theo hóa đơn thuốc đã thanh toán (`quantity` âm, giảm tồn kho). Bắt buộc có `medicine_invoice_id` (kiểm tra ở tầng ứng dụng); với `IMPORT` và `ADJUSTMENT` cột này để `NULL`.
  - `'ADJUSTMENT'`: Cân chỉnh kho sau kiểm kê thực tế phát hiện thừa/thiếu, hao hụt hoặc hết hạn sử dụng (`quantity` dương hoặc âm).
- **Ghi chú:** Tồn kho sau mỗi giao dịch không lưu thành cột, được tính bằng tổng lũy kế `SUM(quantity) OVER (PARTITION BY medicine_id ORDER BY created_at, id)`.

---

### 11. `QueueType` - Loại hàng đợi của phiếu xếp hàng
- **Bảng:** `queue_tickets` | **Cột:** `queue_type`
- **Cú pháp CSDL:** `ENUM('DOCTOR', 'LABORATORY', 'IMAGING') NOT NULL`
- **Ý nghĩa các giá trị:**
  - `'DOCTOR'`: Phiếu vào hàng đợi phòng Bác sĩ. Phát khi thu xong phí khám (`priority = 0`), khi ca cấp cứu được tiếp đón (`priority = 2`), hoặc khi ca có đủ kết quả CLS (`priority = 1`, không thu tiền). `doctor_id` = Bác sĩ được chọn; `NULL` = danh sách chờ phân công.
  - `'LABORATORY'`: Phiếu vào hàng đợi phòng Xét nghiệm. Phát khi thu xong tiền các dịch vụ `LABORATORY` của đợt khám; các dịch vụ được gom qua `queue_ticket_items`.
  - `'IMAGING'`: Phiếu vào hàng đợi phòng CĐHA. Phát khi thu xong tiền các dịch vụ `IMAGING` (vd. siêu âm + X-quang gộp một phiếu).
- **Ghi chú:** Số in trên phiếu (`ticket_no`) đánh lại từ 1 mỗi ngày theo từng loại hàng đợi (`UNIQUE (queue_type, service_date, ticket_no)`).

---

### 12. `TicketStatus` - Trạng thái phiếu xếp hàng
- **Bảng:** `queue_tickets` | **Cột:** `status`
- **Cú pháp CSDL:** `ENUM('WAITING', 'IN_SERVICE', 'DONE', 'CANCELLED') NOT NULL DEFAULT 'WAITING'`
- **Ý nghĩa các giá trị:**
  - `'WAITING'`: Đang chờ gọi. Hàng đợi sắp `priority DESC, issued_at, id`. Bấm "Gọi" / "Gọi lại" chỉ cập nhật `last_called_at`, không đổi trạng thái hay thứ tự.
  - `'IN_SERVICE'`: Bác sĩ / KTV đã bấm "Bắt đầu" khi bệnh nhân vào phòng (kích hoạt T02 / T07 của đợt khám hoặc `PENDING → IN_PROGRESS` của dịch vụ). Mỗi Bác sĩ có tối đa 1 phiếu `IN_SERVICE` (= trạng thái `BUSY`).
  - `'DONE'`: Đã phục vụ xong lượt (Bác sĩ chỉ định CLS / chốt ca; mọi dịch vụ của phiếu phòng đã xong hoặc hủy).
  - `'CANCELLED'`: Phiếu bị hủy (khách bỏ về, hủy hết dịch vụ của phiếu, hủy ca chuyển viện).
- **Ghi chú:** Vòng đời ở SM-07 (`THIET_KE_SO_DO_TRANG_THAI_STATE_MACHINE.md`).

---

### 13. `LabEvaluation` - Đánh giá chỉ số xét nghiệm (chỉ dùng ở tầng ứng dụng)
- **Lưu trữ:** **Không có cột trong CSDL.** Giá trị được tính khi hiển thị từ `lab_test_results.result_value` so với `min_value_snapshot` và `max_value_snapshot`.
- **Khai báo:** Enum Java `LabEvaluation` trong `clinical-service` và kiểu tương ứng ở Frontend.
- **Quy tắc tính & hiển thị:**
  - `'LOW'`: `result_value < min_value_snapshot`. Hiển thị cảnh báo vàng.
  - `'NORMAL'`: `min_value_snapshot <= result_value <= max_value_snapshot`. Hiển thị xanh lá.
  - `'HIGH'`: `result_value > max_value_snapshot`. Hiển thị cảnh báo đỏ nguy hiểm.
- **Lý do không lưu:** Đây là thuộc tính dẫn xuất; nếu lưu, khi Kỹ thuật viên sửa lại giá trị đo mà không cập nhật nhãn thì dữ liệu sẽ mâu thuẫn.

---

## CÁC ENUM ĐÃ LOẠI BỎ SO VỚI PHIÊN BẢN 2.0

| Enum | Lý do loại bỏ | Thay thế bằng |
| :--- | :--- | :--- |
| `PrescriptionStatus` (`PRESCRIBED`, `DISPENSED`, `CANCELLED`) | Đơn thuốc chỉ còn là chỉ định chuyên môn; khách được mua tại phòng khám hoặc mua ngoài. | Việc mua/xuất thuốc thể hiện qua `medicine_invoices.payment_status`. Chống xuất trùng nhờ `medicine_invoices.prescription_id` UNIQUE. |
| `InvoiceItemType` (`SERVICE`, `MEDICINE`) | Bỏ bảng hóa đơn gộp `invoice_items`. | Doanh thu dịch vụ và doanh thu thuốc tách tự nhiên qua 2 bảng `service_invoices` và `medicine_invoices`. |
| `LabEvaluation` (lưu CSDL) | Thuộc tính dẫn xuất. | Chuyển thành enum chỉ dùng ở tầng ứng dụng (Mục 13). |

## BẢNG ĐỐI CHIẾU SỐ THỨ TỰ ENUM (v2.0 → v3.0)

| Enum | Số cũ (v2.0) | Số mới (v3.0) |
| :--- | :---: | :---: |
| `RoleCode`, `UserStatus`, `PetGender`, `BookingChannel`, `AppointmentStatus`, `VisitStatus`, `ServiceCategory` | 1 – 7 | 1 – 7 (giữ nguyên) |
| `ServiceDetailStatus` | 12 | 8 |
| `PaymentStatus` | 11 | 9 |
| `InventoryTransactionType` | 10 | 10 |
| `LabEvaluation` | 8 | 13 (chỉ ở ứng dụng; 11 trước ngày 16/11) |
| `QueueType`, `TicketStatus` | – | 11, 12 (thêm 16/11) |
| `PrescriptionStatus` | 9 | Đã loại bỏ |
| `InvoiceItemType` | 13 | Đã loại bỏ |

> Các tài liệu khác có trích dẫn số thứ tự enum (ví dụ bảng tổng kết trong `THIET_KE_SO_DO_TRANG_THAI_STATE_MACHINE.md`) cần cập nhật theo bảng đối chiếu này.

> **Rà soát 11/11:** không đổi số lượng enum. `ServiceDetailStatus` và `VisitStatus` chỉ cập nhật mô tả theo mô hình hàng đợi riêng của từng Bác sĩ và cơ chế "Đẩy lùi 1 bậc"; cột `visit_service_detail_histories.action` (VARCHAR, không phải enum) đổi `POSTPONED` / `RECALL` thành `SKIPPED`.

> **Cập nhật 13/11/2026:** hàng đợi dùng "Gọi" theo từng dòng thay cho "Đẩy lùi 1 bậc" (xem State Machine §2.4). Giá trị `SKIPPED` của `visit_service_detail_histories.action` đã bị bỏ (không còn nơi nào ghi); cột `action` còn 5 giá trị `ORDERED`, `START_PROCESSING`, `SAMPLE_COLLECTED`, `COMPLETED`, `CANCELLED`. Không đổi số lượng enum.

> **Cập nhật 16–17/11/2026 (góp ý GVHD lần 2):** tách hàng đợi khỏi hồ sơ khám thành bảng phiếu xếp hàng `queue_tickets` → thêm 2 enum `QueueType` (#11) và `TicketStatus` (#12), tổng **12 enum** lưu CSDL; `LabEvaluation` chuyển sang Mục 13. `PaymentStatus` thêm `REFUNDED` (chỉ dùng ở `service_invoices`, khi phòng khám không thực hiện được dịch vụ đã thu). Cột `queue_order` ở `pet_visits` / `visit_service_details` đã bỏ; thứ tự hàng đợi là `priority DESC, issued_at` của phiếu.
