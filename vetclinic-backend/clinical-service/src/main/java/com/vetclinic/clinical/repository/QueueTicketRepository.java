package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.entity.QueueTicket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Hàng đợi đọc từ PHIẾU XẾP HÀNG (SM-07), không đọc từ pet_visits. Thứ tự: priority DESC, issued_at, id (idx_qt_queue). */
public interface QueueTicketRepository extends JpaRepository<QueueTicket, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from QueueTicket t where t.id = :id")
    Optional<QueueTicket> findByIdForUpdate(@Param("id") Long id);

    /** Hàng đợi một Bác sĩ (phiếu DOCTOR đang chờ); doctorId = null là danh sách chờ phân công. */
    @Query("select t from QueueTicket t where t.queueType = com.vetclinic.clinical.constant.QueueType.DOCTOR "
            + "and t.status = com.vetclinic.clinical.constant.TicketStatus.WAITING "
            + "and ((:doctorId is null and t.doctorId is null) or t.doctorId = :doctorId) "
            + "order by t.priority desc, t.issuedAt asc, t.id asc")
    List<QueueTicket> findDoctorQueue(@Param("doctorId") UUID doctorId);

    /** Phiếu DOCTOR đang chờ của nhiều Bác sĩ (bảng điều phối); nhóm theo doctorId ở tầng ứng dụng, giữ thứ tự. */
    @Query("select t from QueueTicket t where t.queueType = com.vetclinic.clinical.constant.QueueType.DOCTOR "
            + "and t.status = com.vetclinic.clinical.constant.TicketStatus.WAITING and t.doctorId in :doctorIds "
            + "order by t.priority desc, t.issuedAt asc, t.id asc")
    List<QueueTicket> findDoctorQueues(@Param("doctorIds") Collection<UUID> doctorIds);

    /** Hàng đợi phòng Xét nghiệm / CĐHA: phiếu ở các trạng thái cho trước (chờ gọi, đang làm). */
    @Query("select t from QueueTicket t where t.queueType = :type and t.status in :statuses "
            + "order by t.priority desc, t.issuedAt asc, t.id asc")
    List<QueueTicket> findRoomQueue(@Param("type") QueueType type, @Param("statuses") Collection<TicketStatus> statuses);

    /** Số phiếu đang chờ của từng Bác sĩ: các dòng [doctorId, count]. */
    @Query("select t.doctorId, count(t) from QueueTicket t where t.queueType = com.vetclinic.clinical.constant.QueueType.DOCTOR "
            + "and t.status = com.vetclinic.clinical.constant.TicketStatus.WAITING and t.doctorId is not null group by t.doctorId")
    List<Object[]> countWaitingByDoctor();

    @Query("select max(t.ticketNo) from QueueTicket t where t.queueType = :type and t.serviceDate = :date")
    Integer findMaxTicketNo(@Param("type") QueueType type, @Param("date") LocalDate date);

    Optional<QueueTicket> findFirstByVisitIdAndQueueTypeAndStatusIn(UUID visitId, QueueType type, Collection<TicketStatus> statuses);

    Optional<QueueTicket> findFirstByVisitIdAndQueueTypeAndStatus(UUID visitId, QueueType type, TicketStatus status);

    List<QueueTicket> findByVisitIdAndStatusIn(UUID visitId, Collection<TicketStatus> statuses);

    /** Phiếu DOCTOR còn hiệu lực của nhiều ca (gắn số phiếu vào danh sách ca). */
    List<QueueTicket> findByVisitIdInAndQueueTypeAndStatusIn(Collection<UUID> visitIds, QueueType type,
                                                             Collection<TicketStatus> statuses);
}
