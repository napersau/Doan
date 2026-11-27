# 04. Xác thực xuyên service: đăng nhập một lần, mọi service đều biết bạn là ai

## 1. Vấn đề

Ở monolith, đăng nhập xong thì server lưu **session** trong bộ nhớ, mọi chức năng đọc chung. Ở microservice:
- Đăng nhập ở **auth-service**, nhưng request sau lại tới **clinical-service**: clinical không có session đó.
- Nếu mỗi request clinical đều gọi sang auth hỏi "người này là ai?" → auth thành nút thắt, auth sập là cả hệ thống sập.

Cần một cách để **mọi service tự biết** người dùng là ai mà **không phải hỏi auth**.

## 2. Lý thuyết

### 2.1. JWT (JSON Web Token)

JWT là một chuỗi gồm 3 phần ngăn bởi dấu chấm: `header.payload.signature`.

```
eyJhbGciOiJIUzI1NiJ9 . eyJ1c2VySWQiOiIwMTlh... . k3Jx9...
    header                 payload (claims)        chữ ký
```

- **payload** chứa thông tin (gọi là *claims*): id người dùng, vai trò, hạn dùng. Chỉ là Base64, **ai cũng đọc được**, nên không
  bao giờ để mật khẩu trong đó.
- **signature** = HMAC-SHA256(header + payload, **khóa bí mật**). Chỉ ai có khóa mới tạo được chữ ký đúng. Sửa một ký tự payload
  là chữ ký sai ngay.

Ví dụ đời thường: **vé xem phim có dấu mộc nổi**. Ai cũng đọc được ghế số mấy, nhưng không ai giả được dấu mộc.

→ Bất kỳ ai có **cùng khóa bí mật** đều kiểm tra được vé **mà không cần hỏi nơi phát vé**. Đây là lý do JWT hợp với microservice.

### 2.2. Access Token + Refresh Token

| | Access Token | Refresh Token |
|---|---|---|
| Dạng | JWT có chữ ký | Chuỗi ngẫu nhiên (UUID) |
| Hạn | **30 phút** | **7 ngày** |
| Dùng để | Gửi kèm mọi request | Đổi lấy Access Token mới khi hết hạn |
| Lưu ở server | **Không** (tự kiểm chữ ký) | Có, bảng `refresh_tokens` (chỉ lưu giá trị băm) |
| Thu hồi được không | Không (chỉ chờ hết hạn) | Có (đặt `revoked = true`) |

Access Token ngắn hạn để lỡ bị lộ thì chỉ dùng được tối đa 30 phút. Refresh Token dài hạn nhưng thu hồi được (khóa tài khoản, đổi
vai trò, đăng xuất).

## 3. Luồng đầy đủ trong dự án

```
① Đăng nhập
   FE ──POST /api/v1/auth/login──► Gateway (đường dẫn công khai, cho qua) ──► auth-service
   auth kiểm mật khẩu (BCrypt) → ký JWT bằng JWT_SECRET → trả { accessToken, refreshToken }

② Gọi API nghiệp vụ
   FE ──GET /api/v1/clinical/... + "Authorization: Bearer <JWT>"──► Gateway
   Gateway: kiểm chữ ký bằng CÙNG JWT_SECRET (không hỏi auth)
            → gắn header X-User-Id: <uuid>, X-User-Roles: RECEPTIONIST
            ──► clinical-service
   clinical: HeaderAuthenticationFilter đọc header → SecurityContext
            → @PreAuthorize("hasRole('RECEPTIONIST')") kiểm quyền

③ Gọi tiếp sang service khác (Feign)
   clinical ──(Feign tự chép X-User-Id, X-User-Roles)──► appointment-service

④ Access Token hết hạn
   FE nhận 401 → POST /api/v1/auth/refresh { refreshToken } → nhận cặp token MỚI → gọi lại request cũ
```

### 3.1. auth-service ký token

[JwtTokenProvider.java](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/security/JwtTokenProvider.java):

```java
public String generateAccessToken(User user) {
    Instant now = Instant.now();
    var builder = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(user.getId()))
            .claim("userId", String.valueOf(user.getId()))                      // để dạng CHUỖI (lý do bên dưới)
            .claim("roles", UserMapper.rolesOf(user).stream().map(Enum::name).toList())   // DANH SÁCH vai trò
            .claim("username", user.getUsername())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.accessTokenTtl())));       // 30 phút
    // ... thêm fullName, roomNumber để giao diện hiển thị
    return builder.signWith(signingKey, Jwts.SIG.HS256).compact();              // ký HS256
}
```

