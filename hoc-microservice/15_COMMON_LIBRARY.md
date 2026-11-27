# 15. Thư viện dùng chung `common-library`: chia sẻ code giữa các service

## 1. Vấn đề

Bảy service đều cần: khuôn trả lời `ApiResponse`, danh sách mã lỗi, bộ lọc đọc header người dùng, Outbox, chống xử lý trùng, định nghĩa
sự kiện... Chép tay vào từng service thì sửa một chỗ phải sửa bảy chỗ, và sự kiện bên gửi, bên nhận lệch nhau là hỏng.

## 2. Lý thuyết

Microservice khuyên **hạn chế** chia sẻ code, vì thư viện chung tạo ra phụ thuộc: sửa thư viện thì mọi service phải build lại. Chia sẻ hợp lý
khi đó là:
- **Hợp đồng** giữa các service (định dạng sự kiện, định dạng lỗi): bắt buộc giống nhau ở hai đầu.
- **Hạ tầng kỹ thuật** (Outbox, bảo mật, Feign): không chứa nghiệp vụ.

**Không** đặt logic nghiệp vụ (tính tiền, quy tắc khám) vào thư viện chung. Logic đó thuộc về service sở hữu nó.

## 3. Trong dự án

### 3.1. Nội dung

[common-library/src/main/java/com/vetclinic/common/](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/):

| Package | Chứa | Học ở file |
|---|---|---|
| `dto` | `ApiResponse`, `PageResponse`: khuôn trả lời chung | 06 |
| `exception` | `ErrorCode` (mọi mã lỗi), `AppException`, các bộ bắt lỗi toàn cục | 06, 07 |
| `security` | `HeaderAuthenticationFilter`, `CommonSecurityConfig`, `SecurityUtils`, `RoleCode` | 04 |
| `feign` | `FeignClientInterceptor`, `CustomFeignErrorDecoder`, `ResilientFeignConfig`, `FeignFailurePredicate` | 06, 07 |
| `event` | Mọi `record` sự kiện, `EventRouting`, `RabbitCommonConfig` | 08 |
| `event/outbox` | `@EnableOutbox`, `OutboxWriter`, `OutboxRelay`, `InternalOutboxController` | 09, 12 |
| `event/processed` | `@EnableIdempotentConsumer`, `IdempotentConsumer` | 10 |
| `lock` | `NamedLockService` | 14 |
| `util` | `UuidV7`, `TimeZones`, `Codes` | – |

Service dùng thư viện bằng cách khai báo phụ thuộc trong `pom.xml`:
```xml
<dependency>
    <groupId>com.vetclinic</groupId>
    <artifactId>common-library</artifactId>
</dependency>
```

### 3.2. Bẫy lớn nhất: thư viện có code mà service không cần

Mọi service quét `scanBasePackages = "com.vetclinic"`, nên **mọi `@Component` trong common-library đều được nạp vào mọi service**. Nhưng:
- **api-gateway** chạy WebFlux (không phải Servlet): nạp `HeaderAuthenticationFilter` (dùng Servlet API) → không khởi động được.
- **notification-service** không có CSDL: nạp `InternalOutboxController` (dùng Spring Data JPA) → lỗi thiếu lớp, không khởi động được.
- **auth, billing** không gọi Feign: nạp cấu hình Feign → lỗi thiếu lớp.

Giải pháp: **bean có điều kiện**. Ví dụ:

```java
// GlobalExceptionHandler: chỉ nạp ở service Servlet (Gateway WebFlux bỏ qua)
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler { ... }

// InternalOutboxController: chỉ nạp ở service có JPA (notification bỏ qua)
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
@RequestMapping("/internal/outbox")
public class InternalOutboxController { ... }

// ResilientFeignConfig: chỉ nạp ở service có cả Feign lẫn Resilience4j
@Configuration
@ConditionalOnClass(name = {"feign.Feign", "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry", ...})
public class ResilientFeignConfig { ... }

// RabbitCommonConfig, DomainEventRabbitPublisher: chỉ nạp ở service có RabbitMQ
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")

// NamedLockService: chỉ nạp ở service có CSDL
@ConditionalOnProperty(name = "spring.datasource.url")
```

