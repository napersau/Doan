package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FR-66: nhập kho qua {@code POST /pharmacy/inventory/import} (thuốc nằm trong body). */
public record InventoryImportRequest(
        @NotNull Long medicineId,
        @NotNull @Min(1) @Max(1_000_000) Integer quantity,
        @Size(max = 255) String notes
) {
}
