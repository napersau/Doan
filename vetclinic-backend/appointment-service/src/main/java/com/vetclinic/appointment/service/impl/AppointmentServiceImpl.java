package com.vetclinic.appointment.service.impl;

import com.vetclinic.appointment.client.AuthClient;
import com.vetclinic.appointment.client.PetClient;
import com.vetclinic.appointment.config.BookingProperties;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import com.vetclinic.appointment.dto.request.CancelAppointmentRequest;
import com.vetclinic.appointment.dto.request.CreateAppointmentRequest;
import com.vetclinic.appointment.dto.request.RescheduleAppointmentRequest;
import com.vetclinic.appointment.dto.response.AppointmentHistoryResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.appointment.dto.response.UserSummaryResponse;
import com.vetclinic.appointment.entity.Appointment;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.mapper.AppointmentMapper;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.repository.AppointmentSpecifications;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.appointment.service.AppointmentFilter;
import com.vetclinic.appointment.service.AppointmentService;
import com.vetclinic.appointment.service.SlotLockService;
import com.vetclinic.appointment.service.SlotPolicy;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.event.AppointmentBookedEvent;
import com.vetclinic.common.event.AppointmentNoShowEvent;
import com.vetclinic.common.event.AppointmentStatusChangedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AppointmentServiceImpl implements AppointmentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository historyRepository;
    private final AuthClient authClient;
    private final PetClient petClient;
    private final SlotPolicy slotPolicy;
    private final SlotLockService slotLockService;
    private final TransactionTemplate transactionTemplate;
    private final OutboxWriter outboxWriter;
    private final AppointmentMapper mapper;
    private final BookingProperties properties;
    private final Clock clock;

    public AppointmentServiceImpl(AppointmentRepository appointmentRepository,
                                  AppointmentStatusHistoryRepository historyRepository,
                                  AuthClient authClient,
                                  PetClient petClient,
                                  SlotPolicy slotPolicy,
                                  SlotLockService slotLockService,
                                  PlatformTransactionManager transactionManager,
                                  OutboxWriter outboxWriter,
                                  AppointmentMapper mapper,
                                  BookingProperties properties,
                                  Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.historyRepository = historyRepository;
        this.authClient = authClient;
        this.petClient = petClient;
        this.slotPolicy = slotPolicy;
        this.slotLockService = slotLockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxWriter = outboxWriter;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** aggregate_type của các dòng outbox do service này ghi. */
    private static final String AGGREGATE = "APPOINTMENT";

    private record Actor(UUID id, RoleCode role) {
    }

    private record Booking(UUID ownerId, BookingChannel channel, AppointmentStatus status) {
    }

    // ================================================================== tạo lịch hẹn (UC-14, UC-16)

    /**
     * KHÔNG đánh dấu @Transactional: các lời gọi OpenFeign phải nằm ngoài transaction (quy chuẩn §9.1), và khóa
     * khung giờ phải còn giữ SAU KHI transaction commit. Thứ tự: kiểm tra đầu vào -> Feign -> lấy khóa
     * -> [transaction: đếm chỗ + chèn] -> nhả khóa.
     */
    @Override
    public AppointmentResponse create(CreateAppointmentRequest request) {
        Actor actor = currentActor();
        LocalDate date = request.appointmentDate();
        LocalTime start = request.startTime();
        slotPolicy.assertBookable(date, start);

        Booking booking = resolveBooking(actor, request);
        verifyPet(request.petId(), booking.ownerId());

        int capacity = slotPolicy.activeDoctorCapacity();
        if (capacity <= 0) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED,
                    "Hiện chưa có bác sĩ nào đang hoạt động nên chưa nhận đặt lịch");
        }

        List<String> locks = List.of(SlotLockService.slotLock(date, start),
                SlotLockService.petDayLock(request.petId(), date));
        Appointment saved = slotLockService.runLocked(locks,
                () -> transactionTemplate.execute(tx -> insertAppointment(actor, request, booking, capacity)));
        return toResponse(saved).withOwnerId(booking.ownerId());
    }

    private Booking resolveBooking(Actor actor, CreateAppointmentRequest request) {
        return switch (actor.role()) {
            // Chủ nuôi: ownerId lấy từ token, không bao giờ tin vào body
            case PET_OWNER -> new Booking(actor.id(), BookingChannel.ONLINE, AppointmentStatus.PENDING);
            case RECEPTIONIST -> {
                if (request.ownerId() == null) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Lễ tân phải chọn chủ thú cưng (ownerId)");
                }
                if (request.bookingChannel() == null || request.bookingChannel() == BookingChannel.ONLINE) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                            "Kênh đặt lịch của lễ tân phải là PHONE hoặc WALK_IN");
                }
                assertActivePetOwner(request.ownerId());
                yield new Booking(request.ownerId(), request.bookingChannel(), AppointmentStatus.CONFIRMED);
            }
            default -> throw new AppException(ErrorCode.ACCESS_DENIED);
        };
    }

    /** Chạy trong transaction + khóa khung giờ: đếm chỗ và chèn là một bước không bị chen ngang. */
    private Appointment insertAppointment(Actor actor, CreateAppointmentRequest request, Booking booking, int capacity) {
        LocalDate date = request.appointmentDate();
        LocalTime start = request.startTime();

        long booked = appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                date, start, AppointmentStatus.OCCUPYING);
        if (booked >= capacity) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED);
        }
        if (appointmentRepository.existsByPetIdAndAppointmentDateAndStatusIn(
                request.petId(), date, AppointmentStatus.OCCUPYING)) {
            throw new AppException(ErrorCode.DUPLICATE_APPOINTMENT);
        }

        Instant now = clock.instant();
        boolean confirmed = booking.status() == AppointmentStatus.CONFIRMED;
        // appointments chỉ còn 9 cột (v3.0): không có owner_id, end_time, confirmed_*; người duyệt/tạo nằm ở lịch sử trạng thái
        Appointment appointment = Appointment.builder()
                // Mã tạm duy nhất; đổi thành mã theo id ngay bên dưới (id chỉ có sau khi INSERT)
                .appointmentCode("TMP-" + UUID.randomUUID())
                .petId(request.petId())
                .appointmentDate(date)
                .startTime(start)
                .reason(request.reason().trim())
                .bookingChannel(booking.channel())
                .status(booking.status())
                .build();
        appointment = appointmentRepository.saveAndFlush(appointment);
        appointment.setAppointmentCode(buildCode(appointment.getId()));

        recordHistory(appointment.getId(), null, booking.status(), actor.id(),
                confirmed ? "Lễ tân tạo lịch hẹn qua kênh " + booking.channel()
                        : "Chủ nuôi gửi yêu cầu đặt lịch qua cổng trực tuyến");

        // Transactional Outbox: ghi sự kiện cùng giao dịch; OutboxRelay gửi sang RabbitMQ sau khi commit (RabbitMQ tắt thì vẫn không mất)
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentBookedEvent(UuidV7.generate(clock), now, actor.id(),
                appointment.getId(), appointment.getAppointmentCode(), booking.ownerId(),
                appointment.getPetId(), date, start, appointment.getStatus().name()));
        return appointment;
    }

    // ================================================================== tra cứu

    /**
     * KHÔNG @Transactional: có lời gọi Feign sang clinical-service (quy chuẩn §9.1). Câu truy vấn chính tự chạy trong
     * transaction của repository.
     */
    @Override
    public PageResponse<AppointmentResponse> search(AppointmentFilter filter) {
        Actor actor = currentActor();
        // Chủ nuôi chỉ thấy lịch hẹn của chính mình, bất kể tham số gửi lên
        UUID ownerId = actor.role() == RoleCode.PET_OWNER ? actor.id() : filter.ownerId();

        // appointments không còn owner_id: "lịch của một chủ nuôi" = lịch của các thú cưng của họ
        List<Long> petIds = ownerId == null ? null : petIdsOfOwner(ownerId);

        Sort.Direction direction = filter.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = "createdAt".equalsIgnoreCase(filter.sortBy())
                ? Sort.by(direction, "createdAt", "id")
                : Sort.by(direction, "appointmentDate", "startTime", "id");
        PageRequest pageable = PageRequest.of(Math.max(filter.page(), 0),
                Math.min(Math.max(filter.size(), 1), MAX_PAGE_SIZE), sort);

        Page<Appointment> result = appointmentRepository.findAll(AppointmentSpecifications.filter(
                petIds, filter.petId(), filter.code(), filter.fromDate(), filter.toDate(), filter.status()), pageable);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), this::toResponse);
    }

    @Override
    public AppointmentResponse getById(Long id) {
        Actor actor = currentActor();
        Appointment appointment = findById(id);
        UUID ownerId = resolveOwner(actor, appointment.getPetId());   // chủ nuôi chỉ xem được lịch của mình
        return toResponse(appointment).withOwnerId(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentHistoryResponse> getHistory(Long id) {
        findById(id);
        return historyRepository.findByAppointmentIdOrderByIdAsc(id).stream()
                .map(mapper::toHistoryResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getForInternal(Long id) {
        return toResponse(findById(id));
    }

    // ================================================================== chuyển trạng thái (UC-15, UC-17)
    //
    // Mọi thao tác dưới đây cần biết chủ nuôi (để kiểm quyền / để báo tin), mà appointments không còn cột owner_id: chủ nuôi
    // được tra ở clinical-service qua thú cưng. Lời gọi Feign phải NẰM NGOÀI transaction nên các hàm này không đánh dấu
    // @Transactional; phần ghi dữ liệu chạy trong transactionTemplate.

    @Override
    public AppointmentResponse confirm(Long id) {
        Actor actor = currentActor();
        requireRole(actor, RoleCode.RECEPTIONIST);
        UUID ownerId = resolveOwner(actor, findById(id).getPetId());
        return transactionTemplate.execute(tx -> applyConfirm(actor, id, ownerId));
    }

    private AppointmentResponse applyConfirm(Actor actor, Long id, UUID ownerId) {
        Appointment appointment = lockForUpdate(id);
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận (PENDING) mới được duyệt");
        }
        // Người duyệt và thời điểm duyệt nằm ở dòng lịch sử (changed_by_user_id, created_at)
        transition(appointment, AppointmentStatus.CONFIRMED, actor.id(), "Lễ tân xác nhận lịch hẹn", null, ownerId);
        return toResponse(appointment).withOwnerId(ownerId);
    }

    @Override
    public AppointmentResponse cancel(Long id, CancelAppointmentRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        UUID ownerId = resolveOwner(actor, findById(id).getPetId());
        return transactionTemplate.execute(tx -> applyCancel(actor, id, request, ownerId));
    }

    private AppointmentResponse applyCancel(Actor actor, Long id, CancelAppointmentRequest request, UUID ownerId) {
        Appointment appointment = lockForUpdate(id);
        if (!AppointmentStatus.MODIFIABLE.contains(appointment.getStatus())) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận hoặc đã xác nhận mới hủy được");
        }
        if (actor.role() == RoleCode.PET_OWNER) {
            assertOwnerTimeWindow(appointment, "hủy");
        }
        // Lý do hủy ghi nguyên văn vào appointment_status_histories.notes; ai hủy = changed_by_user_id
        String reason = request.reason().trim();
        transition(appointment, AppointmentStatus.CANCELLED, actor.id(), reason, reason, ownerId);
        return toResponse(appointment).withOwnerId(ownerId);
    }

    /**
     * Không đánh dấu @Transactional vì lý do giống create (Feign ngoài transaction, khóa khung giờ mới).
     */
    @Override
    public AppointmentResponse reschedule(Long id, RescheduleAppointmentRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        LocalDate newDate = request.newDate();
        LocalTime newStart = request.newStartTime();

        // Đọc nhanh (không khóa) để biết thú cưng và kiểm tra quyền; điều kiện thật được kiểm tra lại trong transaction
        Appointment current = findById(id);
        UUID ownerId = resolveOwner(actor, current.getPetId());
        slotPolicy.assertBookable(newDate, newStart);

        int capacity = slotPolicy.activeDoctorCapacity();
        if (capacity <= 0) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED,
                    "Hiện chưa có bác sĩ nào đang hoạt động nên chưa nhận đặt lịch");
        }

        List<String> locks = List.of(SlotLockService.slotLock(newDate, newStart),
                SlotLockService.petDayLock(current.getPetId(), newDate));
        Appointment saved = slotLockService.runLocked(locks,
                () -> transactionTemplate.execute(tx -> applyReschedule(actor, id, request, capacity, ownerId)));
        return toResponse(saved).withOwnerId(ownerId);
    }

    private Appointment applyReschedule(Actor actor, Long id, RescheduleAppointmentRequest request, int capacity, UUID ownerId) {
        LocalDate newDate = request.newDate();
        LocalTime newStart = request.newStartTime();

        Appointment appointment = lockForUpdate(id);
        if (!AppointmentStatus.MODIFIABLE.contains(appointment.getStatus())) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận hoặc đã xác nhận mới đổi được");
        }
        if (actor.role() == RoleCode.PET_OWNER) {
            assertOwnerTimeWindow(appointment, "đổi");
        }
        if (newDate.equals(appointment.getAppointmentDate()) && newStart.equals(appointment.getStartTime())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khung giờ mới trùng với khung giờ hiện tại");
        }

        long booked = appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                newDate, newStart, AppointmentStatus.OCCUPYING);
        if (booked >= capacity) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED);
        }
        if (appointmentRepository.existsByPetIdAndAppointmentDateAndStatusInAndIdNot(
                appointment.getPetId(), newDate, AppointmentStatus.OCCUPYING, appointment.getId())) {
            throw new AppException(ErrorCode.DUPLICATE_APPOINTMENT);
        }

        String note = "Đổi lịch từ " + appointment.getAppointmentDate() + " " + appointment.getStartTime()
                + " sang " + newDate + " " + newStart
                + (request.reason() == null || request.reason().isBlank() ? "" : ". Lý do: " + request.reason().trim());
        appointment.setAppointmentDate(newDate);
        appointment.setStartTime(newStart);
        appointmentRepository.save(appointment);
        // Đổi lịch không đổi trạng thái: from = to, chi tiết ở notes
        recordHistory(appointment.getId(), appointment.getStatus(), appointment.getStatus(), actor.id(), note);
        // Lễ tân dời giờ hộ thì chủ nuôi cần được báo; chủ nuôi tự đổi thì không
        if (actor.role() != RoleCode.PET_OWNER) {
            notifyOwner(appointment, ownerId, actor.id(), true, request.reason() == null ? null : request.reason().trim());
        }
        return appointment;
    }

    // ================================================================== vắng mặt (EX-01) và check-in

    @Override
    public AppointmentResponse markNoShow(Long id) {
        Actor actor = currentActor();
        requireRole(actor, RoleCode.RECEPTIONIST);
        UUID ownerId = resolveOwner(actor, findById(id).getPetId());
        return transactionTemplate.execute(tx -> applyNoShow(actor, id, ownerId));
    }

    private AppointmentResponse applyNoShow(Actor actor, Long id, UUID ownerId) {
        Appointment appointment = lockForUpdate(id);
        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đã xác nhận (CONFIRMED) mới đánh dấu vắng mặt được");
        }
        if (!isNoShowDue(appointment)) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chưa quá " + properties.noShowGraceMinutes() + " phút sau giờ hẹn nên chưa thể đánh dấu vắng mặt");
        }
        transition(appointment, AppointmentStatus.NO_SHOW, actor.id(), "Lễ tân xác nhận khách vắng mặt (No-show)", null, ownerId);
        return toResponse(appointment).withOwnerId(ownerId);
    }

    @Override
    public boolean markNoShowBySystem(Long id) {
        // Lọc nhanh ngoài transaction để không gọi Feign cho lịch không còn đủ điều kiện; điều kiện thật kiểm tra lại có khóa dòng
        Appointment peek = appointmentRepository.findById(id).orElse(null);
        if (peek == null || peek.getStatus() != AppointmentStatus.CONFIRMED || !isNoShowDue(peek)) {
            return false;
        }
        UUID ownerId = lookupOwnerOfPet(peek.getPetId());
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> applyNoShowBySystem(id, ownerId)));
    }

    private boolean applyNoShowBySystem(Long id, UUID ownerId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(id).orElse(null);
        // Idempotent: khách vừa check-in hoặc lịch vừa bị hủy trong lúc tiến trình quét đang chạy thì bỏ qua
        if (appointment == null || appointment.getStatus() != AppointmentStatus.CONFIRMED || !isNoShowDue(appointment)) {
            return false;
        }
        transition(appointment, AppointmentStatus.NO_SHOW, null,
                "Hệ thống tự động chuyển NO_SHOW do quá " + properties.noShowGraceMinutes()
                        + " phút sau giờ hẹn mà khách chưa làm thủ tục tiếp đón.", null, ownerId);
        return true;
    }

    @Override
    public boolean expirePendingBySystem(Long id) {
        Appointment peek = appointmentRepository.findById(id).orElse(null);
        if (peek == null || peek.getStatus() != AppointmentStatus.PENDING || !isPendingExpired(peek)) {
            return false;
        }
        UUID ownerId = lookupOwnerOfPet(peek.getPetId());
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> applyExpirePending(id, ownerId)));
    }

    private boolean applyExpirePending(Long id, UUID ownerId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(id).orElse(null);
        // Idempotent: lễ tân vừa duyệt hoặc chủ nuôi vừa hủy trong lúc tiến trình quét chạy thì bỏ qua
        if (appointment == null || appointment.getStatus() != AppointmentStatus.PENDING || !isPendingExpired(appointment)) {
            return false;
        }
        // changed_by_user_id = null: hệ thống tự hủy
        String reason = "Quá giờ hẹn mà phòng khám chưa xác nhận (hệ thống tự động hủy)";
        transition(appointment, AppointmentStatus.CANCELLED, null, reason, reason, ownerId);
        return true;
    }

    @Override
    @Transactional
    public void markCheckedIn(Long appointmentId, UUID actorUserId, String visitCode) {
        // Không tìm thấy là lỗi vĩnh viễn: ném lỗi để message sau khi hết retry được đẩy sang DLQ cho người xem xét
        Appointment appointment = lockForUpdate(appointmentId);
        switch (appointment.getStatus()) {
            case CHECKED_IN -> log.debug("Lịch hẹn {} đã CHECKED_IN, bỏ qua sự kiện trùng", appointmentId);
            case CONFIRMED -> transition(appointment, AppointmentStatus.CHECKED_IN, actorUserId,
                    "Khách đã tiếp nhận tại quầy, đợt khám " + visitCode, null, null);
            // FR-18: khách đến trễ sau khi đã bị NO_SHOW được tiếp nhận như khách vãng lai; lịch GIỮ NGUYÊN NO_SHOW (ghi nhận lịch sử uy tín)
            case NO_SHOW -> log.warn("Lịch hẹn {} đã NO_SHOW nhưng khách vẫn được tiếp nhận (đợt khám {}): giữ nguyên NO_SHOW (FR-18)",
                    appointmentId, visitCode);
            default -> log.warn("Bỏ qua sự kiện check-in của lịch hẹn {} đang ở trạng thái {} (đợt khám {})",
                    appointmentId, appointment.getStatus(), visitCode);
        }
    }

    // ================================================================== helper

    /** Thời điểm kết thúc khung hẹn = giờ bắt đầu + độ dài khung (appointments không còn cột end_time). */
    private LocalDateTime endOf(Appointment appointment) {
        return LocalDateTime.of(appointment.getAppointmentDate(), slotPolicy.endOf(appointment.getStartTime()));
    }

    /**
     * EX-01 / FR-17: lịch CONFIRMED quá {@code appointment_date + start_time + 30 phút} mà chưa check-in là vắng mặt. Mốc tính từ giờ
     * BẮT ĐẦU khung (không phải giờ kết thúc khung như v2).
     */
    private boolean isNoShowDue(Appointment appointment) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        return slotPolicy.now().isAfter(start.plusMinutes(properties.noShowGraceMinutes()));
    }

    private boolean isPendingExpired(Appointment appointment) {
        return slotPolicy.now().isAfter(endOf(appointment));
    }

    /** Hình dạng response cũ vẫn có endTime: suy ra từ giờ bắt đầu (các trường còn lại do AppointmentEnricher gắn). */
    private AppointmentResponse toResponse(Appointment appointment) {
        return mapper.toResponse(appointment).withEndTime(slotPolicy.endOf(appointment.getStartTime()));
    }

    /** Chủ nuôi chỉ tự hủy / đổi được trước giờ hẹn tối thiểu N giờ; lễ tân không bị giới hạn này. */
    private void assertOwnerTimeWindow(Appointment appointment, String verb) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        if (slotPolicy.now().isAfter(start.minusHours(properties.minCancelHoursBefore()))) {
            throw new AppException(ErrorCode.CANNOT_CANCEL_APPOINTMENT,
                    "Đã quá thời gian cho phép tự " + verb + " lịch trên hệ thống (tối thiểu "
                            + properties.minCancelHoursBefore() + " tiếng trước giờ hẹn). "
                            + "Vui lòng liên hệ trực tiếp hotline phòng khám để được hỗ trợ");
        }
    }

    /**
     * Đổi trạng thái + ghi lịch sử (người thao tác, lý do ở notes) + báo chủ nuôi.
     *
     * @param note    ghi chú ghi vào lịch sử (với CANCELLED: chính là lý do hủy)
     * @param reason  lý do gửi kèm thông báo cho chủ nuôi; có thể null
     * @param ownerId chủ nuôi của lịch hẹn nếu đã tra được (null thì không báo tin được)
     */
    private void transition(Appointment appointment, AppointmentStatus to, UUID actorId, String note,
                            String reason, UUID ownerId) {
        AppointmentStatus from = appointment.getStatus();
        appointment.setStatus(to);
        appointmentRepository.save(appointment);
        recordHistory(appointment.getId(), from, to, actorId, note);
        // CHECKED_IN đã có thông báo riêng từ clinical-service (visit.checked_in); hành động của chính chủ nuôi không cần báo lại.
        // NO_SHOW có sự kiện riêng (appointment.no_show) nên KHÔNG phát thêm status_changed (nếu không chủ nuôi nhận hai thông báo).
        boolean ownerNotifiable = to == AppointmentStatus.CONFIRMED || to == AppointmentStatus.CANCELLED;
        if (ownerNotifiable && (ownerId == null || !ownerId.equals(actorId))) {
            notifyOwner(appointment, ownerId, actorId, false, reason);
        }
        if (to == AppointmentStatus.NO_SHOW) {
            publishNoShow(appointment, ownerId, actorId);
        }
    }

    private void publishNoShow(Appointment appointment, UUID ownerId, UUID actorId) {
        if (ownerId == null) {
            return;   // đã cảnh báo trong notifyOwner
        }
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentNoShowEvent(UuidV7.generate(clock), clock.instant(), actorId,
                appointment.getId(), appointment.getAppointmentCode(), ownerId, appointment.getPetId(),
                appointment.getAppointmentDate(), appointment.getStartTime()));
    }

    /**
     * Ghi sự kiện báo chủ nuôi vào outbox (cùng giao dịch); OutboxRelay gửi sang RabbitMQ sau khi commit.
     * Không tra được chủ nuôi (clinical-service tạm sập) thì bỏ qua thông báo chứ không làm hỏng nghiệp vụ (CLAUDE.md §10 #3).
     */
    private void notifyOwner(Appointment appointment, UUID ownerId, UUID actorId, boolean rescheduled, String reason) {
        if (ownerId == null) {
            log.warn("Không tra được chủ nuôi của lịch hẹn {} (thú cưng {}), bỏ qua thông báo", appointment.getId(), appointment.getPetId());
            return;
        }
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentStatusChangedEvent(UuidV7.generate(clock), clock.instant(), actorId,
                appointment.getId(), appointment.getAppointmentCode(), ownerId, appointment.getPetId(),
                appointment.getAppointmentDate(), appointment.getStartTime(), appointment.getStatus().name(), rescheduled, reason));
    }

    // ---- tra chủ nuôi qua clinical-service (appointments không còn cột owner_id). PHẢI gọi NGOÀI transaction.

    /**
     * Chủ nuôi của lịch hẹn thuộc thú cưng {@code petId}.
     * <ul>
     *   <li>PET_OWNER: bắt buộc tra được và phải là chính họ, nếu không trả "không tìm thấy" (không lộ sự tồn tại của lịch người
     *       khác; tra không được thì lỗi 503 - đóng cửa, không mở).</li>
     *   <li>Nhân viên: tra được thì trả ownerId để báo tin; không tra được thì null (chỉ mất thông báo).</li>
     * </ul>
     */
    private UUID resolveOwner(Actor actor, Long petId) {
        if (actor.role() != RoleCode.PET_OWNER) {
            return lookupOwnerOfPet(petId);
        }
        UUID owner = requireOwnerOfPet(petId);
        if (!owner.equals(actor.id())) {
            throw new AppException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        return owner;
    }

    private UUID lookupOwnerOfPet(Long petId) {
        try {
            ApiResponse<PetSummaryResponse> response = petClient.getPet(petId);
            return response == null || response.getData() == null ? null : response.getData().ownerId();
        } catch (RuntimeException e) {
            log.warn("Không tra được chủ nuôi của thú cưng {} từ clinical-service: {}", petId, e.getMessage());
            return null;
        }
    }

    private UUID requireOwnerOfPet(Long petId) {
        ApiResponse<PetSummaryResponse> response;
        try {
            response = petClient.getPet(petId);
        } catch (AppException e) {
            throw e.getErrorCode() == ErrorCode.PET_NOT_FOUND ? new AppException(ErrorCode.APPOINTMENT_NOT_FOUND) : e;
        } catch (RuntimeException e) {
            log.warn("Không kiểm tra được quyền sở hữu thú cưng {}: {}", petId, e.getMessage());
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getData() == null || response.getData().ownerId() == null) {
            throw new AppException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
        return response.getData().ownerId();
    }

    /**
     * Các thú cưng của một chủ nuôi. Không tra được thì trả danh sách RỖNG (đóng cửa): tuyệt đối không bỏ điều kiện lọc,
     * nếu không chủ nuôi sẽ thấy lịch của người khác.
     */
    private List<Long> petIdsOfOwner(UUID ownerId) {
        try {
            ApiResponse<List<PetSummaryResponse>> response = petClient.getPetsByOwner(ownerId);
            List<PetSummaryResponse> pets = response == null || response.getData() == null ? List.of() : response.getData();
            return pets.stream().map(PetSummaryResponse::id).toList();
        } catch (RuntimeException e) {
            log.warn("Không lấy được danh sách thú cưng của chủ nuôi {} từ clinical-service: {}", ownerId, e.getMessage());
            return List.of();
        }
    }

    private void recordHistory(Long appointmentId, AppointmentStatus from, AppointmentStatus to,
                               UUID actorId, String note) {
        historyRepository.save(AppointmentStatusHistory.builder()
                .appointmentId(appointmentId)
                .fromStatus(from)
                .toStatus(to)
                .changedByUserId(actorId)
                .notes(note)
                .build());
    }

    private Appointment findById(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPOINTMENT_NOT_FOUND));
    }

    private Appointment lockForUpdate(Long id) {
        return appointmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPOINTMENT_NOT_FOUND));
    }

    private void requireRole(Actor actor, RoleCode required) {
        if (actor.role() != required) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private Actor currentActor() {
        return new Actor(SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED)));
    }

    /** Lễ tân đặt hộ phải chọn đúng một tài khoản chủ thú cưng còn hoạt động. */
    private void assertActivePetOwner(UUID ownerId) {
        ApiResponse<UserSummaryResponse> response = authClient.getUser(ownerId);
        UserSummaryResponse owner = response == null ? null : response.getData();
        if (owner == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (!owner.roles().contains(RoleCode.PET_OWNER)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Người dùng được chọn không phải chủ thú cưng");
        }
        if (!"ACTIVE".equals(owner.status())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản chủ thú cưng không còn hoạt động");
        }
    }

    /** Thú cưng phải tồn tại và thuộc đúng chủ nuôi. Bật bằng vetclinic.booking.verify-pet. */
    private void verifyPet(Long petId, UUID ownerId) {
        if (!properties.verifyPet()) {
            return;
        }
        ApiResponse<PetSummaryResponse> response = petClient.getPet(petId);
        PetSummaryResponse pet = response == null ? null : response.getData();
        // Thú cưng của người khác cũng trả PET_NOT_FOUND để không lộ sự tồn tại của hồ sơ
        if (pet == null || !ownerId.equals(pet.ownerId())) {
            throw new AppException(ErrorCode.PET_NOT_FOUND);
        }
    }

    /** APT-yyyyMMdd-NNNNN: ngày tạo + id nên luôn duy nhất, không cần sinh số rồi thử lại khi trùng. */
    private String buildCode(Long id) {
        return "APT-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%05d", id);
    }
}
