package com.vetclinic.clinical.client;

import com.vetclinic.clinical.dto.response.PrescriptionSummary;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** Endpoint nội bộ của billing-pharmacy-service (không cần header người dùng, Gateway không định tuyến ra ngoài). */
@FeignClient(name = "billing-pharmacy-service", path = "/internal/billing")
public interface BillingClient {

    /** Đơn thuốc (chưa hủy) của nhiều đợt khám, để ghép vào bệnh án trọn đời. */
    @GetMapping("/prescriptions")
    ApiResponse<List<PrescriptionSummary>> getPrescriptions(@RequestParam("visitIds") List<Long> visitIds);
}
