package com.vetclinic.clinical.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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

    /**
     * Một phần tử của hàng đợi để tính "Đẩy lùi 1 bậc".
     *
     * @param priority {@code is_emergency AND skip_count = 0}: ca cấp cứu CHƯA bị lùi, đứng trước mọi ca còn lại
     */
    public record Entry(long queueOrder, boolean priority) {
    }

    /**
     * "Đẩy lùi 1 bậc" (BR-34, State Machine Q4): ca ở vị trí {@code index} bị gọi mà khách vắng nên được xếp ra NGAY SAU người kế
     * tiếp. Trả về {@code queue_order} MỚI của ca đó, hoặc {@code null} nếu không còn ai phía sau (giữ nguyên vị trí).
     * {@code queue} là hàng đợi hiện tại ĐÃ SẮP theo thứ tự gọi và có chứa chính ca bị lùi. Ca bị lùi tăng skip_count nên mất ưu
     * tiên cấp cứu (xếp như ca thường).
     * <ul>
     *   <li>người kế tiếp là ca thường: trung bình cộng queue_order của ca ngay sau và ca sau nữa; không có ca sau nữa thì = ca
     *       ngay sau + 1;</li>
     *   <li>người kế tiếp là ca cấp cứu chưa bị lùi: ca bị lùi đứng đầu nhóm ca thường, tức = queue_order của ca thường đầu tiên
     *       - 1 (không có ca thường nào thì giữ nguyên queue_order, vì mất ưu tiên là đã tự xếp sau mọi ca cấp cứu).</li>
     * </ul>
     */
    public static Long afterPushBack(List<Entry> queue, int index) {
        if (index + 1 >= queue.size()) {
            return null;
        }
        Entry next = queue.get(index + 1);
        if (next.priority()) {
            for (int i = 0; i < queue.size(); i++) {
                if (i != index && !queue.get(i).priority()) {
                    return queue.get(i).queueOrder() - 1;
                }
            }
            return queue.get(index).queueOrder();
        }
        if (index + 2 < queue.size()) {
            return Math.floorDiv(next.queueOrder() + queue.get(index + 2).queueOrder(), 2);
        }
        return next.queueOrder() + 1;
    }
}
