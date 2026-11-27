# 05. Database per Service: mỗi service một CSDL

## 1. Vấn đề

Nếu 6 service dùng chung **một** CSDL:
- appointment đổi tên một cột của bảng `pets` → clinical chạy lỗi mà không ai báo trước.
- Một truy vấn báo cáo nặng khóa bảng → tiếp đón bị treo.
- Không biết bảng nào "của ai", ai được phép sửa.

Khi đó các service chỉ tách nhau về code, còn dữ liệu vẫn dính chặt: một kiểu monolith trá hình.

## 2. Lý thuyết

**Database per Service**: mỗi service **sở hữu** CSDL của mình. Service khác **không được** đọc / ghi thẳng, kể cả khi có mật khẩu.
Muốn dữ liệu thì:
- **Hỏi qua API** (Feign, file 06), hoặc
- **Nghe sự kiện** rồi tự lưu bản sao cần dùng (file 08).

Hệ quả phải chấp nhận:

| Mất đi | Thay bằng |
|---|---|
| `JOIN` giữa bảng của hai service | Gọi API, hoặc lưu **bản chụp (snapshot)** |
| Khóa ngoại (`FOREIGN KEY`) giữa hai CSDL | **Khóa ngoại logic**: chỉ lưu id, ứng dụng tự đảm bảo |
| Một giao dịch (transaction) bao nhiều service | Mỗi service giao dịch riêng + sự kiện (**Saga**, file 17) |
| Dữ liệu khớp tức thì ở mọi nơi | **Eventual consistency**: khớp sau một chút (thường dưới 1 giây) |

## 3. Trong dự án

### 3.1. Một máy chủ MySQL, năm CSDL logic

Để tiết kiệm tài nguyên khi chạy demo, dự án dùng **một container MySQL** nhưng chia **năm CSDL riêng**. Mỗi service chỉ có chuỗi
kết nối tới CSDL của mình:

```yaml
# appointment-service/application.yml
spring.datasource.url: jdbc:mysql://${DB_HOST}:${DB_PORT}/pet_clinic_appointment_db?...
# clinical-service/application.yml
spring.datasource.url: jdbc:mysql://${DB_HOST}:${DB_PORT}/pet_clinic_clinical_db?...
```

| CSDL | Service sở hữu | Số bảng |
|---|---|---|
| `pet_clinic_auth_db` | auth-service | 9 |
| `pet_clinic_appointment_db` | appointment-service | 4 |
| `pet_clinic_clinical_db` | clinical-service | 24 |
| `pet_clinic_billing_pharmacy_db` | billing-pharmacy-service | 10 |
| `pet_clinic_reporting_db` | reporting-service | 5 |

Định nghĩa đầy đủ ở [docs/schema_init.sql](../docs/schema_init.sql). Khi lên môi trường thật có thể tách thành 5 máy chủ MySQL
mà không phải sửa code, chỉ đổi `DB_HOST`.

`ddl-auto: validate` trong mọi service: Hibernate **chỉ kiểm tra** entity khớp bảng lúc khởi động, không tự tạo hay sửa bảng.
Lệch một cột là service không khởi động được, nên phát hiện sai sót ngay.

### 3.2. Khóa ngoại logic

Bảng `appointments` (CSDL appointment) cần biết lịch hẹn của thú cưng nào, nhưng bảng `pets` nằm ở CSDL clinical. Không thể khai
`FOREIGN KEY` sang CSDL khác, nên chỉ lưu id:

```sql
-- docs/schema_init.sql, CSDL pet_clinic_appointment_db
CREATE TABLE appointments (
    id BINARY(16) NOT NULL PRIMARY KEY,
    pet_id BINARY(16) NOT NULL,         -- Logical FK -> pet_clinic_clinical_db.pets(id)
    owner_id BINARY(16) NOT NULL,       -- Logical FK -> pet_clinic_auth_db.users(id)
    ...
```

Ứng dụng tự kiểm tra: lúc đặt lịch, appointment **gọi Feign** sang clinical `GET /internal/pets/{id}` để chắc thú cưng tồn tại và
thuộc đúng chủ (file 06).

Bên trong **cùng một** CSDL thì vẫn dùng khóa ngoại thật (CSDL có 40 khóa ngoại vật lý, ví dụ `pet_visits.pet_id → pets.id` cùng
nằm ở clinical).

### 3.3. Bản chụp (snapshot): sao chép có chủ đích

Danh sách lịch hẹn cần hiện **tên thú cưng, tên và SĐT chủ nuôi**. Nếu mỗi dòng gọi clinical và auth để hỏi tên → 50 lịch hẹn =
100 lời gọi, và auth sập là danh sách lịch hẹn cũng chết.

Giải pháp: lưu **bản sao** ngay trên bảng `appointments`:

```sql
pet_name_snapshot    VARCHAR(100) NOT NULL,   -- tên thú cưng lúc đặt lịch
owner_name_snapshot  VARCHAR(150) NOT NULL,   -- tên chủ nuôi
owner_phone_snapshot VARCHAR(20)  NOT NULL,   -- SĐT chủ nuôi
```

