package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hóa đơn thuốc (UC-36). Lập hóa đơn nằm dưới {@code /pharmacy/prescriptions/{id}/medicine-invoices} (Quy chuẩn §3.2); các thao tác trên
 * hóa đơn đã có nằm dưới {@code /billing/medicine-invoices/{id}}. Chuyển trạng thái dùng POST /{id}/action; sửa dòng thuốc là PUT (sửa dữ liệu).
 */
@RestController
@RequiredArgsConstructor
public class MedicineInvoiceController {

    private final MedicineInvoiceService invoiceService;

    @PostMapping("/api/v1/pharmacy/prescriptions/{prescriptionId}/medicine-invoices")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> create(@PathVariable Long prescriptionId,
                                                                       @Valid @RequestBody MedicineInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Đã lập hóa đơn thuốc", invoiceService.create(prescriptionId, request)));
    }

    @GetMapping("/api/v1/billing/medicine-invoices/{id}")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getById(id)));
    }

    /** Sửa các dòng thuốc / giảm giá khi hóa đơn còn PENDING (quantity = 0 là bỏ dòng). */
    @PutMapping("/api/v1/billing/medicine-invoices/{id}/items")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> updateItems(@PathVariable Long id,
                                                                            @Valid @RequestBody MedicineInvoiceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật hóa đơn thuốc", invoiceService.updateItems(id, request)));
    }

    /** Khách đổi ý: hủy hóa đơn PENDING (kho không đổi). Hủy là kết thúc, đơn không lập lại hóa đơn. */
    @PostMapping("/api/v1/billing/medicine-invoices/{id}/cancel")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy hóa đơn thuốc", invoiceService.cancel(id)));
    }

    /** Thu tiền + trừ kho + ghi nhật ký kho trong một giao dịch; thiếu kho thì rollback toàn bộ (EX-15). */
    @PostMapping("/api/v1/billing/medicine-invoices/{id}/confirm-payment")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> confirmPayment(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã thu tiền và trừ kho", invoiceService.confirmPayment(id)));
    }
}
