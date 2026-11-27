# 02. Service Discovery: các service tìm thấy nhau bằng tên (Eureka)

## 1. Vấn đề

appointment-service cần hỏi auth-service "có bao nhiêu bác sĩ đang làm việc?". Nó phải gọi tới **địa chỉ** nào?

- Ghi cứng `http://172.18.0.7:8081`? Docker cấp IP mỗi lần chạy có thể khác nhau → hỏng.
- Nếu sau này chạy **2 bản** auth-service để chịu tải, phải gọi bản nào?
- Một bản auth-service vừa chết, làm sao biết để không gọi vào?

Cần một **danh bạ** tự cập nhật: service nào đang sống, ở địa chỉ nào.

## 2. Lý thuyết

**Service Discovery** gồm hai vai:

| Vai | Làm gì | Trong dự án |
|---|---|---|
| **Registry** (sổ đăng ký) | Giữ danh sách `tên service → các địa chỉ đang sống` | `service-registry` (Netflix Eureka Server) |
| **Client** (mọi service khác) | Lúc khởi động: **đăng ký** "tôi là auth-service, ở địa chỉ X". Định kỳ gửi **nhịp tim** (heartbeat) "tôi còn sống". Khi cần gọi ai: **hỏi** registry địa chỉ | Mọi service có `eureka.client.*` trong `application.yml` |

Ví dụ đời thường: tổng đài 1080. Mỗi cửa hàng đăng ký số điện thoại; bạn chỉ cần nhớ **tên** cửa hàng, gọi tổng đài để biết số.
Cửa hàng đóng cửa (không gửi nhịp tim) thì tổng đài gạch tên.

Có thêm **load balancing phía client**: nếu `auth-service` có 2 địa chỉ, bên gọi tự chia lượt (lần này bản 1, lần sau bản 2).
Spring Cloud LoadBalancer làm việc này, kích hoạt bằng tiền tố `lb://`.

## 3. Trong dự án

### 3.1. Registry: chỉ một annotation

[ServiceRegistryApplication.java](../vetclinic-backend/service-registry/src/main/java/com/vetclinic/registry/ServiceRegistryApplication.java):

```java
@EnableEurekaServer          // biến ứng dụng Spring Boot này thành Eureka Server
@SpringBootApplication
public class ServiceRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}
```

Cấu hình [service-registry/application.yml](../vetclinic-backend/service-registry/src/main/resources/application.yml):

```yaml
server:
  port: 8761
eureka:
  client:
    register-with-eureka: false   # bản thân registry không tự đăng ký vào chính nó
    fetch-registry: false         # và không cần tải danh bạ về
  server:
    enable-self-preservation: false     # môi trường demo: service chết thì gỡ ngay
    eviction-interval-timer-in-ms: 10000 # 10 giây quét gỡ service chết một lần
    use-read-only-response-cache: false  # không dùng cache 30 giây → service mới đăng ký được thấy ngay
```

Giải thích ba dòng `server`:
- **Self-preservation** là cơ chế an toàn của Eureka: khi mất nhịp tim hàng loạt (có thể do mạng chập chờn), Eureka **không** gỡ
  service nào cả. Hệ thống demo ít service nên tắt đi để service chết được gỡ nhanh.
- **`use-read-only-response-cache: false`**: mặc định Eureka trả danh bạ từ cache làm mới mỗi 30 giây. Nhóm từng gặp lỗi "No servers
  available": service vừa lên nhưng Gateway chưa thấy. Tắt cache thì hết.

### 3.2. Client: mọi service đăng ký vào Eureka

Trong `application.yml` của mọi service (ví dụ [appointment-service](../vetclinic-backend/appointment-service/src/main/resources/application.yml)):

```yaml
spring:
  application:
    name: appointment-service      # ← TÊN đăng ký vào Eureka. Service khác gọi bằng tên này
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}   # địa chỉ registry
    registry-fetch-interval-seconds: 5   # 5 giây tải danh bạ mới một lần (mặc định 30)
  instance:
    prefer-ip-address: true         # đăng ký bằng IP container thay vì hostname
```

Chỉ cần có thư viện `spring-cloud-starter-netflix-eureka-client` trong `pom.xml` là service **tự** đăng ký lúc khởi động, không
cần viết code.

Trong Docker, biến `EUREKA_URL` được đặt trong [docker-compose.yml](../docker-compose.yml):
```yaml
EUREKA_URL: http://service-registry:8761/eureka   # "service-registry" là tên container, Docker tự phân giải
```

### 3.3. Gọi bằng tên: hai chỗ dùng

**Chỗ 1, Gateway**: `lb://` nghĩa là "hỏi Eureka địa chỉ của service tên này, rồi chia tải":
```yaml
# api-gateway/application.yml
- id: auth-service
  uri: lb://auth-service          # KHÔNG ghi IP, chỉ ghi tên
  predicates:
    - Path=/api/v1/auth/**,...
```

**Chỗ 2, Feign client**: thuộc tính `name` chính là tên trong Eureka:
```java
// appointment-service/client/AuthClient.java
@FeignClient(name = "auth-service", path = "/internal/users")
public interface AuthClient { ... }
```

Khi appointment gọi `authClient.getActiveDoctorCount()`, chuyện xảy ra phía sau:

```
1. Feign: "cần gọi service tên auth-service"
2. LoadBalancer hỏi danh bạ (đã tải về từ Eureka, làm mới mỗi 5 giây):
   auth-service → [172.18.0.7:8081]
3. Chọn một địa chỉ (có nhiều thì chia lượt)
4. Gửi HTTP GET http://172.18.0.7:8081/internal/users/active-doctors/count
```

## 4. Thử tận tay

Cổng 8761 không mở ra máy trong cấu hình chuẩn (chỉ Gateway được mở). Mở bằng file dev:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d service-registry
```

Mở trình duyệt `http://localhost:8761`. Bạn thấy bảng **Instances currently registered with Eureka** gồm
`API-GATEWAY`, `AUTH-SERVICE`, `APPOINTMENT-SERVICE`, `CLINICAL-SERVICE`, `BILLING-PHARMACY-SERVICE`, `NOTIFICATION-SERVICE`,
`REPORTING-SERVICE`, mỗi dòng kèm IP:cổng.

Thử dừng một service rồi chờ khoảng 30 giây, tải lại trang:
```bash
docker compose stop billing-pharmacy-service    # BILLING-PHARMACY-SERVICE biến khỏi danh sách
docker compose start billing-pharmacy-service   # đăng ký lại sau khi khởi động xong
```

Muốn đóng lại cổng 8761: `docker compose up -d service-registry` (không kèm file dev).

## 5. Lưu ý trong dự án

- `service-registry` **không** phụ thuộc `common-library` (xem [pom.xml của nó](../vetclinic-backend/service-registry/pom.xml)): registry
  càng ít phụ thuộc càng khó hỏng.
- Trong Docker Compose, Gateway được khởi động **sau cùng** (khi mọi service đã healthy) để lần đầu tải danh bạ đã có đủ service
  (file 16).
- Nếu Eureka sập khi hệ thống đang chạy: các service vẫn gọi nhau được một thời gian vì mỗi bên giữ **bản sao danh bạ** trong bộ
  nhớ. Chỉ service mới khởi động là không tìm được ai.

## 6. Tự kiểm tra

1. `lb://auth-service` khác `http://auth-service:8081` ở điểm nào?
2. Service "chết" bị Eureka phát hiện bằng cách nào?
3. Vì sao dự án tắt `use-read-only-response-cache`?
4. Tên `auth-service` trong `@FeignClient(name = "auth-service")` lấy từ đâu?
