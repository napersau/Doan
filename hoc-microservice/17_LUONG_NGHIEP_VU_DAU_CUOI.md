# 17. Ghép tất cả lại: theo chân một ca khám từ đặt lịch đến báo cáo

File này đi qua **một ca khám thật** của chó **Lu** (chủ: Hoàng Thị Lan, tài khoản `khach_lan`) và chỉ ra ở **mỗi bước**: request đi qua
đâu, service nào làm gì, dùng kỹ thuật nào (kèm số file để đọc lại).

Ký hiệu:
- `──HTTP──►` gọi đồng bộ (FE → Gateway → service, hoặc Feign giữa service)
- `══sự kiện══►` bất đồng bộ qua RabbitMQ
- `[TX]` một giao dịch MySQL

---

## Khái niệm cần biết trước: Saga

Một nghiệp vụ như "khám bệnh" đi qua **nhiều service**, mỗi service có **CSDL riêng** (file 05). Không có giao dịch nào bao được cả 5 CSDL.

**Saga** = chia nghiệp vụ dài thành **chuỗi bước**, mỗi bước là **một giao dịch cục bộ** trong một service, các bước nối với nhau bằng **sự
kiện**. Có hai kiểu:
- **Orchestration** (nhạc trưởng): một service trung tâm ra lệnh cho từng bước.
- **Choreography** (vũ đạo): không ai chỉ huy; mỗi service **nghe** sự kiện và tự làm phần của mình, rồi phát sự kiện tiếp.

VetClinic dùng **choreography**. Bước sau lỗi thì không "rollback" được bước trước (đã commit ở service khác) mà phải làm **hành động bù**
(compensation), ví dụ hoàn tiền thì phát `ServiceInvoiceRefundedEvent` để báo cáo ghi dòng âm.

---

## Bước 1. Khách đặt lịch online

```
FE (khach_lan) ──POST /api/v1/appointments──► Gateway ──► appointment-service
                                               (kiểm JWT, gắn X-User-Id)
appointment:
  1. Feign ──► clinical  GET /internal/pets/{Lu}          kiểm tra Lu thuộc chị Lan, lấy tên để chụp snapshot
  2. Feign ──► auth      GET /internal/users/active-doctors/count   → 5 bác sĩ → sức chứa 10
  3. GET_LOCK("vetclinic:appt-slot:2026-11-30T08:00"), GET_LOCK("vc:apt-pet:<Lu>:2026-11-30")
  4. [TX] đếm lịch khung 08:00 (< 10) → INSERT appointments (PENDING, snapshot tên)
          + INSERT appointment_status_histories
          + INSERT outbox_events (AppointmentBookedEvent)
  5. nhả khóa, trả 201
OutboxRelay ══appointment.booked══► notification ──► /topic/role/RECEPTIONIST "Lịch hẹn mới chờ duyệt"
                                                 ──► /user/queue (chị Lan) "Đã ghi nhận lịch hẹn"
                                                 ──► email xác nhận (nếu có email)
```

| Kỹ thuật | File |
|---|---|
| Gateway kiểm JWT, gắn header | 03, 04 |
| Feign chỉ đọc, gọi ngoài giao dịch, timeout 2s, Circuit Breaker | 06, 07 |
| Snapshot tên thú cưng / chủ nuôi | 05 |
| `GET_LOCK` chống đặt quá sức chứa | 14 |
| Outbox | 09 |
| WebSocket | 13 |

## Bước 2. Lễ tân duyệt lịch

```
FE (letan_hoa) ──POST /api/v1/appointments/{id}/confirm──► appointment
  [TX] PENDING → CONFIRMED + lịch sử + outbox AppointmentStatusChangedEvent
══appointment.status_changed══► notification ──► chị Lan: "Lịch hẹn đã được xác nhận"
```

Chuyển trạng thái dùng `POST /{id}/<hành động>`, mỗi lần đổi đều ghi bảng lịch sử **cùng giao dịch**.

## Bước 3. Ngày hẹn: Lễ tân tiếp đón (check-in)

