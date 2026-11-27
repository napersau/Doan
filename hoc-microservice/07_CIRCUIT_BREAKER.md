# 07. Circuit Breaker: service kia hỏng thì không kéo mình sập theo

## 1. Vấn đề

auth-service đang **treo** (không chết hẳn, chỉ không trả lời). Mỗi request đặt lịch gọi auth phải chờ hết **2 giây timeout** mới
báo lỗi. Giờ cao điểm 100 request/giây:
- 100 luồng xử lý cùng đứng chờ 2 giây → appointment hết luồng, **không phục vụ được cả các request không cần auth**.
- auth vốn đã quá tải lại bị dồn thêm request → càng khó hồi phục.

Lỗi lan dây chuyền như vậy gọi là **cascading failure**.

## 2. Lý thuyết

**Circuit Breaker** (cầu dao) bọc quanh mỗi lời gọi ra ngoài. Giống cầu dao điện trong nhà: chập điện liên tục thì **nhảy cầu dao**,
ngắt hẳn, khỏi cháy nhà. Cầu dao có 3 trạng thái:

```
               tỷ lệ lỗi ≥ 50% (trong 20 lời gọi gần nhất)
   ┌────────┐ ───────────────────────────────────────► ┌────────┐
   │ CLOSED │                                          │  OPEN  │  ← mọi lời gọi bị TỪ CHỐI NGAY,
   │ (đóng) │ ◄───────────┐                            │ (mở)   │    không chờ timeout
   └────────┘   3 lời gọi │                            └───┬────┘
   gọi bình     thử thành │                                │ sau 10 giây
   thường       công      │        ┌───────────┐           │
                          └─────── │ HALF_OPEN │ ◄─────────┘
                                   │ (nửa mở)  │  cho 3 lời gọi đi thử
                     thử thất bại  └─────┬─────┘
                     ──────────────────► quay lại OPEN
```

- **CLOSED**: bình thường, mọi lời gọi đi qua; cầu dao đếm số lời gọi lỗi.
- **OPEN**: bị ngắt. Lời gọi thất bại **ngay lập tức** (vài micro giây thay vì chờ 2 giây). Luồng xử lý được giải phóng; auth được
  "nghỉ" để hồi phục.
- **HALF_OPEN**: sau 10 giây, cho vài lời gọi đi **thử**. Thành công thì đóng lại; vẫn lỗi thì mở tiếp.

Thư viện dùng: **Resilience4j**.

## 3. Trong dự án

### 3.1. Cấu hình mạch

`application.yml` của appointment, clinical, notification:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 20                  # xét 20 lời gọi gần nhất
        minimum-number-of-calls: 10              # phải có ít nhất 10 lời gọi mới đánh giá (tránh 1/1 lỗi = 100%)
        failure-rate-threshold: 50               # ≥ 50% lỗi thì mở mạch
        wait-duration-in-open-state: 10s         # mở 10 giây rồi chuyển sang nửa mở
        permitted-number-of-calls-in-half-open-state: 3
        record-failure-predicate: com.vetclinic.common.feign.FeignFailurePredicate   # thế nào là "lỗi"
```

### 3.2. "Thế nào là lỗi?": điểm tinh tế nhất

Không phải lỗi nào cũng chứng tỏ service kia hỏng. clinical trả `404 PET_NOT_FOUND` nghĩa là clinical **vẫn khỏe**, chỉ là người dùng
nhập sai id. Nếu đếm 404 là lỗi, mười người nhập sai id liên tiếp sẽ làm **mở mạch**, khóa luôn người dùng đúng.

[FeignFailurePredicate.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/feign/FeignFailurePredicate.java):

```java
public boolean test(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
        if (cause instanceof AppException app) {
            // lỗi đã dựng từ phản hồi của service con: chỉ 5xx (service con hỏng) mới tính là lỗi
            return app.getErrorCode().getHttpStatus().is5xxServerError();
        }
        cause = cause.getCause() == cause ? null : cause.getCause();
    }
    return true;   // timeout, mất kết nối... → tính là lỗi
}
```

| Tình huống | Tính là lỗi? |
|---|---|
| Timeout 2 giây, không kết nối được | Có |
| Service con trả 500, 503 | Có |
| Service con trả 400, 403, 404, 409 (lỗi nghiệp vụ) | **Không** |

### 3.3. Gắn cầu dao vào MỌI Feign client mà không phải sửa từng chỗ

Cách thông thường là bật `spring.cloud.openfeign.circuitbreaker.enabled: true`. Nhóm đã thử và **gặp lỗi**: bộ cài sẵn của Spring Cloud
chạy lời gọi ở **luồng khác** và **bọc** mọi lỗi trong `NoFallbackAvailableException`. Hậu quả: `404 PET_NOT_FOUND` biến thành lỗi 500,
và luồng mới mất header người dùng.

Nên nhóm tự viết [ResilientFeignConfig.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/feign/ResilientFeignConfig.java):
bọc chỗ Feign "thực thi một phương thức" (`InvocationHandler`) bằng cầu dao, chạy **trên cùng luồng**, ném lại **đúng lỗi gốc**:

```java
@Bean
public FeignBuilderCustomizer circuitBreakerFeignCustomizer(ObjectProvider<CircuitBreakerRegistry> registryProvider) {
    return builder -> builder.invocationHandlerFactory(new CircuitBreakingInvocationHandlerFactory(registryProvider));
}

