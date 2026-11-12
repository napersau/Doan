package com.vetclinic.billing.client;

import com.vetclinic.billing.dto.response.VisitSummaryResponse;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Endpoint nội bộ của clinical-service (Kiến trúc v3.0 §5.1), CHỈ ĐỌC; không cần header người dùng, Gateway không định tuyến ra ngoài.
 */
@FeignClient(name = "clinical-service", path = "/internal/pet-visits")
public interface ClinicalClient {

    /** Trạng thái + bác sĩ phụ trách + chủ nuôi của đợt khám. */
    @GetMapping("/{id}/summary")
    ApiResponse<VisitSummaryResponse> getVisit(@PathVariable("id") Long id);

    /** Như trên theo lô (làm giàu danh sách); id không tồn tại bị bỏ qua. */
    @GetMapping("/summaries")
    ApiResponse<List<VisitSummaryResponse>> getVisits(@RequestParam("ids") List<Long> ids);
}
