# 14. Nhiều request cùng lúc: khóa và chống ghi đè trong hệ phân tán

## 1. Vấn đề

Khung 08:00 còn **1 chỗ**. Hai khách bấm đặt **cùng một lúc**:

```
Khách A: đếm số lịch đã đặt = 9 (< 10) → được đặt →  INSERT
Khách B: đếm số lịch đã đặt = 9 (< 10) → được đặt →  INSERT       ← cùng đọc trước khi bên kia ghi
Kết quả: 11 lịch trong khung chỉ chứa 10.
```

Lỗi kiểu "**kiểm tra rồi ghi**" (check-then-act) này gọi là **race condition** (tranh chấp).

Ở monolith, nhiều người quen dùng `synchronized` của Java. Ở microservice thì **không được**: `synchronized` chỉ khóa trong **một** tiến trình
Java. Chạy 2 bản appointment-service thì mỗi bản có khóa riêng, chẳng chặn được nhau. Khóa phải nằm ở nơi **mọi bản đều thấy**: CSDL
hoặc Redis.

## 2. Lý thuyết: các công cụ dự án dùng

| Công cụ | Ý tưởng | Hợp với |
|---|---|---|
| **UNIQUE** trong CSDL | CSDL từ chối dòng trùng | Không cho hai bản ghi giống nhau (mã hóa đơn, một hóa đơn / đơn thuốc) |
| **Khóa lạc quan** `@Version` | Không khóa; lúc ghi kiểm tra "dòng có bị ai sửa từ lúc mình đọc không?" | Hiếm khi tranh chấp |
| **Khóa bi quan** `SELECT ... FOR UPDATE` | Khóa **dòng** ngay lúc đọc, người khác phải chờ | Chuyển trạng thái quan trọng |
| **UPDATE có điều kiện** | Gộp kiểm tra và ghi vào **một câu SQL** | Trừ kho, thu hồi token |
| **Khóa tên** `GET_LOCK` của MySQL | Khóa một "cái tên" bất kỳ, không gắn với dòng nào | Kiểm tra trên **nhiều dòng** hoặc dòng **chưa tồn tại** (đếm chỗ rồi chèn) |
| **Lua script** Redis | Cả chuỗi lệnh chạy nguyên tử | Hàng đợi (file 11) |

## 3. Trong dự án

### 3.1. Khóa tên MySQL cho đặt lịch

`FOR UPDATE` không dùng được ở đây: cái cần khóa là "**khung 08:00 ngày 30/11**", mà khung giờ không phải một dòng trong bảng (dòng lịch
hẹn mới chưa tồn tại). Dùng **khóa tên** của MySQL:

[SlotLockService.java](../vetclinic-backend/appointment-service/src/main/java/com/vetclinic/appointment/service/SlotLockService.java):

```java
public <T> T runLocked(Collection<String> lockNames, Supplier<T> action) {
    List<String> ordered = lockNames.stream().distinct().sorted().toList();   // sắp tên → luôn khóa cùng thứ tự, không deadlock
    try (Connection connection = openDedicatedConnection()) {                  // kết nối RIÊNG, không lấy từ pool
        for (String name : ordered) {
            acquire(connection, name);                                         // SELECT GET_LOCK(name, 5)
        }
        return action.get();                                                   // action tự mở giao dịch, commit xong mới trả về
    }                                                                          // đóng kết nối = MySQL tự nhả khóa
}

public static String slotLock(LocalDate date, LocalTime start) {
    return "vetclinic:appt-slot:" + date + "T" + start;        // "vetclinic:appt-slot:2026-11-30T08:00"
}
public static String petDayLock(UUID petId, LocalDate date) {
    return "vc:apt-pet:" + petId + ":" + date;                 // chặn một thú cưng đặt hai lịch cùng ngày
}
```

Dùng trong đặt lịch (file 06 đã xem):

```java
List<String> locks = List.of(SlotLockService.slotLock(date, start), SlotLockService.petDayLock(request.petId(), date));
Appointment saved = slotLockService.runLocked(locks,
        () -> transactionTemplate.execute(tx -> insertAppointment(...)));   // [đếm chỗ + chèn] trong khóa
```

