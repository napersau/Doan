package com.vetclinic.clinical.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import java.time.Instant;

@Entity
@Table(name = "visit_service_details")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitServiceDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ServiceDetailStatus status = ServiceDetailStatus.PENDING;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Thứ tự trong hàng đợi phòng Xét nghiệm / CĐHA, số NHỎ gọi trước (v3.0). Gán = thời điểm chỉ định, epoch micro giây
     * (QueueOrders). "Đẩy lùi 1 bậc" hoạt động như ở pet_visits.
     */
    @Column(name = "queue_order", nullable = false)
    private long queueOrder;

    /** Số lần bị "Đẩy lùi 1 bậc". Giá/VAT KHÔNG nằm ở đây nữa mà ở service_invoices (1-1 với dòng chỉ định này). */
    @Column(name = "skip_count", nullable = false)
    @Builder.Default
    private int skipCount = 0;
}
