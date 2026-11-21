package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.QueueTicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QueueTicketHistoryRepository extends JpaRepository<QueueTicketHistory, Long> {

    boolean existsByTicketId(UUID ticketId);

    /** Số phiếu đã có trong lịch sử của ngày / loại hàng đợi đó (UNIQUE queue_type + service_date + ticket_no). */
    boolean existsByQueueTypeAndServiceDateAndTicketNo(String queueType, java.time.LocalDate serviceDate, int ticketNo);

    List<QueueTicketHistory> findByVisitIdOrderByIdAsc(UUID visitId);
}