Giờ hai khách cùng đặt: khách B phải **chờ** khách A nhả khóa. Lúc B đếm thì lịch của A **đã commit** → B thấy 10/10 → báo hết chỗ.

**Ba chi tiết tinh tế** (đọc chú thích trong code):
1. **Khóa gắn với kết nối, không gắn với giao dịch.** Nếu nhả khóa trước khi commit, người sau đếm sẽ chưa thấy lịch vừa chèn. Nên khóa nằm
   trên kết nối riêng, chỉ đóng **sau khi** giao dịch commit.
2. **Kết nối riêng (không từ pool)**: đóng kết nối là MySQL tự nhả khóa, không bao giờ "quên nhả"; không chiếm kết nối của pool.
3. **Sắp xếp tên khóa**: A khóa (khung, thú cưng), B khóa (thú cưng, khung) → mỗi người giữ một khóa chờ khóa kia = **deadlock**. Sắp tên
   thì ai cũng khóa theo cùng thứ tự.

Phương thức `create()` **không** có `@Transactional` (khóa phải bao ngoài giao dịch). Bản dùng chung cho service khác là
[NamedLockService.java](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/lock/NamedLockService.java), ví dụ clinical dùng
khóa `vetclinic:visit-pet:<petId>` để một thú cưng không được tiếp đón hai lần cùng lúc.

### 3.2. Khóa bi quan `FOR UPDATE` cho chuyển trạng thái

[PetVisitRepository.java](../vetclinic-backend/clinical-service/src/main/java/com/vetclinic/clinical/repository/PetVisitRepository.java):

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)                   // sinh ra SELECT ... FOR UPDATE
@Query("select v from PetVisit v where v.id = :id")
Optional<PetVisit> findByIdForUpdate(@Param("id") UUID id);
```

Ví dụ: ca có 2 xét nghiệm, **hai KTV hoàn tất cùng lúc**. Mỗi KTV đếm "còn bao nhiêu xét nghiệm chưa xong": cả hai thấy "còn 1" (là cái của
người kia) → **không ai** chuyển ca sang `RESULTS_READY` → ca bị kẹt mãi. Giải pháp: **khóa dòng đợt khám trước**, rồi mới đếm. Người thứ hai
phải chờ, lúc đếm thì thấy "còn 0" → chuyển trạng thái.

Thu tiền cũng khóa dòng đợt khám (`support.lock(visitId)`) để hai thu ngân bấm cùng lúc thì người sau thấy hóa đơn đã `PAID` (EX-16).

### 3.3. Khóa lạc quan `@Version`

Các entity `PetVisit`, `ServiceInvoice`, `MedicineInvoice` có cột `version`:

```java
@Version
private Long version;
```

Hibernate tự sinh `UPDATE ... SET ..., version = 6 WHERE id = ? AND version = 5`. Nếu ai đó đã sửa trước (version đã là 6) thì câu lệnh
ảnh hưởng 0 dòng → Hibernate báo lỗi → trả 409. Đây là **lớp bảo vệ thứ hai** phía sau khóa dòng.

### 3.4. UPDATE có điều kiện: trừ kho

[MedicineStockRepository.deduct()](../vetclinic-backend/billing-pharmacy-service/src/main/java/com/vetclinic/billing/repository/MedicineStockRepository.java):

```java
@Modifying(flushAutomatically = true)
@Query("update MedicineStock s set s.quantityOnHand = s.quantityOnHand - :quantity, s.version = s.version + 1 "
        + "where s.medicineId = :id and s.quantityOnHand >= :quantity")    // kiểm tra + trừ trong MỘT câu
