# 11. Hàng đợi khám trên Redis: trạng thái dùng chung, nhanh và nguyên tử

## 1. Vấn đề

Hàng đợi phòng khám đổi liên tục: phát phiếu, gọi số, bắt đầu khám, kết thúc. Nhiều người thao tác **cùng lúc** (2 thu ngân phát phiếu,
3 bác sĩ bấm "Gọi người tiếp theo"). Nếu lưu hàng đợi trong MySQL:
- Hai thu ngân cùng phát phiếu → cùng đọc "số lớn nhất = 41" → cùng cấp **số 42** (trùng số).
- Hai bác sĩ cùng bấm "gọi tiếp" → cùng lấy được **một** khách.
- Phải khóa bảng, thử lại... chậm và rối.

Ngoài ra, nếu sau này chạy **2 bản** clinical-service để chịu tải, hàng đợi để trong **bộ nhớ** của một bản thì bản kia không thấy.
Trạng thái dùng chung giữa nhiều bản service phải nằm ở **bên ngoài**.

## 2. Lý thuyết

**Redis** là CSDL dạng khóa-giá trị nằm trong **bộ nhớ** (RAM), rất nhanh (dưới 1 ms một lệnh). Có sẵn các cấu trúc:

| Cấu trúc | Giống | Dùng trong dự án để |
|---|---|---|
| **String** + `INCR` | Bộ đếm | Cấp số thứ tự phiếu: `INCR` tăng 1 và trả số mới, **nguyên tử** (hai người gọi cùng lúc chắc chắn nhận hai số khác nhau) |
| **Hash** | Một object | Thông tin một phiếu (`ticketNo`, `status`, `visitId`...) |
| **Sorted Set (ZSET)** | Danh sách luôn sắp theo điểm | Hàng đợi: phần tử là id phiếu, điểm (score) quyết định thứ tự |
| **Set** | Tập hợp | Các phiếu đang mở của một đợt khám |

**Redis chạy lệnh tuần tự trên một luồng**, nên một lệnh không bao giờ bị chen giữa chừng. Nhưng một **chuỗi** lệnh (kiểm tra → tăng số →
tạo phiếu → thêm vào hàng) thì có thể bị chen giữa hai lệnh. Giải pháp: **script Lua**. Redis chạy trọn cả script như **một lệnh duy
nhất**, không ai chen vào được. Không cần khóa, không cần thử lại.

**Redis có bền không?** Redis giữ dữ liệu trong RAM; dự án bật AOF (ghi nhật ký xuống đĩa mỗi giây), nên sập thì mất tối đa ~1 giây.
Quan trọng hơn: **MySQL vẫn là nguồn sự thật**. Redis mất sạch thì dựng lại được từ MySQL (mục 3.5).

## 3. Trong dự án

### 3.1. Cấu trúc khóa

[QueueKeys.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueKeys.java):

| Khóa | Kiểu | Ý nghĩa |
|---|---|---|
| `queue:{yyyyMMdd}:DOCTOR:{doctorId}` | ZSET | Hàng đợi của một Bác sĩ hôm nay |
| `queue:{yyyyMMdd}:DOCTOR:UNASSIGNED` | ZSET | Ca chưa phân công Bác sĩ |
| `queue:{yyyyMMdd}:LABORATORY:ALL`, `...:IMAGING:ALL` | ZSET | Hàng đợi phòng Xét nghiệm / CĐHA |
| `ticket:{id}` | HASH | Thông tin một phiếu (hết hạn sau 36 giờ) |
| `queue:seq:{ngày}:{loại}` | String | Bộ đếm số phiếu |
| `queue:dedupe:{khóa}` | String | Chống phát trùng phiếu (EX-19) |
| `queue:visit:{visitId}` | SET | Các phiếu đang mở của một đợt khám |
| `queue:busy:{doctorId}` | String | Bác sĩ đang khám phiếu nào (mỗi Bác sĩ một phiếu `IN_SERVICE`) |
| `queue:meta:{ngày}` | String | Dấu hiệu "hàng đợi hôm nay còn nguyên" |

### 3.2. Điểm (score): ưu tiên trước, giờ thu tiền sau

```java
/** score = (2 − priority) × 10^13 + mốc thời gian (epoch millis) */
static final long PRIORITY_STEP = 10_000_000_000_000L;
```

