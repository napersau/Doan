package com.vetclinic.billing.repository;

import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.entity.MedicineInvoice;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MedicineInvoiceRepository extends JpaRepository<MedicineInvoice, Long> {

    /** Khóa dòng cho mọi thao tác ghi lên hóa đơn (sửa / hủy / thu tiền): hai thu ngân bấm đồng thời thì người sau chờ (EX-16). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MedicineInvoice i where i.id = :id")
    Optional<MedicineInvoice> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"prescription", "items", "items.prescriptionItem", "items.prescriptionItem.medicine"})
    Optional<MedicineInvoice> findWithItemsById(Long id);

    @EntityGraph(attributePaths = {"prescription", "items", "items.prescriptionItem", "items.prescriptionItem.medicine"})
    Optional<MedicineInvoice> findWithItemsByPrescriptionId(Long prescriptionId);

    Optional<MedicineInvoice> findByPrescriptionId(Long prescriptionId);

    /** Có hóa đơn thuốc "chưa hủy" (PENDING/PAID) thì đơn thuốc bị khóa chỉnh sửa (PHARM_1411). */
    boolean existsByPrescriptionIdAndPaymentStatusNot(Long prescriptionId, PaymentStatus status);

    /** Hóa đơn PAID có ngày thu tiền trong [start, end): nguồn của báo cáo doanh thu thuốc (FR-69). */
    @Query("select new com.vetclinic.billing.repository.MedicineRevenueRow(mi.paidAt, mi.discountAmount, "
            + "sum(mii.quantity * mii.unitPrice), sum(mii.quantity * mii.unitPrice * mii.vatRate), "
            + "sum(mii.quantity * mii.unitPrice * (1 + mii.vatRate))) "
            + "from MedicineInvoice mi join mi.items mii "
            + "where mi.paymentStatus = com.vetclinic.billing.constant.PaymentStatus.PAID "
            + "and mi.paidAt >= :start and mi.paidAt < :end "
            + "group by mi.id, mi.paidAt, mi.discountAmount")
    List<MedicineRevenueRow> findPaidRevenueRows(@Param("start") Instant start, @Param("end") Instant end);

    /** Số hóa đơn theo trạng thái cho các hóa đơn LẬP trong [start, end). */
    @Query("select mi.paymentStatus, count(mi) from MedicineInvoice mi "
            + "where mi.createdAt >= :start and mi.createdAt < :end group by mi.paymentStatus")
    List<Object[]> countByStatus(@Param("start") Instant start, @Param("end") Instant end);
}
