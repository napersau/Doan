package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.entity.RevenueFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RevenueFactRepository extends JpaRepository<RevenueFact, Long> {

    boolean existsBySourceInvoiceIdAndItemIdAndEntryType(UUID sourceInvoiceId, UUID itemId, String entryType);

    List<RevenueFact> findBySourceInvoiceIdAndRevenueTypeAndEntryType(UUID sourceInvoiceId, String revenueType, String entryType);

    @Modifying
    @Query("UPDATE RevenueFact r SET r.doctorName = :name WHERE r.doctorId = :userId")
    int renameDoctor(@Param("userId") UUID userId, @Param("name") String name);
}
