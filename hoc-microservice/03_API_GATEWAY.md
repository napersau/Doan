# 03. API Gateway: một cổng vào duy nhất

## 1. Vấn đề

Giao diện React cần gọi 6 service. Nếu gọi thẳng:
- FE phải biết 6 địa chỉ, 6 cổng.
- Mỗi service phải tự kiểm tra đăng nhập, tự cấu hình CORS → lặp code 6 lần, dễ sót.
- Các endpoint nội bộ (service gọi nhau) cũng lộ ra Internet.
- Mở 6 cổng ra ngoài = 6 cửa cho kẻ tấn công.

## 2. Lý thuyết

**API Gateway** là **cổng vào duy nhất** đứng trước mọi service. Nó làm các việc "chung" một lần cho tất cả:

| Việc | Ý nghĩa |
|---|---|
| **Routing** (định tuyến) | Nhìn đường dẫn URL để chuyển tới đúng service |
| **Xác thực** | Kiểm tra token đăng nhập một lần tại cổng |
| **CORS** | Cho phép trình duyệt ở domain khác gọi API |
| **Che giấu nội bộ** | Không chuyển các đường dẫn nội bộ ra ngoài |

Ví dụ đời thường: **quầy bảo vệ** ở cổng bệnh viện. Ai vào phải trình thẻ, bảo vệ chỉ đường "khám tim lên tầng 3". Khách không
cần biết sơ đồ bên trong, và không ai đi lối nhân viên được.

Dự án dùng **Spring Cloud Gateway** bản **WebFlux** (lập trình phản ứng, *reactive*: một luồng xử lý được rất nhiều request cùng lúc,
hợp với việc chỉ chuyển tiếp request).

## 3. Trong dự án

Module [api-gateway/](../vetclinic-backend/api-gateway/) chỉ có 4 file Java:

| File | Vai trò |
|---|---|
| [ApiGatewayApplication.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/ApiGatewayApplication.java) | Hàm main |
| [GatewayAuthProperties.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/config/GatewayAuthProperties.java) | Đọc cấu hình: khóa JWT, danh sách đường dẫn công khai |
| [JwtValidator.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/security/JwtValidator.java) | Kiểm tra chữ ký + hạn của token |
| [JwtAuthenticationFilter.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/security/JwtAuthenticationFilter.java) | Bộ lọc chạy cho **mọi** request |

### 3.1. Routing: viết bằng cấu hình, không cần code

