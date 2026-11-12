package com.vetclinic.appointment.repository;

import com.vetclinic.appointment.entity.Appointment;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * appointments không còn cột owner_id: "lịch của một chủ nuôi" được lọc bằng petId IN (thú cưng của chủ đó).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class AppointmentSpecificationsTest {

    private final Root<Appointment> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    private final Predicate predicate = mock(Predicate.class);

    @Test
    void nullPetIds_addsNoOwnerCondition() {
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(null, null, null, null, null, null).toPredicate(root, query, cb);

        verify(cb, never()).disjunction();
        verify(root, never()).get("petId");
    }

    @Test
    void emptyPetIds_matchesNoAppointmentAtAll() {
        when(cb.disjunction()).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(List.of(), null, null, null, null, null).toPredicate(root, query, cb);

        verify(cb).disjunction();   // chủ nuôi không có thú cưng => không được thấy lịch nào (không phải "tất cả")
    }

    @Test
    void petIds_areFilteredWithAnInClause() {
        Path<Object> petPath = mock(Path.class);
        when(root.get("petId")).thenReturn(petPath);
        when(petPath.in(any(Collection.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(List.of(11L, 12L), null, null, null, null, null).toPredicate(root, query, cb);

        verify(petPath).in(List.of(11L, 12L));
        verify(cb, never()).disjunction();
    }
}