| Loại ca | priority | score (đại khái) | Vị trí |
|---|---|---|---|
| Cấp cứu | 2 | 0 × 10^13 + thời gian | Đầu hàng |
| Đã đủ kết quả CLS, quay lại gặp Bác sĩ | 1 | 1 × 10^13 + thời gian | Sau cấp cứu |
| Ca thường | 0 | 2 × 10^13 + thời gian | Theo thứ tự **giờ thu tiền** |

ZSET sắp theo score tăng dần. Thời gian tính bằng **giờ thu tiền** (`paidAt` trong sự kiện), không phải giờ phiếu được tạo: hệ thống chậm
vài giây (RabbitMQ trễ) thì khách vẫn giữ đúng chỗ, **không bị tụt hạng**.

### 3.3. Script phát phiếu `issue_ticket.lua`

[issue_ticket.lua](../vetclinic-backend/clinical-service/src/main/resources/queue/lua/issue_ticket.lua) (đủ toàn bộ):

```lua
-- KEYS[1] = hàng đợi (ZSET), KEYS[2] = bộ đếm số, KEYS[3] = khóa chống trùng, KEYS[4] = các phiếu của đợt khám
local existed = redis.call('GET', KEYS[3])
if existed then                                   -- sự kiện tới lần 2 → trả lại phiếu cũ, KHÔNG phát phiếu mới
  local no = redis.call('HGET', 'ticket:' .. existed, 'ticketNo')
  if not no then no = '' end
  return { existed, no, '1' }
end
local no = redis.call('INCR', KEYS[2])             -- cấp số thứ tự (không bao giờ trùng)
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
local tkey = 'ticket:' .. ARGV[1]
redis.call('HSET', tkey, 'ticketNo', no, 'status', 'WAITING', 'calledCount', 0, unpack(ARGV, 5))   -- tạo phiếu
redis.call('EXPIRE', tkey, tonumber(ARGV[3]))
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])      -- đưa vào hàng đợi với score
redis.call('SADD', KEYS[4], ARGV[1])
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[3]))
redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[4]))   -- đánh dấu đã phát
return { ARGV[1], tostring(no), '0' }
```

Cả đoạn chạy **một mạch**. Hai thu ngân bấm cùng lúc thì Redis chạy script này hai lần **nối tiếp nhau**, mỗi lần nhận một số khác nhau.
Phần đầu script (khóa `queue:dedupe`) chính là **idempotent consumer** của Redis (file 10).

Dự án có 6 script: `issue_ticket` (phát), `move_ticket` (chuyển phiếu sang Bác sĩ khác, giữ số và thứ tự), `call_ticket` (gọi số),
`start_ticket` (bắt đầu phục vụ), `finish_ticket` (kết thúc), `ensure_seq` (đảm bảo số không lùi về số đã dùng).

Java gọi script trong [QueueRedisRepository.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueRedisRepository.java)
và đổi lỗi Redis thành mã nghiệp vụ:

```java
List<?> result = guard(() -> redis.execute(ISSUE, keys, args));

/** Lỗi hạ tầng Redis (mất kết nối, quá thời gian chờ) -> CLIN_1318 */
} catch (DataAccessException e) {
    throw new AppException(ErrorCode.QUEUE_UNAVAILABLE);   // 503
}
```

### 3.4. Ai phát phiếu và khi nào: kết hợp sự kiện + Redis

Phiếu **không** phát trong giao dịch thu tiền. Chuỗi thật sự là:

```
Thu ngân thu tiền ──► [giao dịch MySQL: hóa đơn PAID + outbox ServiceInvoicePaidEvent]   (file 09)
                       │
                       ▼ OutboxRelay gửi RabbitMQ
              queue "clinical-service.invoice.service_paid"
                       │
                       ▼
              QueueIssuerListener.onInvoicePaid()   ← clinical nghe chính sự kiện của mình
                 1. khóa dòng đợt khám (FOR UPDATE), đọc lại trạng thái từ MySQL (không tin payload)
                 2. ca đã hủy / đã có phiếu → bỏ qua
                 3. gọi issue_ticket.lua trên Redis
                       │
                       ▼
              phát QueueTicketIssuedEvent → notification → màn hình hiện "Số 42"
```

[QueueIssuerListener.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueIssuerListener.java) (rút gọn):

