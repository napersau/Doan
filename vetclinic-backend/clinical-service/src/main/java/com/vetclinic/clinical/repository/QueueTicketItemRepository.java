package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.QueueTicketItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QueueTicketItemRepository extends JpaRepository<QueueTicketItem, QueueTicketItem.Key> {

    @Query("select i from QueueTicketItem i where i.id.ticketId in :ticketIds")
    List<QueueTicketItem> findByTicketIdIn(@Param("ticketIds") Collection<Long> ticketIds);

    @Query("select i from QueueTicketItem i where i.id.visitServiceDetailId = :detailId")
    Optional<QueueTicketItem> findByVisitServiceDetailId(@Param("detailId") Long detailId);

    @Query("select i.id.visitServiceDetailId from QueueTicketItem i where i.id.visitServiceDetailId in :detailIds")
    List<Long> findTicketedDetailIds(@Param("detailIds") Collection<Long> detailIds);
}
