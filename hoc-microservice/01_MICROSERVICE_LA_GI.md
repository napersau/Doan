# 01. Microservice là gì? VetClinic chia service như thế nào?

## 1. Vấn đề: một chương trình làm tất cả (monolith)

Hình dung phòng khám dùng **một chương trình Spring Boot duy nhất** với một CSDL duy nhất: đăng nhập, đặt lịch, khám bệnh,
thu tiền, kho thuốc, báo cáo đều nằm chung. Đây gọi là **monolith** (khối nguyên). Lúc nhỏ thì rất tiện, nhưng khi lớn lên:

| Vấn đề của monolith | Ví dụ ở phòng khám |
|---|---|
| Lỗi một chỗ, sập tất cả | Chức năng báo cáo chạy truy vấn nặng làm hết bộ nhớ → Lễ tân cũng không tiếp đón được |
| Sửa một chỗ, triển khai lại tất cả | Sửa chữ trên email thông báo cũng phải dừng cả hệ thống để cập nhật |
| Không mở rộng riêng được | Giờ cao điểm chỉ phần khám bệnh bận, nhưng phải nhân bản cả chương trình |
| Nhiều người sửa chung một khối code | Ba thành viên sửa chung, dễ đụng nhau |

## 2. Lý thuyết: microservice

**Microservice** là cách chia hệ thống thành **nhiều chương trình nhỏ chạy độc lập**, mỗi chương trình:

1. **Làm một mảng nghiệp vụ** rõ ràng (gọi là *bounded context*, "ranh giới nghiệp vụ").
2. **Chạy thành tiến trình riêng** (ở dự án này: một container Docker riêng, cổng riêng).
3. **Sở hữu CSDL riêng**: service khác muốn dữ liệu phải **hỏi qua API** hoặc **nghe sự kiện**, không được đọc thẳng CSDL.
4. **Triển khai độc lập**: sửa `notification-service` thì chỉ build và chạy lại container đó.

Ví dụ đời thường: **monolith** giống một người vừa làm lễ tân, vừa khám, vừa thu tiền, vừa bán thuốc. **Microservice** giống phòng
khám có nhiều quầy: quầy lễ tân, phòng khám, quầy thu ngân, quầy thuốc. Mỗi quầy có sổ sách riêng; cần thông tin của quầy khác
thì **hỏi** (gọi điện = gọi đồng bộ) hoặc **nhận phiếu chuyển** (sự kiện = bất đồng bộ).

### Cái giá phải trả

Microservice không miễn phí. Chia ra thì phát sinh các bài toán mà monolith không có. **Cả bộ tài liệu này chính là cách dự án
giải từng bài toán đó:**

| Bài toán mới | Cách VetClinic giải | File |
|---|---|---|
| Service tìm địa chỉ nhau thế nào? | Eureka | 02 |
| Trình duyệt gọi 7 service, 7 địa chỉ? | API Gateway | 03 |
| Đăng nhập ở auth, service khác biết bạn là ai? | JWT + header nội bộ | 04 |
| Không JOIN được bảng của service khác | Snapshot, khóa ngoại logic | 05 |
| Hỏi dữ liệu service khác | OpenFeign | 06 |
| Service kia sập thì sao? | Circuit Breaker, timeout | 07 |
| Báo tin cho nhiều service | RabbitMQ | 08 |
| Lưu CSDL xong mà gửi tin thất bại? | Transactional Outbox | 09 |
| Tin nhắn tới hai lần? | Idempotent Consumer | 10 |
| Một nghiệp vụ trải qua nhiều service | Saga bằng sự kiện | 17 |

## 3. Trong dự án: 8 module backend + 1 thư viện chung

Mã backend nằm ở [vetclinic-backend/](../vetclinic-backend/). Đây là **Maven multi-module**: một file
[pom.xml](../vetclinic-backend/pom.xml) cha liệt kê các module con, mỗi module con build ra **một file `.jar` chạy độc lập**.