```java
@RabbitListener(queues = ClinicalRabbitConfig.INVOICE_PAID_QUEUE)
public void onInvoicePaid(ServiceInvoicePaidEvent event) {
    tx.executeWithoutResult(s -> issueForPaidInvoices(event));
}

private void issueForPaidInvoices(ServiceInvoicePaidEvent e) {
    PetVisit visit = visitRepository.findByIdForUpdate(e.visitId()).orElse(null);   // khóa dòng
    if (visit == null || visit.getStatus() == VisitStatus.CANCELLED) {
        return;                                                                     // ca đã hủy: không phát
    }
    int priority = visit.isEmergency() ? 2 : 0;
    // gom các dòng theo loại hàng đợi (DOCTOR / LABORATORY / IMAGING); dịch vụ cùng phòng chung một phiếu
    ...
    queue.issue(visit, QueueType.DOCTOR, visit.getDoctorId(), priority, e.paidAt(), e.cashierId(), detailIds,
            QueueCoordinator.doctorKey(visit.getId(), false), null);                // khóa chống trùng theo đợt khám
}
```

Lý do của thiết kế này (EX-18): tiền đã thu thì sự kiện đã nằm trong outbox, nên **chắc chắn** listener sẽ được gọi. Redis sập lúc đó →
listener ném `CLIN_1318` → RabbitMQ thử lại 3 lần → vẫn lỗi thì vào DLQ → job đối soát bù phiếu (mục 3.5). Không có đường nào làm khách
trả tiền mà mất phiếu.

**Lệnh Redis chạy sau commit.** Khi Bác sĩ kết thúc ca, MySQL ghi lịch sử phiếu trong giao dịch, còn lệnh Redis `finish_ticket` chỉ chạy
**sau khi** giao dịch commit thành công ([QueueCoordinator.afterCommit](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueCoordinator.java)):

```java
private void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { safely(action); }    // giao dịch rollback thì không đụng Redis
        });
    } else {
        safely(action);
    }
}
```

### 3.5. Redis mất dữ liệu thì sao? Dựng lại từ MySQL

[QueueRebuildService.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueRebuildService.java)
đọc MySQL (ca đã nộp phí khám, ca cấp cứu, ca `RESULTS_READY`, CLS đã thu) và phát lại các phiếu còn thiếu. Ba nơi gọi:

| Nơi gọi | Khi nào |
|---|---|
| [QueueRebuildJob](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueRebuildJob.java) | Lúc clinical khởi động (`@EventListener(ApplicationReadyEvent.class)`), nếu mất khóa `queue:meta` |
| [QueueReconcileJob](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/queue/QueueReconcileJob.java) | Mỗi 60 giây: bù phiếu của sự kiện đã rơi vào DLQ |
| `POST /api/v1/clinical/queue-tickets/rebuild` | Admin bấm tay |

`ensure_seq.lua` nâng bộ đếm lên bằng số phiếu lớn nhất đã có trong bảng lịch sử `queue_ticket_histories`, để số phiếu mới không trùng số
đã phát hôm nay.

## 4. Thử tận tay

Redis không mở cổng ra ngoài, dùng `redis-cli` trong container:

```bash
docker exec -it vetclinic-redis-1 redis-cli
```

```
KEYS queue:*                         # liệt kê các hàng đợi (chỉ dùng khi học, KEYS chậm trên dữ liệu lớn)
ZRANGE queue:20261127:DOCTOR:UNASSIGNED 0 -1 WITHSCORES   # thay ngày hôm nay; thấy id phiếu kèm score
HGETALL ticket:<id phiếu ở trên>     # thông tin phiếu: ticketNo, status, visitId...
GET queue:seq:20261127:DOCTOR        # số phiếu lớn nhất đã cấp hôm nay
```

Thí nghiệm "Redis mất sạch":
```bash
docker exec vetclinic-redis-1 redis-cli FLUSHALL        # xóa toàn bộ Redis (chỉ làm trên máy dev!)
docker compose restart clinical-service                  # QueueRebuildJob dựng lại khi khởi động
```
Mở màn hình hàng đợi của Lễ tân: các phiếu đang chờ hiện lại đủ, **giữ nguyên số**.

## 5. Tự kiểm tra

1. Vì sao `INCR` của Redis cấp số không bao giờ trùng, còn "đọc số lớn nhất + 1" trong MySQL thì có thể trùng?
2. Script Lua giải quyết vấn đề gì mà từng lệnh Redis riêng lẻ không giải quyết được?
3. Tính score của ca cấp cứu và ca thường. Vì sao ca cấp cứu luôn đứng trước?
4. Vì sao score dùng giờ **thu tiền** thay vì giờ phát phiếu?
5. Vì sao lệnh `finish_ticket` chỉ chạy sau khi giao dịch MySQL commit?
6. Redis mất sạch dữ liệu thì hệ thống phục hồi thế nào? Vì sao làm được?