- `roles` là **danh sách** vì một người có thể có nhiều vai trò (Bác sĩ kiêm Quản trị viên).
- `userId` ghi dạng chuỗi: thư viện JSON (gson) đọc số thành `5.0`, nên để chuỗi cho an toàn.

### 3.2. Gateway kiểm chữ ký (không hỏi auth)

[JwtValidator.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/security/JwtValidator.java):

```java
public Optional<Claims> parse(String token) {
    try {
        return Optional.of(Jwts.parser()
                .verifyWith(signingKey)          // cùng khóa JWT_SECRET với auth-service
                .build()
                .parseSignedClaims(token)        // sai chữ ký hoặc hết hạn → ném JwtException
                .getPayload());
    } catch (JwtException | IllegalArgumentException e) {
        return Optional.empty();                 // → Gateway trả 401
    }
}
```

Khóa `JWT_SECRET` nằm trong file `.env` (không commit lên Git), truyền vào **ba** service: auth (ký), gateway (kiểm),
notification (kiểm token của WebSocket). [GatewayAuthProperties.java](../vetclinic-backend/api-gateway/src/main/java/com/vetclinic/gateway/config/GatewayAuthProperties.java)
từ chối khởi động nếu khóa ngắn hơn 32 byte.

### 3.3. Service con tin header do Gateway gắn

Service con **không giải mã lại JWT** (đỡ tốn công, đỡ phải giữ khóa). Nó đọc header do Gateway gắn.
[HeaderAuthenticationFilter.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/security/HeaderAuthenticationFilter.java)
nằm trong `common-library` nên mọi service dùng chung:

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
    String userId = request.getHeader("X-User-Id");
    String userRoles = request.getHeader("X-User-Roles");          // "VETERINARIAN,ADMIN"

    if (userId != null && userRoles != null && isUuid(userId.trim())) {
        List<GrantedAuthority> authorities = Arrays.stream(userRoles.split(","))
                .map(String::trim)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))   // thêm tiền tố ROLE_ ĐÚNG MỘT LẦN
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.trim(), null, authorities));
    }
    filterChain.doFilter(request, response);
}
```

Sau bước này, Spring Security coi request như đã đăng nhập. Mọi controller dùng `@PreAuthorize`:

```java
// clinical-service/controller/VisitController.java, lớp có @RequestMapping("/api/v1/clinical/pet-visits")
@PostMapping("/check-in")
@PreAuthorize("hasRole('RECEPTIONIST')")      // hasRole tự thêm "ROLE_" khi so sánh
public ResponseEntity<ApiResponse<VisitResponse>> checkIn(@Valid @RequestBody CheckInRequest request) { ... }
```

Trong service, lấy id người đang đăng nhập bằng
[SecurityUtils.getCurrentUserId()](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/security/SecurityUtils.java).

**Cấu hình bảo mật chung** ở [CommonSecurityConfig.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/security/CommonSecurityConfig.java):

```java
return http
        .csrf(AbstractHttpConfigurer::disable)          // API dùng token, không dùng cookie session
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))   // không tạo session
        .authorizeHttpRequests(auth -> auth
                .requestMatchers(publicPaths.toArray(String[]::new)).permitAll()   // gồm /internal/**, /actuator/health
                .anyRequest().authenticated())
        .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
        .build();
