package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.response.InventoryTransactionResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceItemResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.dto.response.PrescriptionItemResponse;
import com.vetclinic.billing.dto.response.PrescriptionResponse;
import com.vetclinic.billing.dto.response.PrescriptionSummaryResponse;
import com.vetclinic.billing.dto.response.VisitSummaryResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.MedicineInvoiceItem;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.entity.PrescriptionItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entity -> DTO. Việc dựng từ entity (cần transaction để đọc collection lười) tách khỏi việc làm giàu bằng dữ liệu của
 * clinical-service (withVisit, gọi Feign ngoài transaction): withVisit nhận visit == null thì trả nguyên bản.
 */
@Component
public class BillingAssembler {

    // ------------------------------------------------------------------ đơn thuốc

    public PrescriptionResponse toResponse(Prescription prescription, MedicineInvoice invoice, List<String> stockWarnings) {
        List<PrescriptionItemResponse> items = prescription.getItems().stream().map(this::toResponse).toList();
        return new PrescriptionResponse(prescription.getId(), prescription.getVisitId(), null, null,
                prescription.getDoctorNotes(), prescription.getCreatedAt(),
                invoice == null ? null : invoice.getId(), invoice == null ? null : invoice.getPaymentStatus(),
                items, stockWarnings);
    }

    public PrescriptionResponse withVisit(PrescriptionResponse r, VisitSummaryResponse visit) {
        if (visit == null) {
            return r;
        }
        return new PrescriptionResponse(r.id(), r.visitId(), visit.visitCode(), visit.petName(), r.doctorNotes(),
                r.createdAt(), r.medicineInvoiceId(), r.medicineInvoiceStatus(), r.items(), r.stockWarnings());
    }

    public PrescriptionSummaryResponse toSummary(Prescription prescription) {
        return new PrescriptionSummaryResponse(prescription.getId(), prescription.getVisitId(), null, null,
                prescription.getItems().size(), prescription.getCreatedAt());
    }

    public PrescriptionSummaryResponse withVisit(PrescriptionSummaryResponse r, VisitSummaryResponse visit) {
        if (visit == null) {
            return r;
        }
        return new PrescriptionSummaryResponse(r.id(), r.visitId(), visit.visitCode(), visit.petName(), r.itemCount(),
                r.createdAt());
    }

    private PrescriptionItemResponse toResponse(PrescriptionItem item) {
        Medicine medicine = item.getMedicine();
        return new PrescriptionItemResponse(item.getId(), medicine.getId(), medicine.getCode(), medicine.getName(),
                medicine.getActiveIngredient(), medicine.getUnit(), item.getQuantity(), item.getDosage(),
                item.getFrequency(), item.getDurationDays(), item.getInstructions(), medicine.getStockQuantity(),
                medicine.getMinStockAlert());
    }

    // ------------------------------------------------------------------ hóa đơn thuốc

    /** Tiền được tính từ các dòng (không lưu): xem {@link MedicineInvoiceAmounts}. */
    public MedicineInvoiceResponse toResponse(MedicineInvoice invoice) {
        List<MedicineInvoiceItemResponse> items = invoice.getItems().stream().map(this::toResponse).toList();
        return new MedicineInvoiceResponse(invoice.getId(), invoice.getInvoiceCode(), invoice.getPrescription().getId(),
                invoice.getPrescription().getVisitId(), null, null, null,
                MedicineInvoiceAmounts.beforeVat(invoice.getItems()), MedicineInvoiceAmounts.vat(invoice.getItems()),
                invoice.getDiscountAmount(), MedicineInvoiceAmounts.payable(invoice.getItems(), invoice.getDiscountAmount()),
                invoice.getPaymentStatus(), invoice.getCashierId(), invoice.getPaidAt(), invoice.getCreatedAt(), items);
    }

    public MedicineInvoiceResponse withVisit(MedicineInvoiceResponse r, VisitSummaryResponse visit) {
        if (visit == null) {
            return r;
        }
        return new MedicineInvoiceResponse(r.id(), r.invoiceCode(), r.prescriptionId(), r.visitId(), visit.visitCode(),
                visit.petName(), visit.ownerId(), r.amountBeforeVat(), r.vatAmount(), r.discountAmount(),
                r.payableAmount(), r.paymentStatus(), r.cashierId(), r.paidAt(), r.createdAt(), r.items());
    }

    private MedicineInvoiceItemResponse toResponse(MedicineInvoiceItem item) {
        PrescriptionItem prescribed = item.getPrescriptionItem();
        Medicine medicine = prescribed.getMedicine();
        BigDecimal base = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new MedicineInvoiceItemResponse(item.getId(), prescribed.getId(), medicine.getId(), medicine.getCode(),
                medicine.getName(), medicine.getUnit(), item.getQuantity(), prescribed.getQuantity(), item.getUnitPrice(),
                item.getVatRate(), MedicineInvoiceAmounts.money(base),
                MedicineInvoiceAmounts.money(base.multiply(item.getVatRate())), medicine.getStockQuantity(),
                medicine.getMinStockAlert());
    }

    // ------------------------------------------------------------------ kho

    public InventoryTransactionResponse toResponse(InventoryTransaction t) {
        return new InventoryTransactionResponse(t.getId(), t.getMedicineId(), t.getTransactionType(), t.getQuantity(),
                t.getMedicineInvoiceId(), t.getNotes(), t.getCreatedBy(), t.getCreatedAt());
    }
}