| Module | Cổng | Làm gì | CSDL |
|---|---|---|---|
| `service-registry` | 8761 | Danh bạ địa chỉ (Eureka). Hạ tầng, không có nghiệp vụ | – |
| `api-gateway` | 8080 | Cổng vào duy nhất, kiểm tra JWT, chuyển request đến đúng service | – |
| `auth-service` | 8081 | Tài khoản, vai trò, đăng nhập, cấp token, hồ sơ nhân viên / chủ nuôi | `pet_clinic_auth_db` (9 bảng) |
| `appointment-service` | 8082 | Đặt lịch, sức chứa khung giờ, NO_SHOW | `pet_clinic_appointment_db` (4 bảng) |
| `clinical-service` | 8083 | Thú cưng, tiếp đón, khám, xét nghiệm, CĐHA, hóa đơn dịch vụ, đơn thuốc, hàng đợi | `pet_clinic_clinical_db` (24 bảng) + Redis |
| `billing-pharmacy-service` | 8084 | Danh mục thuốc, kho, hóa đơn thuốc | `pet_clinic_billing_pharmacy_db` (10 bảng) |
| `notification-service` | 8085 | Thông báo realtime (WebSocket) và email | không có |
| `reporting-service` | 8086 | Báo cáo doanh thu, lượt khám, hiệu suất bác sĩ | `pet_clinic_reporting_db` (5 bảng) |
| `common-library` | – | **Không chạy được một mình**: là thư viện code dùng chung, được nhúng vào các service | – |

### Vì sao chia đúng như vậy?

Chia theo **nghiệp vụ** (ai làm việc gì ở phòng khám), không chia theo kỹ thuật:

- **auth** tách riêng vì mọi service đều cần biết "ai đang đăng nhập", và tài khoản là dữ liệu nhạy cảm.
- **appointment** tách riêng vì đặt lịch có quy tắc riêng (khung giờ, sức chứa, hủy trước 2 giờ) và khách đặt online lúc nửa đêm
  không liên quan đến việc khám.
- **clinical** là lõi chuyên môn (khám, CLS, đơn thuốc). Nó lớn nhất vì các bước khám gắn chặt với nhau trong một ca.
- **billing-pharmacy** tách riêng vì kho thuốc và tiền thuốc cần giao dịch chặt chẽ (thu tiền + trừ kho phải cùng thành công).
- **notification** tách riêng vì gửi email chậm và hay lỗi (SMTP), không được làm chậm nghiệp vụ chính.
- **reporting** tách riêng vì truy vấn báo cáo nặng, không được làm chậm CSDL đang phục vụ khám bệnh (xem file 12).

### Mỗi service có cấu trúc giống nhau

Mở bất kỳ service nào, ví dụ [appointment-service/src/main/java/com/vetclinic/appointment/](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/):

```
AppointmentServiceApplication.java   ← hàm main, bật các tính năng (Feign, Outbox...)
controller/   ← nhận HTTP request (REST API)
service/      ← logic nghiệp vụ
repository/   ← truy vấn CSDL (Spring Data JPA)
entity/       ← lớp ánh xạ bảng CSDL
dto/          ← dữ liệu vào / ra của API (không trả thẳng entity)
client/       ← Feign client: gọi sang service KHÁC
event/        ← nhận sự kiện RabbitMQ từ service khác
config/, constant/, mapper/, scheduler/
```

Hai thư mục **`client/`** và **`event/`** chính là "cửa giao tiếp" với service khác. Muốn biết một service nói chuyện với ai,
mở hai thư mục này.

### Hàm `main` cho biết service dùng những gì

[AppointmentServiceApplication.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/AppointmentServiceApplication.java):

```java
@EnableOutbox                 // service này GỬI sự kiện qua Outbox (file 09)
@EnableIdempotentConsumer     // service này NHẬN sự kiện, chống xử lý trùng (file 10)
@EnableScheduling             // có tác vụ chạy định kỳ (quét NO_SHOW)
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.vetclinic.appointment.client")   // GỌI service khác qua Feign (file 06)
@SpringBootApplication(scanBasePackages = "com.vetclinic")               // quét cả common-library (file 15)
public class AppointmentServiceApplication {
    public static void main(String[] args) {
        TimeZones.forceUtcDefault();
        SpringApplication.run(AppointmentServiceApplication.class, args);
    }
}
```

