package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.entity.VisitFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface VisitFactRepository extends JpaRepository<VisitFact, UUID> {

    @Modifying
    @Query("UPDATE VisitFact v SET v.doctorName = :name WHERE v.doctorId = :userId")
    int renameDoctor(@Param("userId") UUID userId, @Param("name") String name);

    @Modifying
    @Query("UPDATE VisitFact v SET v.ownerName = :name WHERE v.ownerId = :userId")
    int renameOwner(@Param("userId") UUID userId, @Param("name") String name);

    @Modifying
    @Query("UPDATE VisitFact v SET v.petName = :petName, v.ownerId = :ownerId, v.ownerName = :ownerName WHERE v.petId = :petId")
    int updatePet(@Param("petId") UUID petId, @Param("petName") String petName, @Param("ownerId") UUID ownerId, @Param("ownerName") String ownerName);
}
