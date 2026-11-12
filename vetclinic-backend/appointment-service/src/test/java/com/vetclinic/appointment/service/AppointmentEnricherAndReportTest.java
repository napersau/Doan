package com.vetclinic.appointment.service;

import com.vetclinic.appointment.client.AuthClient;
import com.vetclinic.appointment.client.PetClient;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import com.vetclinic.appointment.dto.response.AppointmentReportResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.appointment.dto.response.UserSummaryResponse;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentEnricherAndReportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Mock
    private AuthClient authClient;
    @Mock
    private PetClient petClient;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentStatusHistoryRepository historyRepository;

    private static AppointmentResponse appointment(long id, long petId, long ownerId) {
        return new AppointmentResponse(id, "APT-" + id, petId, ownerId, LocalDate.of(2026, 10, 31), LocalTime.of(9, 0),
                LocalTime.of(9, 30), "Khám", BookingChannel.ONLINE, AppointmentStatus.PENDING, null, null, null, null, null,
                Instant.EPOCH, Instant.EPOCH, null, null, null);
    }

    // ================================================================== làm giàu tên

    @Test
    void enrichAll_attachesPetAndOwnerNamesWithOneBatchCallEach() {
        when(petClient.getPets(any())).thenReturn(ApiResponse.success(List.of(
                new PetSummaryResponse(11L, 5L, "Lu"), new PetSummaryResponse(12L, 6L, "Mun"))));
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(
                new UserSummaryResponse(5L, "Nguyễn Văn A", "0944444441", RoleCode.PET_OWNER, "ACTIVE"),
                new UserSummaryResponse(6L, "Trần Thị B", "0944444442", RoleCode.PET_OWNER, "ACTIVE"))));
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        List<AppointmentResponse> result = enricher.enrichAll(List.of(appointment(1, 11, 5), appointment(2, 12, 6), appointment(3, 11, 5)));

        assertThat(result).extracting(AppointmentResponse::petName).containsExactly("Lu", "Mun", "Lu");
        assertThat(result).extracting(AppointmentResponse::ownerName).containsExactly("Nguyễn Văn A", "Trần Thị B", "Nguyễn Văn A");
        assertThat(result.get(1).ownerPhone()).isEqualTo("0944444442");
        verify(petClient).getPets(any());      // đúng một lời gọi dù 3 lịch hẹn: không N+1
        verify(authClient).getUsers(any());
    }

    @Test
    void enrichAll_whenOtherServicesAreDown_returnsTheAppointmentsUnchanged() {
        // Không có thú cưng thì không biết chủ nuôi nào để tra: auth-service không được gọi
        when(petClient.getPets(any())).thenThrow(new RuntimeException("clinical down"));
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        AppointmentResponse result = enricher.enrich(appointment(1, 11, 5));

        assertThat(result.appointmentCode()).isEqualTo("APT-1");
        assertThat(result.petName()).isNull();
        assertThat(result.ownerName()).isNull();
    }

    @Test
    void enrichAll_emptyListMakesNoCalls() {
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        assertThat(enricher.enrichAll(List.of())).isEmpty();
        verify(petClient, never()).getPets(any());
        verify(authClient, never()).getUsers(any());
    }


    // ================================================================== suy ra chủ nuôi và trường duyệt/hủy (lược đồ v3.0)

    private static AppointmentResponse withoutOwner(long id, long petId, AppointmentStatus status) {
        // Response do service trả ra: appointments không còn owner_id / cancel_reason / confirmed_* / cancelled_* / updated_at
        return new AppointmentResponse(id, "APT-" + id, petId, null, LocalDate.of(2026, 10, 31), LocalTime.of(9, 0),
                LocalTime.of(9, 30), "Khám", BookingChannel.ONLINE, status, null, null, null, null, null,
                Instant.parse("2026-10-30T01:00:00Z"), null, null, null, null);
    }

    private static AppointmentStatusHistory history(long id, AppointmentStatus from, AppointmentStatus to, Long by, String notes, String at) {
        return AppointmentStatusHistory.builder().id(id).appointmentId(1L).fromStatus(from).toStatus(to)
                .changedByUserId(by).notes(notes).createdAt(Instant.parse(at)).build();
    }

    @Test
    void enrichAll_derivesOwnerFromThePet_notFromTheAppointment() {
        when(petClient.getPets(any())).thenReturn(ApiResponse.success(List.of(new PetSummaryResponse(11L, 5L, "Lu"))));
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(
                new UserSummaryResponse(5L, "Nguyễn Văn A", "0944444441", RoleCode.PET_OWNER, "ACTIVE"))));
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        AppointmentResponse result = enricher.enrich(withoutOwner(1, 11, AppointmentStatus.PENDING));

        assertThat(result.ownerId()).isEqualTo(5L);
        assertThat(result.ownerName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    void enrichAll_derivesConfirmAndCancelAuditFromTheStatusHistory() {
        when(petClient.getPets(any())).thenReturn(ApiResponse.success(List.of(new PetSummaryResponse(11L, 5L, "Lu"))));
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of()));
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenReturn(List.of(
                history(1, null, AppointmentStatus.PENDING, 5L, "Chủ nuôi gửi yêu cầu", "2026-10-30T01:00:00Z"),
                history(2, AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, 9L, "Lễ tân xác nhận", "2026-10-30T01:10:00Z"),
                history(3, AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED, 5L, "Bận việc đột xuất", "2026-10-30T01:20:00Z")));
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        AppointmentResponse result = enricher.enrich(withoutOwner(1, 11, AppointmentStatus.CANCELLED));

        assertThat(result.confirmedBy()).isEqualTo(9L);
        assertThat(result.confirmedAt()).isEqualTo(Instant.parse("2026-10-30T01:10:00Z"));
        assertThat(result.cancelReason()).isEqualTo("Bận việc đột xuất");
        assertThat(result.cancelledBy()).isEqualTo(5L);
        assertThat(result.cancelledAt()).isEqualTo(Instant.parse("2026-10-30T01:20:00Z"));
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-10-30T01:20:00Z"));   // lần đổi cuối cùng
    }

    @Test
    void enrichAll_receptionistCreatedAppointment_countsTheCreationAsTheConfirmation() {
        when(petClient.getPets(any())).thenReturn(ApiResponse.success(List.of()));
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenReturn(List.of(
                history(1, null, AppointmentStatus.CONFIRMED, 7L, "Lễ tân tạo lịch hẹn", "2026-10-30T01:00:00Z")));
        AppointmentEnricher enricher = new AppointmentEnricher(authClient, petClient, historyRepository);

        AppointmentResponse result = enricher.enrich(withoutOwner(1, 11, AppointmentStatus.CONFIRMED));

        assertThat(result.confirmedBy()).isEqualTo(7L);
        assertThat(result.cancelReason()).isNull();
        assertThat(result.cancelledBy()).isNull();
    }

    // ================================================================== báo cáo

    @Test
    void report_computesNoShowAndCancellationRates() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);
        when(appointmentRepository.countByStatusBetween(any(), any())).thenReturn(List.of(
                new Object[]{AppointmentStatus.CHECKED_IN, 8L}, new Object[]{AppointmentStatus.NO_SHOW, 2L},
                new Object[]{AppointmentStatus.CANCELLED, 5L}, new Object[]{AppointmentStatus.PENDING, 5L}));
        when(appointmentRepository.countByChannelBetween(any(), any())).thenReturn(List.of(
                new Object[]{BookingChannel.ONLINE, 12L}, new Object[]{BookingChannel.PHONE, 8L}));

        AppointmentReportResponse result = report.summary(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30));

        assertThat(result.totalAppointments()).isEqualTo(20);
        assertThat(result.noShowRate()).isEqualTo(0.2);          // 2 / (8 + 2)
        assertThat(result.cancellationRate()).isEqualTo(0.25);   // 5 / 20
        assertThat(result.byStatus()).containsEntry("CONFIRMED", 0L).containsEntry("NO_SHOW", 2L);   // đủ mọi trạng thái
        assertThat(result.byChannel()).containsEntry("ONLINE", 12L).containsEntry("PHONE", 8L).containsEntry("WALK_IN", 0L);
    }

    @Test
    void report_noOutcomesYetMeansZeroRatesNotDivisionByZero() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);
        when(appointmentRepository.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(appointmentRepository.countByChannelBetween(any(), any())).thenReturn(List.of());

        AppointmentReportResponse result = report.summary(null, null);

        assertThat(result.noShowRate()).isZero();
        assertThat(result.cancellationRate()).isZero();
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(result.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void report_refusesInvertedOrTooLongRanges() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);

        assertThatThrownBy(() -> report.summary(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
        assertThatThrownBy(() -> report.summary(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 30)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
    }
}
