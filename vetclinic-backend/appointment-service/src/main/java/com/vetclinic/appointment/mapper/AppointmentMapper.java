package com.vetclinic.appointment.mapper;

import com.vetclinic.appointment.dto.response.AppointmentHistoryResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.entity.Appointment;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface AppointmentMapper {

    // Các trường dưới đây không còn là cột của appointments (lược đồ v3.0) nên được gắn sau:
    //  - endTime: AppointmentServiceImpl (start + độ dài khung)
    //  - ownerId, ownerName, ownerPhone, petName: AppointmentEnricher (pets ở clinical-service, users ở auth-service)
    //  - cancelReason, confirmedBy/At, cancelledBy/At, updatedAt: AppointmentEnricher (appointment_status_histories)
    @org.mapstruct.Mapping(target = "ownerId", ignore = true)
    @org.mapstruct.Mapping(target = "endTime", ignore = true)
    @org.mapstruct.Mapping(target = "cancelReason", ignore = true)
    @org.mapstruct.Mapping(target = "confirmedBy", ignore = true)
    @org.mapstruct.Mapping(target = "confirmedAt", ignore = true)
    @org.mapstruct.Mapping(target = "cancelledBy", ignore = true)
    @org.mapstruct.Mapping(target = "cancelledAt", ignore = true)
    @org.mapstruct.Mapping(target = "updatedAt", ignore = true)
    @org.mapstruct.Mapping(target = "petName", ignore = true)
    @org.mapstruct.Mapping(target = "ownerName", ignore = true)
    @org.mapstruct.Mapping(target = "ownerPhone", ignore = true)
    AppointmentResponse toResponse(Appointment appointment);

    AppointmentHistoryResponse toHistoryResponse(AppointmentStatusHistory history);
}
