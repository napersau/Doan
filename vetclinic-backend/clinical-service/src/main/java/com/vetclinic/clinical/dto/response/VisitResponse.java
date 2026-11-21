package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.VisitStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * Hồ sơ một đợt khám. services / labResults / imagingResults chỉ có ở API chi tiết, null ở các API danh sách.
 * locked = true từ lúc Bác sĩ chốt chuyên môn (WAITING_PAYMENT trở đi): không được sửa chẩn đoán, dịch vụ, đơn thuốc (BR-27).
 * <p>
 * Lược đồ v3.0 bỏ các cột assigned_room, started_at, closed_at, completed_at, cancel_reason khỏi pet_visits: hình dạng
 * response giữ các trường đó nhưng được SUY RA (phòng từ Bác sĩ qua auth-service, các mốc và lý do hủy từ lịch sử trạng thái).
 * ticket: phiếu xếp hàng còn hiệu lực của ca (chỉ gắn ở các API hàng đợi; null ở chỗ khác). Thứ tự hàng đợi nằm ở queue_tickets.
 */
public record VisitResponse(
        Long id,
        String visitCode,
        UUID petId,
        String petName,
        String petCode,
        String speciesName,
        UUID ownerId,
        UUID appointmentId,
        UUID doctorId,
        UUID receptionistId,
        String assignedRoom,
        boolean emergency,
        VisitStatus status,
        boolean locked,
        TicketInfo ticket,
        BigDecimal weightAtVisit,
        BigDecimal temperature,
        String initialSymptoms,
        String clinicalNotes,
        String diagnosis,
        String treatmentPlan,
        String doctorAdvice,
        String cancelReason,
        Instant checkedInAt,
        Instant startedAt,
        Instant closedAt,
        Instant completedAt,
        List<ServiceDetailResponse> services,
        List<LabResultResponse> labResults,
        List<ImagingResultResponse> imagingResults
) {

    /** Gắn số phòng của Bác sĩ phụ trách (users.room_number): lấy từ auth-service ở tầng controller, ngoài transaction. */
    public VisitResponse withAssignedRoom(String room) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId,
                receptionistId, room, emergency, status, locked, ticket, weightAtVisit, temperature,
                initialSymptoms, clinicalNotes, diagnosis, treatmentPlan, doctorAdvice, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults);
    }

    /** Gắn phiếu xếp hàng (số in trên phiếu, ưu tiên, giờ gọi) cho màn hình hàng đợi. */
    public VisitResponse withTicket(TicketInfo t) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId,
                receptionistId, assignedRoom, emergency, status, locked, t, weightAtVisit, temperature,
                initialSymptoms, clinicalNotes, diagnosis, treatmentPlan, doctorAdvice, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults);
    }
}
