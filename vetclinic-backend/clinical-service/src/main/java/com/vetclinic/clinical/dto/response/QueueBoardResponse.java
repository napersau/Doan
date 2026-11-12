package com.vetclinic.clinical.dto.response;

import java.util.List;

/**
 * Bảng điều phối của Lễ tân (UC-20, FR-21): danh sách chờ phân công (ca WAITING chưa có Bác sĩ) và một hàng đợi cho mỗi Bác sĩ
 * ACTIVE. suggestedDoctorId = Bác sĩ có ít người chờ nhất (chỉ gợi ý, Lễ tân quyết định; được xếp cho BẤT KỲ Bác sĩ nào).
 */
public record QueueBoardResponse(
        List<VisitResponse> unassigned,
        List<DoctorQueueResponse> doctors,
        Long suggestedDoctorId
) {
}
