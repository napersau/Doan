package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PetVisitStatusHistoryRepository extends JpaRepository<PetVisitStatusHistory, Long> {
    java.util.List<PetVisitStatusHistory> findByVisitIdOrderByIdAsc(Long visitId);

    /** Đã từng vào trạng thái {@code toStatus} (vd. đã qua WAITING_RESULTS = đang ở Giai đoạn 2). */
    boolean existsByVisitIdAndToStatus(Long visitId, com.vetclinic.clinical.constant.VisitStatus toStatus);

    /** Tra cứu theo lô để suy ra các mốc thời gian của cả danh sách đợt khám (VisitTimeline), không N+1. */
    java.util.List<PetVisitStatusHistory> findByVisitIdInOrderByIdAsc(java.util.Collection<Long> visitIds);
}
