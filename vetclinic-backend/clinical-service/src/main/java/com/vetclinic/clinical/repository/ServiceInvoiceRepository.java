package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ServiceInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceInvoiceRepository extends JpaRepository<ServiceInvoice, Long> {

    /** Mỗi dòng chỉ định có đúng một hóa đơn (UNIQUE visit_service_detail_id). */
    Optional<ServiceInvoice> findByVisitServiceDetailId(Long visitServiceDetailId);

    /** Tra cứu theo lô để dựng response của cả một đợt khám bằng MỘT câu truy vấn (không N+1). */
    List<ServiceInvoice> findByVisitServiceDetailIdIn(Collection<Long> visitServiceDetailIds);

    /** FR-69: hóa đơn dịch vụ đã thu trong [start, end) (theo paid_at), kèm nhóm dịch vụ. */
    @Query("select new com.vetclinic.clinical.repository.InvoiceAmountRow(s.category.code, s.category.displayOrder, d.visitId, d.quantity, i.unitPrice, i.vatRate, i.discountAmount) "
            + "from ServiceInvoice i, VisitServiceDetail d join d.service s "
            + "where d.id = i.visitServiceDetailId and i.paymentStatus = com.vetclinic.clinical.constant.PaymentStatus.PAID "
            + "and i.paidAt >= :start and i.paidAt < :end")
    List<InvoiceAmountRow> findPaidBetween(@Param("start") Instant start, @Param("end") Instant end);

    /** Công nợ: hóa đơn PENDING của các đợt khám đã COMPLETED (khách bỏ về, EX-11). */
    @Query("select new com.vetclinic.clinical.repository.InvoiceAmountRow(s.category.code, s.category.displayOrder, d.visitId, d.quantity, i.unitPrice, i.vatRate, i.discountAmount) "
            + "from ServiceInvoice i, VisitServiceDetail d join d.service s, PetVisit v "
            + "where d.id = i.visitServiceDetailId and v.id = d.visitId "
            + "and v.status = com.vetclinic.clinical.constant.VisitStatus.COMPLETED "
            + "and i.paymentStatus = com.vetclinic.clinical.constant.PaymentStatus.PENDING")
    List<InvoiceAmountRow> findDebt();
}
