package com.vetclinic.billing.controller;

import java.util.UUID;
import com.vetclinic.billing.dto.request.PrescriptionRequest;
import com.vetclinic.billing.dto.response.PrescriptionResponse;
import com.vetclinic.billing.dto.response.PrescriptionSummaryResponse;
import com.vetclinic.billing.service.PrescriptionService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Đơn thuốc điện tử: chỉ định chuyên môn, không giá, không trạng thái (v3.0). Bán thuốc ở MedicineInvoiceController. */
@RestController
@RequestMapping("/api/v1/pharmacy/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    /** UC-31: kê đơn khi ca đang IN_PROGRESS. Kê vượt tồn kho vẫn lưu, kèm {@code stockWarnings} (EX-13). */
    @PostMapping
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> create(@Valid @RequestBody PrescriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Đã lưu đơn thuốc", prescriptionService.create(request)));
    }

    /** Sửa đơn (thay nội dung các dòng thuốc) khi ca còn đang khám và đơn chưa có hóa đơn thuốc chưa hủy. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> update(@PathVariable Long id,
                                                                    @Valid @RequestBody PrescriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật đơn thuốc", prescriptionService.update(id, request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PrescriptionSummaryResponse>>> search(
            @RequestParam(required = false) UUID visitId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.search(visitId, page, size)));
    }

    /** UC-39: chủ nuôi chỉ xem được đơn của thú cưng mình. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.getById(id)));
    }

    @GetMapping("/by-visit/{visitId}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> getByVisit(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.getByVisit(visitId)));
    }

    /** In đơn cho khách mua ngoài (BR-32): không tạo hóa đơn, không đổi gì trong hệ thống. */
    @GetMapping("/{id}/print")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> print(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.print(id)));
    }
}
