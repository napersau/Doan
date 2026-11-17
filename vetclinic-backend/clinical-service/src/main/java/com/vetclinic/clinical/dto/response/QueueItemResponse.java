package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;

import java.time.Instant;

/**
 * Một dịch vụ trong hàng đợi phòng Lab / CĐHA, kèm PHIẾU XẾP HÀNG chứa nó (17/11/2026). Danh sách đã xếp theo phiếu:
 * priority DESC, issued_at, id; các dịch vụ cùng phiếu đứng liền nhau (giao diện gom theo ticketId: gọi một lần, bắt đầu cả phiếu).
 * <ul>
 *   <li>stage (chỉ phòng Lab, SUY RA từ action mới nhất của lịch sử chỉ định, không có cột riêng): QUEUED (ORDERED),
 *       SAMPLING (START_PROCESSING: đang lấy mẫu), ANALYZING (SAMPLE_COLLECTED: đã lấy mẫu, đang phân tích).</li>
 *   <li>labStatus (chỉ hàng đợi CĐHA, EX-09 / FR-36): SAMPLING nếu cùng ca còn dịch vụ Lab đang lấy mẫu, SAMPLE_COLLECTED nếu đã
 *       lấy mẫu xong (sẵn sàng chụp); null nếu ca không có dịch vụ Lab đang thực hiện.</li>
 * </ul>
 */
public record QueueItemResponse(
        Long serviceDetailId,
        Long visitId,
        String visitCode,
        Long petId,
        String petName,
        String speciesName,
        Long serviceId,
        String serviceName,
        ServiceCategory category,
        boolean emergency,
        ServiceDetailStatus status,
        String stage,
        String labStatus,
        String notes,
        Instant orderedAt,
        Long ticketId,
        int ticketNo,
        int ticketPriority,
        com.vetclinic.clinical.constant.TicketStatus ticketStatus,
        Instant issuedAt,
        Instant lastCalledAt
) {
}
