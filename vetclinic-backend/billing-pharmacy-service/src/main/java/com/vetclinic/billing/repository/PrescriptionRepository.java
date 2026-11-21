package com.vetclinic.billing.repository;

import java.util.UUID;
import com.vetclinic.billing.entity.Prescription;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /** Khóa dòng khi sửa đơn / lập hóa đơn thuốc: hai thao tác đồng thời trên cùng một đơn được xếp hàng. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Prescription p where p.id = :id")
    Optional<Prescription> findByIdForUpdate(@Param("id") Long id);

    // open-in-view đã tắt nên luôn nạp kèm dòng thuốc và thuốc
    @EntityGraph(attributePaths = {"items", "items.medicine"})
    Optional<Prescription> findWithItemsById(Long id);

    @EntityGraph(attributePaths = {"items", "items.medicine"})
    Optional<Prescription> findWithItemsByVisitId(UUID visitId);

    /** Cho tra cứu bệnh án: đơn của nhiều đợt khám cùng lúc. */
    @EntityGraph(attributePaths = {"items", "items.medicine"})
    List<Prescription> findWithItemsByVisitIdIn(Collection<UUID> visitIds);

    Page<Prescription> findByVisitId(UUID visitId, Pageable pageable);
}