```
FE (letan_hoa) ──POST /api/v1/clinical/pet-visits/check-in──► clinical-service
clinical:
  1. Feign ──► appointment GET /internal/appointments/{id}   lịch phải CONFIRMED và đúng hôm nay
  2. GET_LOCK("vetclinic:visit-pet:<Lu>")                   một thú cưng không bị tiếp đón hai lần cùng lúc
  3. [TX] INSERT pet_visits (WAITING)
          + lịch sử trạng thái
          + dịch vụ "Khám lâm sàng" + hóa đơn phí khám (PENDING, chụp giá 100.000đ)
          + outbox VisitCheckedInEvent
══visit.checked_in══►  appointment     : lịch hẹn → CHECKED_IN   (appointment TỰ sửa CSDL của mình)
                   ►  reporting       : thêm dòng visit_facts
                   ►  notification    : báo các bên
                   ►  clinical        : QueueIssuerListener – ca thường thì CHƯA phát phiếu (phải nộp phí trước); ca cấp cứu thì phát ngay
```

**Điểm then chốt của microservice:** clinical không ghi vào CSDL appointment. Nó chỉ "báo tin", appointment tự cập nhật (file 08). Mỗi bên
nhận chống trùng bằng `processed_events` (file 10).

## Bước 4. Thu ngân thu phí khám → phát phiếu xếp hàng

```
FE (thu ngân) ──POST /api/v1/clinical/pet-visits/{id}/service-invoices/confirm-payment──► clinical
  [TX] khóa dòng pet_visits (FOR UPDATE)
       hóa đơn phí khám → PAID (@Version chống thu trùng)
       outbox ServiceInvoicePaidEvent (kèm paidAt, các dòng, số tiền)
  → trả về ngay. Màn hình thu ngân hiện "Đang cấp số thứ tự…"

══invoice.service_paid══► clinical (QueueIssuerListener):
       [TX] khóa pet_visits, đọc lại trạng thái (ca chưa hủy, chưa có phiếu)
       Redis: issue_ticket.lua  → INCR số = 42, ZADD vào queue:20261130:DOCTOR:UNASSIGNED
                                 score = 2×10^13 + paidAt
       gửi thẳng QueueTicketIssuedEvent ══► notification ──► màn hình: "Số 42"
                        ►  reporting   : doanh thu dịch vụ +100.000đ (revenue_facts + daily_revenue_summaries)
                        ►  notification
```

| Câu hỏi | Trả lời | File |
|---|---|---|
| Vì sao không phát phiếu ngay trong hàm thu tiền? | Redis không chung giao dịch với MySQL (ghi kép). Đưa ra sau sự kiện trong outbox thì "đã thu tiền là chắc chắn có phiếu" | 09, 11 |
| RabbitMQ sập đúng lúc thu tiền? | Thu tiền vẫn thành công, sự kiện nằm `PENDING` trong outbox, RabbitMQ lên lại thì gửi | 09 (EX-18) |
| Sự kiện tới hai lần? | Khóa `queue:dedupe` trong Lua trả lại phiếu cũ; reporting có `processed_events` | 10, 11 (EX-19) |
| Hai thu ngân bấm cùng lúc? | Khóa dòng `pet_visits`, người sau thấy hóa đơn đã `PAID` (409) | 14 (EX-16) |

## Bước 5. Bác sĩ gọi khách và khám (giai đoạn 1)

```
Lễ tân phân công Bác sĩ Tiến cho ca → Feign ──► auth (bác sĩ còn ACTIVE? kiểm TẠI THỜI ĐIỂM GHI, không fallback)
                                    → Redis move_ticket.lua: phiếu 42 sang queue:...:DOCTOR:<bs_tien>, GIỮ số và thứ tự
Bác sĩ ──POST /api/v1/clinical/queue-tickets/call-next──► clinical
       Redis call_ticket.lua (lấy phiếu đầu hàng, ghi calledCount)
       gửi thẳng PatientCalledEvent ══► notification ──► "Mời Lu vào phòng P101"
Bác sĩ ──POST /api/v1/clinical/pet-visits/{id}/start-exam──► [TX] pet_visits WAITING → IN_PROGRESS + lịch sử; Redis start_ticket sau commit
```

## Bước 6. Chỉ định xét nghiệm, thu tiền, làm xét nghiệm

