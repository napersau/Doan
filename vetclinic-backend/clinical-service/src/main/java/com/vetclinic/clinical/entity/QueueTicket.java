package com.vetclinic.clinical.entity;

import java.util.UUID;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Phiếu xếp hàng (SM-07, thêm 17/11/2026): dữ liệu VẬN HÀNH đổi liên tục trong ngày, tách khỏi hồ sơ khám. Mỗi phiếu là một lượt
 * chờ của một đợt khám ở hàng đợi Bác sĩ / phòng Xét nghiệm / phòng CĐHA. Phát khi đã thu tiền dịch vụ cần thu trước
 * ({@code services.requires_prepayment}), hoặc ngay với ca cấp cứu (priority 2) và ca có đủ kết quả CLS (priority 1).
 * Thứ tự hàng đợi: {@code priority DESC, issued_at, id}. "Gọi" chỉ cập nhật {@code last_called_at}.
 */
@Entity
@Table(name = "queue_tickets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueTicket {

    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_RESULTS_READY = 1;
    public static final int PRIORITY_EMERGENCY = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_type", nullable = false)
    private QueueType queueType;

    /** Ngày phát phiếu theo giờ phòng khám: số phiếu đánh lại từ 1 mỗi ngày theo từng loại hàng đợi. */
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "ticket_no", nullable = false)
    private int ticketNo;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    /** Chỉ phiếu DOCTOR: Bác sĩ của hàng đợi; null = danh sách chờ phân công. LFK: auth.users */
    @Column(name = "doctor_id")
    private UUID doctorId;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.WAITING;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** Thu ngân phát phiếu; null = hệ thống tự phát (cấp cứu, ca có kết quả CLS). LFK: auth.users */
    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "last_called_at")
    private Instant lastCalledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
