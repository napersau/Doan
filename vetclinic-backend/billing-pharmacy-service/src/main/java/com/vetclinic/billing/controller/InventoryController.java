package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.request.InventoryAdjustRequest;
import com.vetclinic.billing.dto.request.InventoryImportRequest;
import com.vetclinic.billing.dto.response.MedicineResponse;
import com.vetclinic.billing.service.MedicineService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** UC-37 / FR-66: nhập kho và cân chỉnh kho (chỉ Admin). Mỗi thao tác ghi nhật ký có dấu và đổi tồn kho trong cùng giao dịch. */
@RestController
@RequestMapping("/api/v1/pharmacy/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final MedicineService medicineService;

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> importStock(@Valid @RequestBody InventoryImportRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã nhập kho", medicineService.importStock(request)));
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MedicineResponse>> adjustStock(@Valid @RequestBody InventoryAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cân chỉnh kho", medicineService.adjustStock(request)));
    }
}