```
Bác sĩ nhập sinh hiệu (bắt buộc cân nặng, nhiệt độ) → chỉ định "Xét nghiệm máu"
  [TX] dịch vụ + hóa đơn PENDING; pet_visits → WAITING_RESULTS
Thu ngân thu tiền xét nghiệm → outbox ServiceInvoicePaidEvent
══► QueueIssuerListener: phiếu queue:...:LABORATORY:ALL (dịch vụ cùng phòng gộp một phiếu)
KTV (ktv_lab_minh) gọi phiếu → nhập kết quả từng chỉ số → hoàn tất
  [TX] khóa pet_visits TRƯỚC, rồi đếm dịch vụ CLS còn dở = 0 → RESULTS_READY + outbox ParaclinicalResultsReadyEvent
══visit.results_ready══► clinical    : phiếu DOCTOR priority 1 cho bs_tien (đứng sau cấp cứu, trước ca thường)
                       ► notification: /user/queue của bs_tien "Ca VIS-... đã có kết quả"
```

Khóa dòng trước khi đếm để hai KTV hoàn tất cùng lúc không làm ca kẹt (file 14).

## Bước 7. Khám giai đoạn 2, kê đơn, chốt ca

```
Bác sĩ POST .../{id}/start-phase2 → IN_PROGRESS → nhập chẩn đoán, kê đơn (PUT /api/v1/clinical/pet-visits/{id}/prescription)
  (kê đơn: Feign ──► billing GET /internal/medicines để hiện tên thuốc, tồn kho; vượt tồn chỉ cảnh báo)
Bác sĩ chốt ca (POST /api/v1/clinical/pet-visits/{id}/close-visit):
  [TX] pet_visits → WAITING_PAYMENT (hồ sơ bị khóa) + lịch sử
       + outbox PrescriptionCreatedEvent
══prescription.created══► billing-pharmacy:
       idempotentConsumer.run(...) → [TX] tạo medicine_invoices NHÁP (PENDING) + processed_events
       (prescription_id UNIQUE: lớp chống trùng thứ hai)
```

clinical **không gọi Feign để tạo hóa đơn bên billing** (Feign chỉ đọc). Nó phát sự kiện, billing tự tạo trong CSDL của mình.

## Bước 8. Thu nốt tiền dịch vụ → hoàn tất ca

```
Thu ngân thu các hóa đơn dịch vụ còn PENDING (tiêm, thủ thuật...)
  [TX] hóa đơn → PAID; outbox ServiceInvoicePaidEvent
       mọi hóa đơn PAID / CANCELLED / REFUNDED → pet_visits → COMPLETED (CÙNG giao dịch)
       outbox VisitCompletedEvent
══visit.completed══► reporting   : visit_facts → COMPLETED, cộng hiệu suất bs_tien
                   ► notification
```

## Bước 9. Thu tiền thuốc + trừ kho

```
Thu ngân mở hóa đơn thuốc nháp ──POST /api/v1/billing/medicine-invoices/{id}/confirm-payment──► billing
  [TX] khóa dòng hóa đơn (FOR UPDATE)
       làm mới giá theo bảng giá hiện hành (EX-20)
       từng thuốc (sắp theo id): UPDATE medicine_stocks ... WHERE quantity_on_hand >= :qty
            0 dòng → thiếu kho → ROLLBACK TẤT CẢ (EX-15)
       ghi inventory_transactions (số âm)
       hóa đơn → PAID; outbox MedicineInvoicePaidEvent
══pharmacy.medicine_invoice_paid══► reporting (doanh thu thuốc), notification
(tồn kho xuống dưới ngưỡng ══pharmacy.low_stock══► notification báo Admin)
```

Thu tiền + trừ kho + nhật ký kho là **một** giao dịch vì cùng nằm trong billing-pharmacy (file 01, 14).

## Bước 10. Admin xem báo cáo

```
FE (admin) ──GET /api/v1/reports/revenue/daily?from=...&to=...──► reporting-service
  SELECT ... FROM daily_revenue_summaries     ← MỘT CSDL, không gọi service nào
```

Doanh thu đã có từ các bước 4, 6, 8, 9 thông qua sự kiện (file 12).

---

## Bản đồ tổng hợp: ai phát, ai nghe

