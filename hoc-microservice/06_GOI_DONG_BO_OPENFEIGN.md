# 06. Gọi đồng bộ giữa các service: OpenFeign

## 1. Vấn đề

Lúc khách đặt lịch, appointment-service phải biết **ngay** khung giờ còn chỗ không. Sức chứa = số Bác sĩ đang làm việc × 2, mà danh
sách Bác sĩ nằm ở auth-service. appointment cần hỏi và **chờ câu trả lời** rồi mới quyết định cho đặt hay không.

Có thể tự viết HTTP bằng `RestTemplate`: ghép URL, gửi, đọc JSON, xử lý lỗi... lặp lại ở mọi chỗ. Feign làm việc này gọn hơn.

## 2. Lý thuyết

**OpenFeign** cho phép khai báo lời gọi HTTP bằng một **interface Java**. Spring tự sinh lớp cài đặt lúc chạy:

```java
@FeignClient(name = "auth-service", path = "/internal/users")   // gọi service tên này (tra Eureka)
public interface AuthClient {
    @GetMapping("/active-doctors/count")                       // GET /internal/users/active-doctors/count
    ApiResponse<Long> getActiveDoctorCount();
}

// Dùng như gọi hàm bình thường:
long doctors = authClient.getActiveDoctorCount().getData();
```

Ví dụ đời thường: thay vì tự quay số, chờ chuông, nói, nghe, ghi chép, bạn bấm **phím tắt** "Gọi quầy thuốc", máy làm hết phần còn lại.

**Gọi đồng bộ có rủi ro**: service kia chậm thì mình chậm theo, service kia sập thì mình lỗi theo. Vì vậy dự án đặt nhiều quy tắc ở
phần 4.

## 3. Trong dự án

### 3.1. Danh sách lời gọi Feign (toàn hệ thống)

| Bên gọi → bên được gọi | Interface | Gọi để |
|---|---|---|
| appointment → auth | [AuthClient](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/client/AuthClient.java) | Đếm Bác sĩ `ACTIVE` (sức chứa khung giờ), thông tin chủ nuôi |
| appointment → clinical | [PetClient](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/client/PetClient.java) | Kiểm tra thú cưng thuộc đúng chủ, chụp snapshot |
| clinical → auth | [AuthClient](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/client/AuthClient.java) | Bác sĩ `ACTIVE`, tên, số phòng |
| clinical → appointment | [AppointmentClient](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/client/AppointmentClient.java) | Đọc lịch hẹn lúc tiếp đón (phải `CONFIRMED`, đúng ngày) |
| clinical → billing | [MedicineClient](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/client/MedicineClient.java), [MedicineInvoiceClient](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/client/MedicineInvoiceClient.java) | Thông tin thuốc khi kê đơn, trạng thái hóa đơn thuốc |
| notification → auth, clinical | [client/](../vetclinic-backend/notification-service/src/main/java/com/vetclinic/notification/client/) | Lấy email, tên để soạn thông báo |
| reporting → auth, clinical, billing | [OutboxClients](../vetclinic-backend/reporting-service/src/main/java/com/vetclinic/reporting/client/OutboxClients.java) | **Chỉ** khi Admin bấm dựng lại báo cáo (file 12) |

Hai điều nên để ý: **billing và auth không gọi ai**; và **mọi lời gọi đều trỏ vào `/internal/**`**.

### 3.2. Bên được gọi: endpoint `/internal/**`

[InternalUserController.java](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/controller/InternalUserController.java):

```java
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    @GetMapping("/active-doctors/count")
    public ResponseEntity<ApiResponse<Long>> activeDoctorCount() {
        return ResponseEntity.ok(ApiResponse.success(userService.countActiveDoctors()));
    }

    /** Tra cứu hàng loạt theo ?ids=a,b,c để tránh N+1 lời gọi Feign khi hiển thị danh sách. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsers(@RequestParam List<UUID> ids) { ... }
}
```

Vì sao tách `/internal` riêng thay vì dùng lại `/api/v1/users`?
- `/api/v1/...` dành cho người dùng, có `@PreAuthorize`, trả nhiều dữ liệu.
- `/internal/...` dành cho service, **không cần header người dùng** (listener RabbitMQ hay job định kỳ không có người dùng nào),
  trả đúng dữ liệu tối thiểu. An toàn nhờ Gateway không định tuyến `/internal` (file 03).

Lưu ý endpoint `getUsers(?ids=a,b,c)`: lấy **một lô** thay vì gọi từng người. 50 bác sĩ = 1 lời gọi, không phải 50 (tránh lỗi **N+1**).

### 3.3. Bên gọi: ví dụ đặt lịch

[SlotPolicy.activeDoctorCapacity()](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/service/SlotPolicy.java):

```java
public int activeDoctorCapacity() {
    ApiResponse<Long> response = authClient.getActiveDoctorCount();   // ← lời gọi Feign
    if (response == null || response.getData() == null) {
        throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
    }
    long doctors = Math.max(response.getData(), 0L);
    return (int) (doctors * APPOINTMENTS_PER_DOCTOR_PER_SLOT);        // × 2
}
```

Và trong [AppointmentServiceImpl.create()](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/service/impl/AppointmentServiceImpl.java):

```java
/**
 * KHÔNG đánh dấu @Transactional: các lời gọi OpenFeign phải nằm ngoài transaction.
 * Thứ tự: kiểm tra đầu vào -> Feign (thú cưng + chủ nuôi) -> lấy khóa -> [transaction: đếm chỗ + chèn] -> nhả khóa.
 */
public AppointmentResponse create(CreateAppointmentRequest request) {
    ...
    PetSummaryResponse pet = loadPet(request.petId(), booking.ownerId());   // Feign → clinical
    int capacity = slotPolicy.activeDoctorCapacity();                       // Feign → auth
    ...
    Appointment saved = slotLockService.runLocked(locks,
            () -> transactionTemplate.execute(tx -> insertAppointment(...))); // giao dịch NGẮN, không có Feign bên trong
    return toResponse(saved);
}
```