[api-gateway/application.yml](../vetclinic-backend/api-gateway/src/main/resources/application.yml):

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: auth-service
    uri: lb://auth-service                 # lb:// = hỏi Eureka (file 02)
    predicates:
      - Path=/api/v1/auth/**,/api/v1/users/**,/api/v1/staff/**,/api/v1/roles/**
  - id: appointment-service
    uri: lb://appointment-service
    predicates:
      - Path=/api/v1/appointments/**
  - id: clinical-service
    uri: lb://clinical-service
    predicates:
      - Path=/api/v1/clinical/**,/api/v1/pets/**,/api/v1/species/**,/api/v1/catalog/**,/files/**
  - id: billing-pharmacy-service
    uri: lb://billing-pharmacy-service
    predicates:
      - Path=/api/v1/billing/**,/api/v1/pharmacy/**
  - id: reporting-service
    uri: lb://reporting-service
    predicates:
      - Path=/api/v1/reports/**
  - id: notification-service
    uri: lb://notification-service
    predicates:
      - Path=/api/v1/notifications/**
  - id: notification-websocket
    uri: lb:ws://notification-service      # ws:// = chuyển tiếp cả kết nối WebSocket (file 13)
    predicates:
      - Path=/ws/**
```

Đọc như sau: "request có đường dẫn khớp `Path` thì chuyển tới `uri`". Ví dụ:

```
GET http://localhost:8080/api/v1/pets/123
  → khớp route clinical-service (/api/v1/pets/**)
  → hỏi Eureka địa chỉ clinical-service → 172.18.0.9:8083
  → chuyển thành GET http://172.18.0.9:8083/api/v1/pets/123 (giữ nguyên đường dẫn)
```

**Quy ước quan trọng:** prefix trong route, trong `@RequestMapping` của Controller và trong Feign client phải khớp nhau. Đổi một
chỗ phải đổi đủ ba.

### 3.2. Chặn `/internal/**`: bảo vệ bằng cách "không có đường"

Các service có endpoint dạng `/internal/...` để **service khác** gọi (ví dụ
[InternalUserController.java](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/controller/InternalUserController.java)
`/internal/users/active-doctors/count`). Các endpoint này **không cần đăng nhập** (vì người gọi có thể là một listener RabbitMQ,
không có người dùng nào).

Vậy làm sao người ngoài không gọi được? **Không có route nào khớp `/internal/**`** → Gateway trả 404. Và cổng của service con
**không mở ra máy host** (chỉ Gateway mở cổng 8080), nên cách duy nhất tới `/internal` là từ **bên trong mạng Docker**.

### 3.3. Bộ lọc JWT: chạy cho mọi request

[JwtAuthenticationFilter.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/security/JwtAuthenticationFilter.java)
cài `GlobalFilter` (áp dụng cho mọi route), `getOrder() = -100` để chạy **trước** việc định tuyến:

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    // BƯỚC 1: luôn XÓA header X-User-Id / X-User-Roles do client tự gửi
    ServerHttpRequest request = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(SecurityConstants.HEADER_USER_ID);
                headers.remove(SecurityConstants.HEADER_USER_ROLES);
            }).build();

    // BƯỚC 2: đường dẫn công khai (login, xem khung giờ...) hoặc preflight CORS → cho qua luôn
    if (HttpMethod.OPTIONS.equals(request.getMethod()) || isPublic(request)) {
        return chain.filter(exchange.mutate().request(request).build());
    }

    // BƯỚC 3: lấy token từ header "Authorization: Bearer <token>" và kiểm tra chữ ký + hạn
    Optional<Claims> claims = extractToken(request).flatMap(jwtValidator::parse);
    if (claims.isEmpty()) {
        return unauthorized(exchange.getResponse());     // 401 SYS_1002 NGAY TẠI GATEWAY
    }

    // BƯỚC 4: đọc userId + roles trong token, GẮN thành header nội bộ rồi chuyển tiếp
    Object userId = claims.get().get(SecurityConstants.CLAIM_USER_ID);
    String roles = rolesOf(claims.get().get(SecurityConstants.CLAIM_ROLES));   // ["RECEPTIONIST"] → "RECEPTIONIST"
    ServerHttpRequest authenticated = request.mutate()
            .header(SecurityConstants.HEADER_USER_ID, String.valueOf(userId))
            .header(SecurityConstants.HEADER_USER_ROLES, roles)
            .build();
    return chain.filter(exchange.mutate().request(authenticated).build());
}
```

**Vì sao Bước 1 quan trọng nhất?** Service con tin tưởng tuyệt đối header `X-User-Id` / `X-User-Roles` (file 04). Nếu Gateway
không xóa, kẻ gian tự gửi `X-User-Roles: ADMIN` là thành Admin. Gateway xóa trước, rồi chỉ gắn lại từ token đã kiểm chữ ký.

`Mono<Void>` là kiểu của WebFlux: "một kết quả sẽ có trong tương lai". Bạn chỉ cần hiểu `chain.filter(...)` = "cho đi tiếp",
còn `unauthorized(...)` = "chặn lại, trả lỗi".

Danh sách đường dẫn công khai trong `application.yml`:
```yaml
vetclinic.gateway.public-paths:
  - /api/v1/auth/login         # chưa đăng nhập thì làm sao có token
  - /api/v1/auth/refresh
  - /api/v1/auth/logout
  - /api/v1/auth/register
  - /api/v1/appointments/slots # khách xem khung giờ trống trước khi đăng nhập
  - /files/**                  # URL ảnh CĐHA có chữ ký tạm (clinical tự kiểm)
  - /ws/**                     # WebSocket tự kiểm JWT trong frame CONNECT (file 13)
  - /actuator/health/**
```

### 3.4. CORS chỉ ở Gateway

Trình duyệt chạy ở `http://localhost:3000` gọi API ở `http://localhost:8080` (khác cổng = khác *origin*), nên trình duyệt đòi
server cho phép (CORS). Dự án cấu hình **một chỗ duy nhất** ở Gateway:

```yaml
globalcors:
  cors-configurations:
    '[/**]':
      allowed-origin-patterns: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
      allowed-methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
      allow-credentials: true
default-filters:
  - DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin, RETAIN_UNIQUE
```

Service con **không** cấu hình CORS: nếu cả hai cùng thêm header, trình duyệt thấy header trùng và từ chối. `DedupeResponseHeader`
là lớp bảo vệ thêm: lỡ trùng thì gộp lại.

## 4. Thử tận tay

Các kết quả dưới đây đã chạy thật trên hệ thống:

```bash
# 1) Không có token → bị chặn tại Gateway
curl -s http://localhost:8080/api/v1/pets
# {"status":401,"errorCode":"SYS_1002","message":"Chưa đăng nhập hoặc token đã hết hạn",...}

# 2) Gọi endpoint nội bộ từ ngoài → không có route
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/internal/users/active-doctors/count
# 404

# 3) Giả mạo header Admin → Gateway xóa header, vẫn 401
curl -s -H "X-User-Id: 019a9442-d810-7000-8000-000000000001" -H "X-User-Roles: ADMIN" http://localhost:8080/api/v1/pets
# {"status":401,"errorCode":"SYS_1002",...}

# 4) Đường dẫn công khai → không cần token
curl -s "http://localhost:8080/api/v1/appointments/slots?date=2026-11-30"
# {"status":200,...,"data":[{"startTime":"08:00:00",...,"capacity":10,...}]}
```

## 5. Tự kiểm tra

1. Request `GET /api/v1/billing/medicine-invoices` được chuyển tới service nào? Dựa vào dòng cấu hình nào?
2. Vì sao Gateway phải **xóa** `X-User-Id` trước khi kiểm tra token?
3. Endpoint `/internal/...` không cần đăng nhập, vậy điều gì ngăn người ngoài gọi nó? (2 lý do)
4. Vì sao không cấu hình CORS ở cả service con?
5. Thêm một service mới `inventory-service` với prefix `/api/v1/inventory/**` thì phải sửa những chỗ nào?
