package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface PetVisitRepository extends JpaRepository<PetVisit, Long>, JpaSpecificationExecutor<PetVisit> {

    /**
     * Khóa dòng (SELECT ... FOR UPDATE) cho mọi thao tác ghi lên đợt khám: serialize các thao tác cùng lúc
     * (hai KTV cùng hoàn tất, Bác sĩ bấm hai lần...) và đảm bảo kiểm tra trạng thái nguồn không bị chen ngang.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PetVisit v where v.id = :id")
    Optional<PetVisit> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    Optional<PetVisit> findWithPetById(Long id);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    List<PetVisit> findWithPetByIdIn(Collection<Long> ids);

    /**
     * Thứ tự MỌI hàng đợi (v3.0): cấp cứu chưa bị đẩy lùi lên đầu, còn lại theo queue_order tăng dần (số nhỏ gọi trước), cùng
     * giá trị thì theo id. Không còn tầng "có hẹn đúng giờ" và không còn cột scheduled_at. Chỉ mục idx_visits_queue
     * (doctor_id, status, is_emergency, queue_order).
     */
    @EntityGraph(attributePaths = {"pet", "pet.species"})
    @Query("select v from PetVisit v where v.status = :status "
            + "order by case when (v.emergency = true and v.skipCount = 0) then 0 else 1 end asc, v.queueOrder asc, v.id asc")
    List<PetVisit> findQueueByStatus(@Param("status") VisitStatus status);

    /** Các ca của một Bác sĩ (hàng đợi riêng + ca đang khám), cùng thứ tự hàng đợi như findQueueByStatus. */
    @EntityGraph(attributePaths = {"pet", "pet.species"})
    @Query("select v from PetVisit v where v.doctorId = :doctorId and v.status in :statuses "
            + "order by case when (v.emergency = true and v.skipCount = 0) then 0 else 1 end asc, v.queueOrder asc, v.id asc")
    List<PetVisit> findDoctorVisits(@Param("doctorId") Long doctorId, @Param("statuses") Collection<VisitStatus> statuses);

    /**
     * Cân nặng gần nhất của mỗi thú cưng = weight_at_visit của đợt khám gần nhất có cân (pets không còn cột weight).
     * Trả các dòng [petId, weight].
     */
    @Query("select v.pet.id, v.weightAtVisit from PetVisit v where v.weightAtVisit is not null and v.id in "
            + "(select max(v2.id) from PetVisit v2 where v2.pet.id in :petIds and v2.weightAtVisit is not null group by v2.pet.id)")
    List<Object[]> findLatestWeights(@Param("petIds") Collection<Long> petIds);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    List<PetVisit> findByPetIdOrderByIdDesc(Long petId);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    org.springframework.data.domain.Page<PetVisit> findByPetId(Long petId, org.springframework.data.domain.Pageable pageable);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    org.springframework.data.domain.Page<PetVisit> findByPetIdAndStatus(Long petId, VisitStatus status,
                                                                        org.springframework.data.domain.Pageable pageable);

    /** Báo cáo vận hành: số ca theo trạng thái, ngày check-in trong [start, end). */
    @Query("select v.status, count(v) from PetVisit v where v.checkedInAt >= :start and v.checkedInAt < :end group by v.status")
    List<Object[]> countByStatusBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    @Query("select count(v) from PetVisit v where v.emergency = true and v.checkedInAt >= :start and v.checkedInAt < :end")
    long countEmergencyBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    @Query("select v.doctorId, v.status, count(v) from PetVisit v where v.doctorId is not null "
            + "and v.checkedInAt >= :start and v.checkedInAt < :end group by v.doctorId, v.status")
    List<Object[]> countByDoctorAndStatusBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    boolean existsByPetIdAndStatusIn(Long petId, Collection<VisitStatus> statuses);

    boolean existsByAppointmentId(Long appointmentId);

    boolean existsByDoctorIdAndStatus(Long doctorId, VisitStatus status);

    // ---- Trạng thái Bác sĩ (tính động): chỉ cần biết bác sĩ nào đang ở tình trạng nào
    @Query("select distinct v.doctorId from PetVisit v where v.doctorId is not null and v.status in :statuses")
    Set<Long> findDoctorIdsWithStatusIn(@Param("statuses") Collection<VisitStatus> statuses);
}
