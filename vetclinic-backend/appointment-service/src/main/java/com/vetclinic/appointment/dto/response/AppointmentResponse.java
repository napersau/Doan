package com.vetclinic.appointment.dto.response;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record AppointmentResponse(
        Long id,
        String appointmentCode,
        Long petId,
        Long ownerId,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        BookingChannel bookingChannel,
        AppointmentStatus status,
        String cancelReason,
        Long confirmedBy,
        Instant confirmedAt,
        Long cancelledBy,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        String petName,
        String ownerName,
        String ownerPhone
) {

    /**
     * Từ lược đồ v3.0, bảng appointments chỉ có 9 cột. Các trường dưới đây KHÔNG còn lưu mà được suy ra:
     * endTime = startTime + độ dài khung; ownerId = pets.owner_id (clinical-service); cancelReason / confirmedBy / confirmedAt /
     * cancelledBy / cancelledAt / updatedAt lấy từ appointment_status_histories (AppointmentEnricher). Hình dạng response giữ
     * nguyên để Frontend và clinical-service không phải đổi.
     */
    public AppointmentResponse withEndTime(LocalTime endTime) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId, appointmentDate, startTime, endTime, reason,
                bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy, cancelledAt, createdAt, updatedAt,
                petName, ownerName, ownerPhone);
    }

    /** Gắn chủ nuôi (suy ra từ thú cưng). Tham số null thì giữ nguyên giá trị hiện có. */
    public AppointmentResponse withOwnerId(Long ownerId) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId != null ? ownerId : this.ownerId, appointmentDate,
                startTime, endTime, reason, bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy,
                cancelledAt, createdAt, updatedAt, petName, ownerName, ownerPhone);
    }

    /** Gắn người/lý do/thời điểm duyệt, hủy và lần cập nhật cuối (lấy từ lịch sử trạng thái). */
    public AppointmentResponse withAudit(String cancelReason, Long confirmedBy, Instant confirmedAt,
                                         Long cancelledBy, Instant cancelledAt, Instant updatedAt) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId, appointmentDate, startTime, endTime, reason,
                bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy, cancelledAt, createdAt,
                updatedAt != null ? updatedAt : this.updatedAt, petName, ownerName, ownerPhone);
    }

    /** Gắn tên thú cưng / chủ nuôi (lấy từ clinical và auth). Tham số null thì giữ nguyên giá trị hiện có. */
    public AppointmentResponse withNames(String petName, String ownerName, String ownerPhone) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId, appointmentDate, startTime, endTime, reason,
                bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy, cancelledAt, createdAt, updatedAt,
                petName != null ? petName : this.petName, ownerName != null ? ownerName : this.ownerName,
                ownerPhone != null ? ownerPhone : this.ownerPhone);
    }
}
