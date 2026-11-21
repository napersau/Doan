package com.vetclinic.billing.entity;

import java.util.UUID;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Đơn thuốc = chỉ định chuyên môn của bác sĩ (v3.0): KHÔNG có giá, KHÔNG có trạng thái. Việc khách mua tại phòng khám
 * thể hiện qua {@link MedicineInvoice}; mua ngoài thì không phát sinh gì thêm.
 */
@Entity
@Table(name = "prescriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Prescription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Khóa ngoại logic -> pet_clinic_clinical_db.pet_visits(id); mỗi đợt khám tối đa một đơn (BR-18). */
    @Column(name = "visit_id", nullable = false, unique = true)
    private UUID visitId;

    @Column(name = "doctor_notes", columnDefinition = "TEXT")
    private String doctorNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<PrescriptionItem> items = new ArrayList<>();

    public void addItem(PrescriptionItem item) {
        item.setPrescription(this);
        items.add(item);
    }
}
