package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;

/** Dòng hóa đơn thuốc; unitPrice / vatRate là snapshot lúc lập hóa đơn. */
public record MedicineInvoiceItemResponse(
        Long id,
        Long prescriptionItemId,
        Long medicineId,
        String medicineCode,
        String medicineName,
        String unit,
        int quantity,
        int prescribedQuantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount
) {
}