| Annotation | Nghĩa |
|---|---|
| `@ConditionalOnWebApplication(SERVLET)` | Chỉ nạp nếu là ứng dụng web kiểu Servlet (Spring MVC) |
| `@ConditionalOnClass(name = "...")` | Chỉ nạp nếu lớp đó có trên classpath (service có thư viện đó) |
| `@ConditionalOnProperty(name = "...")` | Chỉ nạp nếu cấu hình có thuộc tính đó |

Dùng `name = "chuỗi"` thay vì `.class` để nếu lớp không tồn tại thì Java không lỗi ngay lúc đọc annotation.

### 3.3. Phụ thuộc `optional`

Trong [common-library/pom.xml](../vetclinic-backend/common-library/pom.xml), các thư viện web, security, amqp, jpa, openfeign, resilience4j
đều `<optional>true</optional>`:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
    <optional>true</optional>      <!-- common-library biên dịch được, nhưng KHÔNG kéo Feign vào service dùng nó -->
</dependency>
```

Nhờ vậy auth-service dùng common-library mà không bị kéo theo Feign. Service nào cần Feign thì tự khai trong `pom.xml` của mình, và lúc
đó `@ConditionalOnClass` mới bật bean tương ứng.

### 3.4. Bật tính năng bằng annotation `@EnableXxx`

Thay vì nạp tự động, Outbox và Idempotent Consumer chỉ bật khi service **yêu cầu**:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({OutboxPackageRegistrar.class, OutboxConfiguration.class})   // nạp các bean Outbox
public @interface EnableOutbox { }
```

Service gắn `@EnableOutbox` lên lớp Application thì có Outbox; không gắn thì không (notification, reporting không phát sự kiện nên không gắn).

### 3.5. `HeaderAuthenticationFilter` không phải `@Component`

```java
/**
 * Cố ý KHÔNG đánh dấu @Component: nếu là bean, Spring Boot sẽ tự đăng ký thêm vào servlet chain và filter chạy 2 lần.
 * Filter chỉ được thêm vào SecurityFilterChain trong CommonSecurityConfig.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter { ... }
```

Spring Boot tự đăng ký **mọi bean kiểu Filter** vào chuỗi lọc servlet. Nếu đồng thời thêm vào chuỗi Security thì nó chạy hai lần. Vì vậy chỉ
`new HeaderAuthenticationFilter()` bên trong `CommonSecurityConfig`.

### 3.6. Lưu ý khi build

Sửa common-library xong, chạy `mvn install` ở thư mục gốc **không** đóng gói lại jar của service nếu service không đổi. Phải
`mvn -o -pl <module> clean install` cho service cần chạy lại (Docker không bị vì Docker biên dịch lại từ mã nguồn).

## 4. Thử tận tay

Đếm số lớp trong thư viện và nơi dùng:
```bash
cd vetclinic-backend
find common-library/src/main -name "*.java" | wc -l          # khoảng 60 lớp
grep -rl "com.vetclinic.common.event.outbox.OutboxWriter" --include=*.java */src/main | grep -v common-library
# → các service gọi outboxWriter.write(...)
```

Xem một service KHÔNG nạp bean của Feign: log khởi động của `auth-service` không có dòng nào về `FeignClient`.

## 5. Tự kiểm tra

1. Nên và không nên đặt gì vào thư viện dùng chung?
2. Vì sao `InternalOutboxController` cần `@ConditionalOnClass(JpaRepository)`? Điều gì xảy ra nếu bỏ?
3. `<optional>true</optional>` trong pom của common-library có tác dụng gì?
4. Vì sao Outbox bật bằng `@EnableOutbox` thay vì tự động nạp?
5. Vì sao `HeaderAuthenticationFilter` không được đánh dấu `@Component`?