So sánh nhanh các service:

| Service | `@EnableFeignClients` (gọi đi) | `@EnableOutbox` (gửi sự kiện) | `@EnableIdempotentConsumer` (nhận sự kiện) |
|---|---|---|---|
| auth | – | có | – |
| appointment | có | có | có |
| clinical | có | có | có |
| billing-pharmacy | – | có | có |
| notification | có | – (chỉ nhận) | chống trùng trong bộ nhớ |
| reporting | có (chỉ khi dựng lại báo cáo) | – | có |

## 4. Hai kiểu giao tiếp giữa các service

Đây là ý quan trọng nhất của cả bộ tài liệu:

| | **Đồng bộ** (synchronous) | **Bất đồng bộ** (asynchronous) |
|---|---|---|
| Công cụ | HTTP qua **OpenFeign** | Sự kiện qua **RabbitMQ** |
| Cách hoạt động | Gọi rồi **đứng chờ** trả lời | Gửi tin rồi **đi làm việc khác** |
| Ví dụ đời thường | Gọi điện hỏi: "Hôm nay có bao nhiêu bác sĩ đi làm?" | Dán thông báo lên bảng tin: "Đã thu tiền ca VIS-123" |
| Dùng khi | Cần câu trả lời **ngay** để quyết định | Báo cho **người khác** biết việc đã xảy ra |
| Ví dụ trong dự án | Đặt lịch hỏi auth số bác sĩ ACTIVE để tính sức chứa | Thu tiền xong, báo cho hàng đợi, báo cáo, thông báo |
| Nhược điểm | Service kia sập thì mình cũng lỗi | Dữ liệu khớp sau một chút (không tức thì) |

**Quy tắc của dự án** (xem [THIET_KE_KIEN_TRUC_MICROSERVICES.md](../docs/THIET_KE_KIEN_TRUC_MICROSERVICES.md) mục 5):
- Feign **chỉ để ĐỌC**. Không service nào **ghi** sang service khác qua Feign.
- Muốn service khác **thay đổi** dữ liệu của nó → gửi **sự kiện**, để nó tự cập nhật CSDL của mình.

Ví dụ: Lễ tân tiếp đón khách có lịch hẹn. clinical-service **không** gọi appointment để sửa lịch hẹn thành `CHECKED_IN`. clinical
chỉ phát sự kiện `VisitCheckedInEvent`; appointment-service nghe được và **tự** đổi trạng thái lịch hẹn của mình (file 08).

## 5. Thử tận tay

```bash
docker compose ps
```

Bạn thấy 13 container: 4 hạ tầng (`mysql-server`, `rabbitmq-broker`, `redis`, `mailpit`), 8 service Java, 1 `frontend`.
Thử dừng **một** service để thấy tính độc lập:

```bash
docker compose stop reporting-service
# Vào giao diện: đăng nhập, đặt lịch, tiếp đón... vẫn chạy bình thường. Chỉ trang Báo cáo lỗi.
docker compose start reporting-service
# Sự kiện phát ra trong lúc reporting tắt vẫn nằm chờ trong RabbitMQ; reporting bật lại thì xử lý bù.
```

Đây chính là lợi ích "lỗi một chỗ không sập tất cả".

## 6. Tự kiểm tra

1. Kể tên 6 service nghiệp vụ và CSDL của từng service.
2. Vì sao `notification-service` được tách riêng thay vì gửi email ngay trong clinical-service?
3. Khác nhau giữa gọi đồng bộ và bất đồng bộ? Cho một ví dụ mỗi loại trong dự án.
4. Vì sao clinical-service không gọi Feign để sửa lịch hẹn thành `CHECKED_IN`?
5. `common-library` có chạy độc lập được không? Nó là gì?
