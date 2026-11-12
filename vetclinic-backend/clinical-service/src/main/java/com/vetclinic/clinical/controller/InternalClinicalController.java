package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint cho các service khác gọi qua OpenFeign. Nằm dưới /internal/**: không cần header người dùng và Gateway
 * không định tuyến ra ngoài, nên chỉ truy cập được trong mạng Docker.
 */
@RestController
@RequestMapping("/internal/clinical")
@RequiredArgsConstructor
public class InternalClinicalController {

    private final PetService petService;
    private final VisitService visitService;

    /** appointment-service kiểm tra thú cưng tồn tại và thuộc đúng chủ nuôi khi đặt lịch. */
    @GetMapping("/pets/{id}")
    public ResponseEntity<ApiResponse<PetSummaryResponse>> pet(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(petService.getSummary(id)));
    }

    /** appointment-service làm giàu danh sách lịch hẹn bằng tên thú cưng, theo lô. */
    @GetMapping(value = "/pets", params = "ids")
    public ResponseEntity<ApiResponse<List<PetSummaryResponse>>> pets(@RequestParam("ids") List<Long> ids) {
        return ResponseEntity.ok(ApiResponse.success(petService.getSummaries(ids)));
    }

    /**
     * appointment-service lọc "lịch hẹn của tôi" / theo chủ nuôi: appointments không còn cột owner_id (lược đồ v3.0) nên
     * hỏi danh sách thú cưng của chủ rồi lọc {@code pet_id IN (...)}.
     */
    @GetMapping(value = "/pets", params = "ownerId")
    public ResponseEntity<ApiResponse<List<PetSummaryResponse>>> petsOfOwner(@RequestParam("ownerId") Long ownerId) {
        return ResponseEntity.ok(ApiResponse.success(petService.getSummariesByOwner(ownerId)));
    }

    /**
     * billing-pharmacy-service kiểm tra trạng thái, bác sĩ phụ trách và chủ nuôi của đợt khám (kê đơn, xem đơn). Đường dẫn chuẩn v3.0 là
     * {@code /internal/pet-visits/{id}/summary}; đường dẫn này giữ tạm cho billing v2 tới Phase 4 (xóa ở bước 7.1).
     */
    @GetMapping("/visits/{id}")
    public ResponseEntity<ApiResponse<VisitSummaryResponse>> visit(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(visitService.summary(id)));
    }

    /** Làm giàu danh sách hóa đơn / đơn thuốc bằng mã ca khám và tên thú cưng theo lô. */
    @GetMapping("/visits")
    public ResponseEntity<ApiResponse<List<VisitSummaryResponse>>> visits(@RequestParam("ids") List<Long> ids) {
        return ResponseEntity.ok(ApiResponse.success(visitService.summaries(ids)));
    }
}
