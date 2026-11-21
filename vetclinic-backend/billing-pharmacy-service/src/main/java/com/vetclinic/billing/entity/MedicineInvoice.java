package com.vetclinic.billing.entity;

import com.vetclinic.billing.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Hóa đơn thuốc (SM-06): tùy chọn, tối đa 1 hóa đơn cho mỗi đơn thuốc ({@code prescription_id UNIQUE}, kể cả hóa đơn đã hủy).
 * Số tiền KHÔNG lưu: {@code SUM(qty * unit_price * (1 + vat_rate)) - discount_amount} (VIEW v_medicine_invoice_amounts,
 * xem {@link com.vetclinic.billing.service.MedicineInvoiceAmounts}). {@code @Version} chặn thu tiền trùng (EX-16).
 */
@Entity
@Table(name = "medicine_invoices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineInvoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_code", nullable = false, unique = true, length = 50)
    private String invoiceCode;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false, unique = true)
    private Prescription prescription;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    /** Khóa ngoại logic -> auth.users(id): thu ngân, gán khi thu tiền. */
    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<MedicineInvoiceItem> items = new ArrayList<>();

    public void addItem(MedicineInvoiceItem item) {
        item.setInvoice(this);
        items.add(item);
    }
}