// Mỗi khi gọi một phương thức của Feign client:
return (proxy, method, args) -> {
    // MỖI PHƯƠNG THỨC một mạch riêng, ví dụ "auth-service.getActiveDoctorCount"
    CircuitBreaker breaker = registry.circuitBreaker(target.name() + "." + method.getName());
    try {
        return breaker.executeCheckedSupplier(() -> inner.invoke(proxy, method, args));   // gọi thật, qua cầu dao
    } catch (InvocationTargetException e) {
        throw e.getCause() != null ? e.getCause() : e;    // ném lại lỗi gốc (vd. AppException PET_NOT_FOUND)
    }
};
```

Mỗi phương thức một mạch: `getActiveDoctorCount` hỏng không làm mở mạch của `getUser`.

### 3.4. Mạch mở thì người dùng thấy gì

Khi mạch OPEN, Resilience4j ném `CallNotPermittedException`.
[CircuitBreakerExceptionHandler.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/exception/CircuitBreakerExceptionHandler.java)
đổi thành phản hồi 503 dễ hiểu:

```java
@ExceptionHandler(CallNotPermittedException.class)
public ResponseEntity<ApiResponse<Object>> handleOpenCircuit(CallNotPermittedException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE,
            "Dịch vụ phụ thuộc đang quá tải hoặc tạm ngừng, vui lòng thử lại sau ít giây"));
}
```

Cùng mã `SYS_1004` với lỗi mất kết nối, nên giao diện xử lý một kiểu.

### 3.5. Fallback: có hay không?

**Fallback** là "phương án dự phòng" khi gọi lỗi (ví dụ trả giá trị mặc định). Dự án áp dụng theo quy tắc 5 của file 06:
- Lời gọi **chỉ để hiển thị** → nơi gọi tự `try/catch`, dùng snapshot hoặc để trống (đó chính là fallback).
- Lời gọi **là điều kiện để ghi** → **không có fallback**, báo 503. Không bao giờ "đoán" số Bác sĩ hay "đoán" thuốc còn tồn tại.

## 4. Thử tận tay

Dự án có sẵn kịch bản kiểm tra ở [scripts/feign_resilience_check.py](../scripts/feign_resilience_check.py) và trong
[e2e/v4_resilience.mjs](../e2e/v4_resilience.mjs). Tự thử bằng tay:

```bash
docker compose pause auth-service          # "treo" auth (không chết, chỉ không trả lời) → lời gọi bị timeout
```

Đăng nhập sẵn tài khoản Lễ tân trên giao diện, thử đặt lịch nhiều lần liên tiếp:
- Khoảng 10 lần đầu: mỗi lần chờ ~2 giây rồi báo lỗi (timeout).
- Sau đó: báo lỗi **ngay lập tức** "Dịch vụ phụ thuộc đang quá tải..." → mạch đã OPEN.

```bash
docker compose unpause auth-service
```

Chờ hơn 10 giây rồi đặt lịch lại → thành công (HALF_OPEN thử được, mạch đóng lại).

## 5. Tự kiểm tra

1. Ba trạng thái của Circuit Breaker là gì? Điều kiện chuyển từ trạng thái này sang trạng thái kia?
2. Vì sao lỗi 404 **không** được tính là lỗi cho Circuit Breaker?
3. Khi mạch mở, lời gọi mất bao lâu để báo lỗi? Điều đó giúp gì cho service đang gọi?
4. Vì sao nhóm không dùng `spring.cloud.openfeign.circuitbreaker.enabled`?
5. Lời gọi đếm Bác sĩ khi đặt lịch có fallback không? Vì sao?
