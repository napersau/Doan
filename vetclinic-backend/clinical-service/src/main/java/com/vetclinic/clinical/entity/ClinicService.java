package com.vetclinic.clinical.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.vetclinic.clinical.constant.ServiceCategory;
import java.math.BigDecimal;

@Entity
@Table(name = "services")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicService {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ServiceCategory category;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Thuế suất VAT hiện hành (0.05 = 5%, mặc định 0). Snapshot sang service_invoices khi chỉ định (BR-25). */
    @Column(name = "vat_rate", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal vatRate = BigDecimal.ZERO.setScale(2);

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * Chính sách thu trước (BR-35, 17/11/2026): TRUE = thu tiền xong mới phát phiếu xếp hàng (khám, Xét nghiệm, CĐHA);
     * FALSE = dịch vụ làm tại phòng, thu khi chốt ca.
     */
    @Column(name = "requires_prepayment", nullable = false)
    private boolean requiresPrepayment;
}