```
                 appointment.booked/.status_changed/.no_show
 appointment ════════════════════════════════════════════════════════════► notification
     ▲
     │ visit.checked_in
 clinical ══ visit.checked_in, invoice.service_paid, visit.results_ready ══► clinical (phát phiếu Redis)
     ║    ══ visit.checked_in, invoice.service_paid/refunded, visit.completed/cancelled ══► reporting, notification
     ║    ══ prescription.created ══► billing
     ║    ══ pet.updated ══► appointment, reporting
     ║    ── queue.ticket_issued, visit.patient_called (gửi thẳng, không outbox) ──► notification
     ▼
 billing ═══ pharmacy.medicine_invoice_paid ══► reporting, notification
         ═══ pharmacy.low_stock ══► notification
 auth ══════ user.profile_updated, user.status_changed ══► appointment, clinical, reporting, notification
```

## Các tình huống sự cố và hệ thống phản ứng

| Sự cố | Điều xảy ra | Nhờ kỹ thuật |
|---|---|---|
| reporting tắt 10 phút | Khám, thu tiền bình thường; sự kiện chờ trong queue; bật lại xử lý bù | RabbitMQ bền vững (08) |
| RabbitMQ tắt khi đang thu tiền | Thu tiền vẫn thành công; outbox giữ sự kiện; bật lại gửi tiếp | Outbox (09) |
| Redis mất sạch | Khởi động lại clinical dựng lại hàng đợi từ MySQL, giữ số phiếu | MySQL là nguồn sự thật (11) |
| auth treo | Danh sách vẫn xem được; đặt lịch / phân công báo 503 ngay sau khi mạch mở | Snapshot (05), Circuit Breaker (07) |
| Admin khóa tài khoản bs_tien khi còn ca chờ (EX-17) | auth phát `UserStatusChangedEvent` → clinical gỡ phân công các ca `WAITING`, phiếu về `UNASSIGNED` giữ số; ca đang khám không bị ngắt | Sự kiện (08), Redis `move_ticket` (11) |
| Sự kiện bị giao hai lần | Không phiếu thứ hai, không hóa đơn thứ hai, doanh thu không đếm đôi | Idempotent (10) |
| Admin đổi giá khi ca đang mở (EX-20) | Hóa đơn dịch vụ giữ giá lúc chỉ định; hóa đơn thuốc lấy giá mới lúc thu | Snapshot giá (05) |

Kịch bản [e2e/v4_resilience.mjs](../e2e/v4_resilience.mjs) tự động kiểm tra các tình huống trên trên hệ thống Docker thật.

## Thử tận tay: tự đi lại cả luồng

1. Đăng nhập giao diện `http://localhost:3000` bằng `khach_lan` / `Admin@123`, đặt lịch cho Lu.
2. Đăng nhập `letan_hoa` (cửa sổ ẩn danh), duyệt lịch, tiếp đón, thu phí khám.
3. Sau mỗi bước, chạy câu SQL này trong Workbench để thấy sự kiện vừa phát:

```sql
SELECT 'appointment' AS service, id, event_type, status, created_at FROM pet_clinic_appointment_db.outbox_events
UNION ALL SELECT 'clinical', id, event_type, status, created_at FROM pet_clinic_clinical_db.outbox_events
UNION ALL SELECT 'billing', id, event_type, status, created_at FROM pet_clinic_billing_pharmacy_db.outbox_events
ORDER BY created_at DESC LIMIT 10;
```

4. Đăng nhập `bs_tien`, gọi khách, khám, chốt ca; `letan_hoa` thu tiền dịch vụ và tiền thuốc.
5. Đăng nhập `admin`, mở Báo cáo: doanh thu vừa thu đã có.

## Tự kiểm tra

1. Saga choreography khác orchestration ở điểm nào? VetClinic dùng kiểu nào?
2. Liệt kê theo thứ tự các sự kiện phát ra từ lúc tiếp đón đến lúc thu tiền thuốc.
3. Ở bước 3, có mấy service nhận `visit.checked_in`? Mỗi service làm gì?
4. Vì sao hóa đơn thuốc được billing tạo qua sự kiện, thay vì clinical gọi Feign tạo hộ?
5. Nếu billing tắt đúng lúc Bác sĩ chốt ca thì hóa đơn thuốc có mất không? Vì sao?
