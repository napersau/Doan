package com.vetclinic.common.event.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Lô sự kiện đến hạn gửi, theo thứ tự phát sinh. {@code FOR UPDATE SKIP LOCKED}: nếu service chạy nhiều bản, mỗi bản
     * lấy các dòng khác nhau, không bản nào phải chờ khóa (chỉ mục {@code idx_outbox_pending (status, next_attempt_at, id)}).
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("now") Instant now, @Param("limit") int limit);

    Optional<OutboxEvent> findByEventId(UUID eventId);

    long countByStatus(OutboxStatus status);

    /** Dọn các dòng đã gửi quá hạn giữ (Kiến trúc v4.0 mục 5.3: 7 ngày). */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.publishedAt < :before")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxStatus status, @Param("before") Instant before);
}
