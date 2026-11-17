package com.vetclinic.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Một dịch vụ Xét nghiệm / CĐHA thuộc một phiếu phòng (queue_ticket_items): các dịch vụ cùng đợt khám, cùng phòng gom vào MỘT
 * phiếu ("một ca": gọi một lần, bắt đầu cả phiếu). Mỗi dịch vụ chỉ thuộc một phiếu ({@code visit_service_detail_id UNIQUE}).
 */
@Entity
@Table(name = "queue_ticket_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class QueueTicketItem {

    @EmbeddedId
    private Key id;

    public static QueueTicketItem of(Long ticketId, Long visitServiceDetailId) {
        return new QueueTicketItem(new Key(ticketId, visitServiceDetailId));
    }

    public Long getTicketId() {
        return id.getTicketId();
    }

    public Long getVisitServiceDetailId() {
        return id.getVisitServiceDetailId();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "ticket_id", nullable = false)
        private Long ticketId;

        @Column(name = "visit_service_detail_id", nullable = false)
        private Long visitServiceDetailId;
    }
}
