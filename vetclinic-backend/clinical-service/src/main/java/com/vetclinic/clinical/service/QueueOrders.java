package com.vetclinic.clinical.service;

import java.time.Clock;
import java.time.Instant;

/**
 * Giá trị {@code queue_order} (BIGINT) của hàng đợi: thời điểm tiếp nhận / chỉ định tính bằng EPOCH MICRO GIÂY.
 * Số nhỏ được gọi trước; micro giây đủ mịn để hai ca tiếp nhận trong cùng giây vẫn có thứ tự, và còn khoảng trống giữa hai ca
 * để "Đẩy lùi 1 bậc" chèn vào bằng trung bình cộng.
 */
public final class QueueOrders {

    private QueueOrders() {
    }

    public static long of(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    public static long now(Clock clock) {
        return of(clock.instant());
    }
}
