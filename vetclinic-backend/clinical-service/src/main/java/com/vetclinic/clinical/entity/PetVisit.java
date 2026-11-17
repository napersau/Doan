package com.vetclinic.clinical.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.vetclinic.clinical.constant.VisitStatus;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pet_visits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetVisit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_code", nullable = false, unique = true, length = 50)
    private String visitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    /** Khóa ngoại logic -> pet_clinic_appointment_db.appointments(id). NULL = khách vãng lai. */
    @Column(name = "appointment_id", unique = true)
    private Long appointmentId;

    /** Khóa ngoại logic -> auth.users(id): Bác sĩ phụ trách (do Lễ tân phân công). */
    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "receptionist_id", nullable = false)
    private Long receptionistId;

    @Column(name = "is_emergency", nullable = false)
    private boolean emergency;

    @Column(name = "weight_at_visit", precision = 5, scale = 2)
    private BigDecimal weightAtVisit;

    @Column(name = "temperature", precision = 4, scale = 1)
    private BigDecimal temperature;

    @Column(name = "initial_symptoms", columnDefinition = "TEXT")
    private String initialSymptoms;

    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    @Column(name = "treatment_plan", columnDefinition = "TEXT")
    private String treatmentPlan;

    @Column(name = "doctor_advice", columnDefinition = "TEXT")
    private String doctorAdvice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private VisitStatus status = VisitStatus.WAITING;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    // Thông tin xếp hàng (số phiếu, thứ tự, giờ gọi) nằm ở queue_tickets (SM-07), không lưu trên hồ sơ khám (17/11/2026).

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
