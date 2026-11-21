package com.vetclinic.appointment.client;

import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.common.dto.ApiResponse;
import java.util.UUID;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * HỢP ĐỒNG mà clinical-service phải cung cấp (hồ sơ thú cưng thuộc clinical-service):
 * <pre>GET /internal/clinical/pets/{id}  ->  ApiResponse&lt;{ id, ownerId, name }&gt;, 404 + PET_NOT_FOUND nếu không có</pre>
 * Chỉ được gọi khi vetclinic.booking.verify-pet = true.
 */
@FeignClient(name = "clinical-service", path = "/internal/clinical")
public interface PetClient {

    @GetMapping("/pets/{id}")
    ApiResponse<PetSummaryResponse> getPet(@PathVariable("id") Long id);

    /** Tra cứu hàng loạt theo ?ids=1,2,3 để làm giàu danh sách lịch hẹn. */
    @GetMapping("/pets")
    ApiResponse<List<PetSummaryResponse>> getPets(@RequestParam("ids") List<Long> ids);

    /**
     * Thú cưng của một chủ nuôi: {@code GET /internal/clinical/pets?ownerId=}. appointments không còn cột owner_id nên
     * "lịch của tôi" / lọc theo chủ nuôi được thực hiện bằng {@code pet_id IN (...)} (CLAUDE.md §10 #3).
     */
    @GetMapping("/pets")
    ApiResponse<List<PetSummaryResponse>> getPetsByOwner(@RequestParam("ownerId") UUID ownerId);
}
