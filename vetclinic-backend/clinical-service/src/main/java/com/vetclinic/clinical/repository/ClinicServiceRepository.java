package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ClinicService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClinicServiceRepository extends JpaRepository<ClinicService, Long> {
    boolean existsByCode(String code);

    Optional<ClinicService> findByCode(String code);

    List<ClinicService> findByActiveTrueOrderByCategoryAscNameAsc();

    List<ClinicService> findAllByOrderByCategoryAscNameAsc();

    List<ClinicService> findByIdInAndActiveTrue(Collection<Long> ids);
}