int deduct(@Param("id") UUID id, @Param("quantity") int quantity);
```

Trả về 0 dòng = **không đủ hàng**. Không bao giờ có cảnh hai người cùng đọc "còn 5", cùng trừ 4, kho thành -3.

Dùng khi thu tiền thuốc ([MedicineInvoiceServiceImpl](../vetclinic-backend/billing-pharmacy-service/src/main/java/com/vetclinic/billing/service/impl/MedicineInvoiceServiceImpl.java)):

```java
// Sắp theo id thuốc để hai giao dịch bán chồng nhau luôn khóa theo cùng thứ tự, không deadlock
for (MedicineInvoiceItem line : lines) {
    if (stockRepository.deduct(medicine.getId(), line.getQuantity()) == 0) {
        throw new AppException(ErrorCode.INSUFFICIENT_STOCK, ...);   // rollback TOÀN BỘ, kể cả các thuốc đã trừ trước đó (EX-15)
    }
    inventoryRepository.save(InventoryTransaction.builder()...quantity(-line.getQuantity())...build());   // nhật ký kho
}
invoice.setPaymentStatus(PaymentStatus.PAID);
outboxWriter.write(...MedicineInvoicePaidEvent...);   // tất cả: thu tiền + trừ kho + nhật ký + outbox = MỘT giao dịch
```

Nhờ "kho và tiền thuốc cùng một service" (file 01) mà làm được trong **một** giao dịch. Nếu kho ở service khác thì phải dùng Saga có bù trừ,
phức tạp hơn nhiều.

### 3.5. UNIQUE + mã ngẫu nhiên

Mã `APT-20261130-K7Y3MH`, `VIS-2026-...`, `SINV-...`: đuôi **ngẫu nhiên** và cột `UNIQUE`. Không dùng `count + 1` (hai người cùng đếm ra một
số). Lỡ trùng ngẫu nhiên thì CSDL từ chối, thử sinh mã khác.

### 3.6. Lỗi khóa → 409

Mọi lỗi tranh chấp đều trả mã **409** (người dùng tải lại / thử lại là được):

| Lỗi | Mã | Nơi đổi |
|---|---|---|
| `@Version` lệch, deadlock, chờ khóa dòng quá lâu | `SYS_1005` | [PersistenceExceptionHandler](../vetclinic-backend/common-library/src/main/java/com/vetclinic/common/exception/PersistenceExceptionHandler.java) |
| Chờ khóa tên `GET_LOCK` quá 5 giây | `SYS_1008` (`RESOURCE_BUSY`); riêng đặt lịch dùng mã `SLOT_BUSY` | `NamedLockService`, `SlotLockService` |

## 4. Thử tận tay

Kịch bản đặt lịch đồng thời có sẵn trong e2e. Tự thử bằng Git Bash: mở **hai** terminal, đăng nhập lấy token Lễ tân (file 04), rồi bấm Enter
ở cả hai gần như cùng lúc cho một khung còn 1 chỗ: chỉ một bên `201`, bên kia `409` / "hết chỗ".

Xem khóa tên đang giữ (trong lúc có request đặt lịch đang chạy):
```sql
SELECT * FROM performance_schema.metadata_locks WHERE OBJECT_TYPE = 'USER LEVEL LOCK';
```

Kiểm tra bất biến kho (luôn phải bằng nhau):
```sql
SELECT s.medicine_id, s.quantity_on_hand, SUM(t.quantity) AS tong_nhat_ky
FROM pet_clinic_billing_pharmacy_db.medicine_stocks s
JOIN pet_clinic_billing_pharmacy_db.inventory_transactions t ON t.medicine_id = s.medicine_id
GROUP BY s.medicine_id, s.quantity_on_hand
HAVING s.quantity_on_hand <> SUM(t.quantity);    -- kết quả phải RỖNG
```

## 5. Tự kiểm tra

1. Vì sao `synchronized` của Java không chống được đặt trùng khi chạy 2 bản appointment-service?
2. Vì sao đặt lịch dùng `GET_LOCK` thay vì `SELECT ... FOR UPDATE`?
3. Vì sao khóa tên phải giữ tới **sau** khi giao dịch commit?
4. Sắp xếp tên khóa giúp tránh lỗi gì?
5. Câu `UPDATE ... WHERE quantity_on_hand >= :qty` chống được lỗi gì? Trả 0 dòng nghĩa là gì?
6. Hai KTV hoàn tất xét nghiệm cùng lúc, ca có thể bị kẹt thế nào? Dự án tránh bằng gì?
