package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Đợt khám ngoại trú hai pha. Chuyển trạng thái dùng POST theo quy chuẩn Action Endpoint. */
@RestController
@RequestMapping("/api/v1/clinical/pet-visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final com.vetclinic.clinical.service.DoctorQueueService doctorQueueService;
    /** Số phòng của Bác sĩ lấy từ auth-service SAU commit (pet_visits không còn cột assigned_room). */
    private final com.vetclinic.clinical.service.VisitEnricher enricher;

    /** UC-19: tiếp nhận khách có hẹn (appointmentId) hoặc vãng lai (petId). */
    @PostMapping("/check-in")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> checkIn(@Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Tiếp nhận thành công", enricher.enrich(visitService.checkIn(request))));
    }

    /**
     * UC-20, FR-21: bảng điều phối: danh sách chờ phân công + hàng đợi riêng từng Bác sĩ (kèm số người chờ, trạng thái, phòng) và
     * Bác sĩ được gợi ý (ít người chờ nhất). Thứ tự: (cấp cứu chưa bị lùi) trước, rồi queue_order.
     */
    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<com.vetclinic.clinical.dto.response.QueueBoardResponse>> queue() {
        return ResponseEntity.ok(ApiResponse.success(doctorQueueService.board()));
    }

    /** Hàng đợi của chính Bác sĩ đang đăng nhập (ca WAITING và RESULTS_READY, ca có kết quả đứng đầu). */
    @GetMapping("/my-queue")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> myQueue() {
        return ResponseEntity.ok(ApiResponse.success(enricher.enrichAll(doctorQueueService.myQueue())));
    }

    /** Danh sách đợt khám có lọc, phân trang, mới nhất trước (bảng điều phối của lễ tân, lịch sử ca của bác sĩ). */
    @GetMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<com.vetclinic.common.dto.PageResponse<VisitResponse>>> search(
            @RequestParam(required = false) com.vetclinic.clinical.constant.VisitStatus status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long petId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = visitService.search(status, date, doctorId, petId, keyword, page, size);
        result.setItems(enricher.enrichAll(result.getItems()));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN','ADMIN')")
    public ResponseEntity<ApiResponse<VisitResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(enricher.enrich(visitService.getById(id))));
    }

    /** Audit trail vòng đời đợt khám. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<VisitHistoryResponse>>> history(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(visitService.getHistory(id)));
    }

    /** UC-21, FR-24: xếp ca vào hàng đợi của Bác sĩ bất kỳ (không cần rảnh); gọi lại với Bác sĩ khác = chuyển hàng đợi. */
    @PostMapping("/{id}/assign-doctor")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> assign(@PathVariable Long id,
                                                             @Valid @RequestBody AssignDoctorRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Phân công bác sĩ thành công", enricher.enrich(visitService.assignDoctor(id, request))));
    }

    /** UC-21 Luồng 5c: gỡ phân công, ca về danh sách chờ phân công. */
    @PostMapping("/{id}/unassign-doctor")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> unassignDoctor(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã gỡ phân công", enricher.enrich(visitService.unassignDoctor(id))));
    }

    /** UC-22: Bác sĩ bắt đầu khám, tự đo cân nặng và thân nhiệt. */
    @PostMapping("/{id}/start-exam")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> startExam(@PathVariable Long id,
                                                                @Valid @RequestBody StartExamRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bác sĩ đã bắt đầu khám lâm sàng", enricher.enrich(visitService.startExam(id, request))));
    }

    /** UC-23: chỉ định dịch vụ. Có Lab / CĐHA thì ca sang WAITING_RESULTS và Bác sĩ được giải phóng. */
    @PostMapping("/{id}/services")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> orderServices(@PathVariable Long id,
                                                                    @Valid @RequestBody OrderServicesRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã chỉ định dịch vụ", enricher.enrich(visitService.orderServices(id, request))));
    }

    /** Mở lại đợt khám đã chốt (chưa thu tiền) để điều chỉnh chuyên môn. Chỉ Admin, bắt buộc lý do. */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<VisitResponse>> reopen(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã mở lại đợt khám", enricher.enrich(visitService.reopen(id, request))));
    }

    /** UC-33: chốt chuyên môn, khóa hồ sơ, chuyển sang thanh toán. Bắt buộc có chẩn đoán xác định. */
    @PostMapping("/{id}/close-visit")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> closeVisit(@PathVariable Long id,
                                                                 @Valid @RequestBody CloseVisitRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã chốt đợt khám, chuyển sang thanh toán", enricher.enrich(visitService.closeVisit(id, request))));
    }

    /** EX-11: khách bỏ về hẳn không đọc kết quả. */
    @PostMapping("/{id}/abandon")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> abandon(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã đóng ca, ghi nợ viện phí", enricher.enrich(visitService.abandon(id))));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> cancel(@PathVariable Long id,
                                                             @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy đợt khám", enricher.enrich(visitService.cancel(id, request))));
    }
}
