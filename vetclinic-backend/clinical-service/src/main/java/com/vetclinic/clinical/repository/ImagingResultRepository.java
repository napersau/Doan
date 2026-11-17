package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ImagingResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImagingResultRepository extends JpaRepository<ImagingResult, Long> {
    List<ImagingResult> findByVisitServiceDetailIdIn(Collection<Long> detailIds);
}
