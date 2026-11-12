package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.MedicineInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicineInvoiceItemRepository extends JpaRepository<MedicineInvoiceItem, Long> {

    /** Dòng thuốc đã được hóa đơn (kể cả hóa đơn đã hủy) tham chiếu thì không xóa khỏi đơn được (khóa ngoại fk_mii_pitem). */
    boolean existsByPrescriptionItemId(Long prescriptionItemId);
}
