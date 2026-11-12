package com.vetclinic.appointment.repository;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.entity.Appointment;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    /**
     * Tham số null thì bỏ qua điều kiện tương ứng. appointments không còn cột owner_id nên "lịch của một chủ nuôi" được lọc
     * bằng {@code petIds} = các thú cưng của chủ đó (lấy từ clinical-service); danh sách rỗng nghĩa là không có lịch nào.
     */
    public static Specification<Appointment> filter(Collection<Long> petIds, Long petId, String code,
                                                    LocalDate fromDate, LocalDate toDate, AppointmentStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (petIds != null) {
                predicates.add(petIds.isEmpty() ? cb.disjunction() : root.get("petId").in(petIds));
            }
            if (petId != null) {
                predicates.add(cb.equal(root.get("petId"), petId));
            }
            if (code != null && !code.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("appointmentCode")), code.trim().toUpperCase(Locale.ROOT)));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("appointmentDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("appointmentDate"), toDate));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
