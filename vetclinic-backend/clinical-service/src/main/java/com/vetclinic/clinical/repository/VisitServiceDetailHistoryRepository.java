package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.VisitServiceDetailHistory;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VisitServiceDetailHistoryRepository extends JpaRepository<VisitServiceDetailHistory, Long> {
    List<VisitServiceDetailHistory> findByVisitServiceDetailIdInOrderByIdAsc(Collection<Long> detailIds);
}