```

> **Vì sao an toàn khi tin header?** Vì (1) Gateway luôn xóa header do client gửi rồi mới gắn lại từ token đã kiểm, và (2) cổng
> của service con **không mở ra ngoài**. Nếu lỡ mở cổng 8083 ra Internet, ai cũng gửi được `X-User-Roles: ADMIN`. Vì vậy
> [docker-compose.yml](../docker-compose.yml) chỉ mở cổng 8080.

### 3.4. Chuyển tiếp danh tính khi service gọi nhau

Khi clinical gọi Feign sang service khác trong lúc xử lý request của người dùng, header phải đi theo để service đích cũng kiểm được
quyền. [FeignClientInterceptor.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/feign/FeignClientInterceptor.java):

```java
public void apply(RequestTemplate template) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
        return;     // không có request người dùng (listener RabbitMQ, job định kỳ) → không gắn gì, phải gọi /internal/**
    }
    HttpServletRequest request = servletAttributes.getRequest();
    copyHeader(request, template, "X-User-Id");
    copyHeader(request, template, "X-User-Roles");
}
```

### 3.5. Refresh Token xoay vòng và phát hiện đánh cắp

[AuthServiceImpl.refresh()](../vetclinic-backend/auth-service/src/main/java/com/vetclinic/auth/service/impl/AuthServiceImpl.java):

```java
@Transactional(noRollbackFor = AppException.class)   // ném lỗi nhưng VẪN GIỮ việc thu hồi token
public TokenRefreshResponse refresh(String tokenStr) {
    RefreshToken token = refreshTokenRepository.findByTokenHashWithUser(TokenHasher.sha256Hex(tokenStr))
            .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    User user = token.getUser();

    if (token.isRevoked()) {                      // token đã dùng rồi mà lại đem ra dùng → nghi bị đánh cắp
        throw reuseDetected(user);                // thu hồi TOÀN BỘ phiên của người này
    }
    if (token.getExpiryDate().isBefore(Instant.now())) { ... hết hạn ... }

    // Thu hồi nguyên tử: UPDATE ... SET revoked = true WHERE id = ? AND revoked = false
    // Hai request cùng token cùng lúc: chỉ một request được 1 dòng, request kia được 0 dòng → coi là dùng lại
    if (refreshTokenRepository.revokeIfActive(token.getId()) == 0) {
        throw reuseDetected(user);
    }
    String newToken = issueRefreshToken(user);    // cấp token MỚI (xoay vòng)
    return new TokenRefreshResponse(jwtTokenProvider.generateAccessToken(user), newToken, ...);
}
```

- Mỗi Refresh Token **chỉ dùng được một lần** (Token Rotation).
- Server chỉ lưu **SHA-256** của token: lộ CSDL cũng không dùng được token.
- **Hệ quả cho FE:** nếu hai request cùng lúc nhận 401 rồi cùng gọi `/refresh` với cùng token, request thứ hai bị coi là đánh cắp →
  đăng xuất mọi thiết bị. Vì vậy [fe/src/api/client.js](../fe/src/api/client.js) **xếp hàng** các lời gọi refresh:

```javascript
let refreshing = null;
export function refreshAccessToken() {
  if (!refreshing) {                        // chưa có ai đang refresh → mình refresh
    refreshing = fetch(`${API_URL}/api/v1/auth/refresh`, { ... })
      .then(...)
      .finally(() => { refreshing = null; });
  }
  return refreshing;                        // có người đang refresh → chờ chung kết quả đó
}
```

## 4. Thử tận tay

```bash
# Đăng nhập lấy token
curl -s -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
     -d '{"username":"letan_hoa","password":"Admin@123"}'
```

Copy `accessToken`, dán vào https://jwt.io (hoặc giải Base64 phần giữa). Payload thật của tài khoản Lễ tân:

```json
{"jti":"de0d37cd-...","sub":"019a9442-d810-7000-8000-000000000005",
 "userId":"019a9442-d810-7000-8000-000000000005","roles":["RECEPTIONIST"],
 "username":"letan_hoa","iat":1795797889,"exp":1795799689,"fullName":"Phạm Thu Hoa"}
```

`exp - iat = 1800` giây = 30 phút. Thử sửa một ký tự trong token rồi gọi API → 401.

Trong MySQL (Workbench, cổng 3307):
```sql
SELECT BIN_TO_UUID(user_id), LEFT(token_hash, 16), expiry_date, revoked
FROM pet_clinic_auth_db.refresh_tokens ORDER BY created_at DESC LIMIT 5;
```
Không có token gốc, chỉ có giá trị băm; token đã dùng để refresh có `revoked = 1`.

## 5. Tự kiểm tra

1. Gateway kiểm JWT có cần gọi sang auth-service không? Vì sao?
2. Vì sao service con không giải mã JWT mà tin header `X-User-Id`? Điều kiện gì làm việc đó an toàn?
3. Access Token bị lộ thì kẻ gian dùng được bao lâu? Refresh Token bị lộ thì hệ thống phát hiện thế nào?
4. Vì sao FE phải xếp hàng các lời gọi `/refresh`?
5. Listener RabbitMQ gọi Feign sang auth thì có header người dùng không? Phải gọi endpoint nào?