## 4. Các quy tắc khi dùng Feign trong dự án (và vì sao)

### Quy tắc 1: Feign chỉ để ĐỌC

Không service nào **ghi** sang service khác bằng Feign. Lý do: nếu clinical gọi Feign "ghi lịch hẹn CHECKED_IN" rồi giao dịch của
clinical lại rollback, thì appointment đã ghi mà clinical không ghi → lệch dữ liệu, không ai hoàn tác. Muốn service khác thay đổi →
gửi **sự kiện** (file 08, 09).

### Quy tắc 2: Gọi Feign NGOÀI `@Transactional`

Giao dịch giữ một **kết nối CSDL** từ lúc mở tới lúc commit. Nếu bên trong có Feign mà service kia chậm 2 giây, kết nối bị giữ 2 giây
không làm gì. Bể kết nối chỉ có 10 kết nối (`hikari.maximum-pool-size: 10`) → 10 request chậm là cả service treo.

Cách làm: gọi Feign **trước**, có kết quả rồi mới mở giao dịch ngắn để ghi. Code ở mục 3.3 làm đúng thứ tự này.

### Quy tắc 3: Luôn có timeout 2 giây

```yaml
# application.yml của appointment / clinical / notification
spring.cloud.openfeign.client.config.default:
  connect-timeout: 2000   # 2 giây để kết nối
  read-timeout: 2000      # 2 giây để chờ trả lời
```

Không có timeout, service kia treo thì mình treo theo **vô thời hạn**. Riêng reporting đặt `read-timeout: 15000` vì job dựng lại báo
cáo đọc trang dữ liệu lớn và chỉ Admin chạy tay.

### Quy tắc 4: Lỗi nghiệp vụ phải đi xuyên qua lời gọi

clinical trả `404 PET_NOT_FOUND` (mã `APPT_1201`) → appointment phải nhận được đúng lỗi đó để báo người dùng "Không tìm thấy hồ sơ
thú cưng", chứ không phải "Lỗi hệ thống". [CustomFeignErrorDecoder.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/feign/CustomFeignErrorDecoder.java)
đọc body lỗi và dựng lại đúng mã:

```java
public Exception decode(String methodKey, Response response) {
    ApiResponse<?> errorResponse = objectMapper.readValue(body, ApiResponse.class);   // {"errorCode":"APPT_1201",...}
    ErrorCode errorCode = ErrorCode.fromCode(errorResponse.getErrorCode());
    if (errorCode != null) {
        return new AppException(errorCode, errorResponse.getMessage());   // ném lại ĐÚNG lỗi của service con
    }
    return new AppException(fallbackFor(response.status()));              // không đọc được: đoán theo mã HTTP
}
```

Mọi service đều trả lỗi theo **cùng một khuôn** `ApiResponse` (`status, errorCode, message, data, timestamp`) trong `common-library`,
nhờ vậy bên gọi luôn đọc được lỗi của bên bị gọi.

### Quy tắc 5: Phân biệt "chỉ để hiển thị" và "điều kiện để ghi"

| Loại lời gọi | Service kia lỗi thì | Ví dụ |
|---|---|---|
| **Làm giàu hiển thị** | Bắt lỗi, hiện thiếu một chút, **vẫn trả kết quả** | Số phòng Bác sĩ trên chi tiết ca ([VisitEnricher](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/service/VisitEnricher.java)) |
| **Điều kiện để ghi** | **Dừng thao tác**, báo lỗi 503, không ghi gì | Sức chứa khung giờ khi đặt lịch; Bác sĩ còn `ACTIVE` khi phân công |

```java
// VisitEnricher: chỉ hiển thị → nuốt lỗi
try {
    response = authClient.getUsers(List.copyOf(doctorIds));
} catch (RuntimeException e) {
    log.warn("Không lấy được thông tin Bác sĩ từ auth-service: {}", e.getMessage());
    return Map.of();     // không có số phòng, nhưng màn hình vẫn hiện
}
```

Vì sao đặt lịch **không** nuốt lỗi? Nếu auth sập mà "đoán" sức chứa = 10, có thể nhận quá số khách phục vụ được. Thà báo "thử lại
sau" còn hơn ghi sai.

## 5. Thử tận tay

Chặn auth-service rồi thử hai thao tác:

```bash
docker compose stop auth-service
```

- Đăng nhập: lỗi (cần auth, đúng như mong đợi). Nhưng nếu bạn **đã đăng nhập từ trước**, token còn hạn nên Gateway vẫn cho qua.
- Mở danh sách lịch hẹn: **vẫn hiện** (không gọi auth).
- Đặt lịch mới: lỗi **503 `SYS_1004`** (cần đếm Bác sĩ, không đoán bừa).

```bash
docker compose start auth-service
```

Xem log lời gọi:
```bash
docker compose logs appointment-service --tail 50 | grep -i "feign\|auth-service"
```

## 6. Tự kiểm tra

1. `@FeignClient(name = "clinical-service", path = "/internal/pets")` + `@GetMapping("/{id}")` gửi request tới URL nào?
2. Vì sao không được gọi Feign bên trong `@Transactional`?
3. clinical trả 404 `PET_NOT_FOUND`. Nhờ lớp nào mà appointment nhận được đúng mã lỗi này?
4. Tại sao màn hình chi tiết ca nuốt lỗi Feign, còn đặt lịch thì không?
5. Vì sao Feign chỉ được dùng để đọc?