Lúc đặt lịch, chụp dữ liệu từ kết quả Feign
([AppointmentServiceImpl.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/service/impl/AppointmentServiceImpl.java)):

```java
Appointment appointment = Appointment.builder()
        .petId(request.petId())
        .ownerId(booking.ownerId())
        .petNameSnapshot(pet.name())             // pet = kết quả gọi clinical qua Feign
        .ownerNameSnapshot(pet.ownerName())
        .ownerPhoneSnapshot(pet.ownerPhone())
        ...
```

**Khi dữ liệu gốc đổi thì sao?** Chủ nuôi đổi SĐT ở auth → auth phát `UserProfileUpdatedEvent` → appointment nghe và cập nhật
snapshot ([SnapshotListeners.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/event/SnapshotListeners.java)):

```java
@RabbitListener(queues = AppointmentRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
    idempotentConsumer.run(event.eventId(), USER_CONSUMER, () -> {
        // UPDATE appointments SET owner_name_snapshot = ?, owner_phone_snapshot = ? WHERE owner_id = ?
        appointmentService.refreshOwnerSnapshot(event.userId(), event.fullName(), event.phoneNumber());
    });
}
```

Tương tự `PetUpdatedEvent` (clinical phát khi đổi tên thú cưng / chuyển chủ) cập nhật `pet_name_snapshot`.

Các snapshot khác trong dự án:

| Bảng (service) | Cột snapshot | Nguồn | Cập nhật khi |
|---|---|---|---|
| `appointments` (appointment) | `pet_name_snapshot`, `owner_name_snapshot`, `owner_phone_snapshot` | clinical, auth | `PetUpdatedEvent`, `UserProfileUpdatedEvent` |
| `pets` (clinical) | `owner_name_snapshot`, `owner_phone_snapshot` | auth | `UserProfileUpdatedEvent` |
| `pet_visits` (clinical) | `doctor_name_snapshot` | auth | `UserProfileUpdatedEvent` |
| `medicine_invoices` (billing) | `customer_name_snapshot`, `pet_name_snapshot`, `doctor_name_snapshot` | clinical | lấy từ `PrescriptionCreatedEvent` lúc tạo hóa đơn |
| `service_invoices` (clinical) | `unit_price`, `vat_rate` | bảng giá | **không cập nhật**: chụp giá lúc chỉ định, đổi giá không ảnh hưởng ca đang mở (EX-20) |

Chú ý hàng cuối: không phải snapshot nào cũng cần cập nhật. Giá trên hóa đơn **phải giữ nguyên** giá lúc chỉ định, đó là đúng nghiệp vụ.

### 3.4. NFR-10: danh sách không phụ thuộc service khác

Yêu cầu phi chức năng NFR-10 trong tài liệu: danh sách đợt khám / lịch hẹn vẫn xem được khi auth-service sập. Dự án làm được nhờ
danh sách **chỉ đọc cột snapshot**. Chỉ màn hình **chi tiết một ca** mới gọi auth để lấy số phòng và trạng thái Bác sĩ, và nếu auth sập
thì vẫn hiện, chỉ thiếu hai thông tin đó
([VisitEnricher.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/service/VisitEnricher.java),
xem file 07).

## 4. Thử tận tay

Trong Workbench (cổng 3307):

```sql
-- Snapshot trên lịch hẹn
SELECT appointment_code, BIN_TO_UUID(pet_id) AS pet_id, pet_name_snapshot, owner_name_snapshot, owner_phone_snapshot
FROM pet_clinic_appointment_db.appointments LIMIT 5;

-- So với dữ liệu gốc ở clinical: tên phải trùng
SELECT BIN_TO_UUID(id), name FROM pet_clinic_clinical_db.pets WHERE name = 'Lu';
```

Thử eventual consistency: vào giao diện, đổi tên một thú cưng có lịch hẹn, rồi chạy lại câu `SELECT` đầu. Gần như ngay lập tức
`pet_name_snapshot` đổi theo: đó là `PetUpdatedEvent` đã đi qua RabbitMQ.

Thử tắt clinical rồi xem danh sách lịch hẹn:
```bash
docker compose stop clinical-service
# Trang lịch hẹn của Lễ tân vẫn hiện đủ tên thú cưng, tên chủ (đọc snapshot). Chỉ thao tác cần clinical mới lỗi.
docker compose start clinical-service
```

## 5. Tự kiểm tra

1. Vì sao `appointments.pet_id` không khai `FOREIGN KEY`?
2. Snapshot giúp gì cho NFR-10? Nhược điểm của snapshot là gì?
3. Chủ nuôi đổi SĐT. Liệt kê các bảng ở các service khác sẽ được cập nhật và bằng sự kiện gì.
4. Vì sao `service_invoices.unit_price` không được cập nhật khi Admin đổi giá dịch vụ?
5. "Eventual consistency" nghĩa là gì? Cho một ví dụ trong dự án.
