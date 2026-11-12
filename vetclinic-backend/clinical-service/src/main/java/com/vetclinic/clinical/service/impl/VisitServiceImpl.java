package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AppointmentClient;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.ClinicalInfoRequest;
import com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.ServiceOrderItem;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.response.AppointmentSummaryResponse;
import com.vetclinic.clinical.dto.response.BillableServiceResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.QueueOrders;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.lock.NamedLockService;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VisitServiceImpl implements VisitService {

    /** Phí khám lâm sàng tổng quát, tự thêm vào đợt khám khi Bác sĩ bắt đầu khám (CLAUDE.md §5.8). */
    static final String EXAM_SERVICE_CODE = "EXAM_GEN";

    private final PetVisitRepository visitRepository;
    private final PetRepository petRepository;
    private final PetVisitStatusHistoryRepository historyRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final ClinicServiceRepository serviceRepository;
    private final AuthClient authClient;
    private final AppointmentClient appointmentClient;
    private final VisitSupport support;
    private final VisitAssembler assembler;
    private final NamedLockService lockService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public VisitServiceImpl(PetVisitRepository visitRepository, PetRepository petRepository,
                            PetVisitStatusHistoryRepository historyRepository,
                            VisitServiceDetailRepository detailRepository, ClinicServiceRepository serviceRepository,
                            AuthClient authClient, AppointmentClient appointmentClient,
                            VisitSupport support, VisitAssembler assembler,
                            NamedLockService lockService, ApplicationEventPublisher eventPublisher, Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.petRepository = petRepository;
        this.historyRepository = historyRepository;
        this.detailRepository = detailRepository;
        this.serviceRepository = serviceRepository;
        this.authClient = authClient;
        this.appointmentClient = appointmentClient;
        this.support = support;
        this.assembler = assembler;
        this.lockService = lockService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private record Actor(Long id, RoleCode role) {
    }

    // ================================================================== tiếp nhận (UC-19) và hàng đợi (UC-20)

    /**
     * KHÔNG @Transactional: lời gọi OpenFeign phải nằm ngoài transaction, và khóa tên phải còn giữ SAU KHI transaction
     * commit. Thứ tự: kiểm tra lịch hẹn qua Feign -> lấy khóa theo thú cưng -> [transaction: kiểm tra + tạo] -> nhả khóa.
     * Khóa theo thú cưng chặn hai lễ tân (hoặc bấm đúp) cùng tạo hai đợt khám đang diễn ra cho một thú cưng.
     */
    @Override
    public VisitResponse checkIn(CheckInRequest request) {
        Actor actor = currentActor();
        Long petId = request.petId();
        Long appointmentId = request.appointmentId();
        String symptoms = blankToNull(request.initialSymptoms());
        // Bác sĩ chọn ngay khi tiếp nhận (tùy chọn): kiểm tra qua Feign NGOÀI transaction
        Long doctorId = request.doctorId() == null ? null : fetchActiveDoctor(request.doctorId()).id();

        if (appointmentId != null) {
            if (visitRepository.existsByAppointmentId(appointmentId)) {
                throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT);
            }
            ApiResponse<AppointmentSummaryResponse> response = appointmentClient.getAppointment(appointmentId);
            AppointmentSummaryResponse appointment = response == null ? null : response.getData();
            if (appointment == null) {
                throw new AppException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }
            if (!"CONFIRMED".equals(appointment.status()) || !LocalDate.now(clock).equals(appointment.appointmentDate())) {
                throw new AppException(ErrorCode.APPOINTMENT_NOT_CHECKABLE);
            }
            if (petId != null && !petId.equals(appointment.petId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thú cưng không khớp với lịch hẹn");
            }
            petId = appointment.petId();
            if (symptoms == null) {
                symptoms = blankToNull(appointment.reason());
            }
        }
        if (petId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chọn thú cưng hoặc lịch hẹn để tiếp nhận");
        }

        Long finalPetId = petId;
        String finalSymptoms = symptoms;
        return lockService.runLocked(List.of("vetclinic:visit-pet:" + petId), () -> transactionTemplate.execute(
                tx -> createVisit(actor.id(), finalPetId, appointmentId, finalSymptoms,
                        Boolean.TRUE.equals(request.emergency()), doctorId)));
    }

    private VisitResponse createVisit(Long receptionistId, Long petId, Long appointmentId, String symptoms,
                                      boolean emergency, Long doctorId) {
        Pet pet = petRepository.findWithSpeciesById(petId)
                .orElseThrow(() -> new AppException(ErrorCode.PET_NOT_FOUND));
        if (visitRepository.existsByPetIdAndStatusIn(petId, VisitStatus.ACTIVE)) {
            throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT);
        }
        if (appointmentId != null && visitRepository.existsByAppointmentId(appointmentId)) {
            throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT, "Lịch hẹn này đã được tiếp nhận trước đó");
        }

        Instant now = clock.instant();
        PetVisit visit = PetVisit.builder()
                // Mã tạm duy nhất; đổi thành mã theo id ngay bên dưới (id chỉ có sau khi INSERT)
                .visitCode("TMP-" + UUID.randomUUID())
                .pet(pet)
                .appointmentId(appointmentId)
                .doctorId(doctorId)
                .receptionistId(receptionistId)
                .emergency(emergency)
                .initialSymptoms(symptoms)
                .status(VisitStatus.WAITING)
                .checkedInAt(now)
                // Thứ tự hàng đợi = thời điểm tiếp nhận (epoch micro giây); mọi hàng đợi: cấp cứu chưa bị lùi rồi queue_order
                .queueOrder(QueueOrders.of(now))
                .build();
        visit = visitRepository.saveAndFlush(visit);
        visit.setVisitCode(String.format("VIS-%d-%05d", LocalDate.now(clock).getYear(), visit.getId()));

        support.record(visit.getId(), null, VisitStatus.WAITING, receptionistId,
                (appointmentId == null ? "Tiếp nhận khách vãng lai" : "Tiếp nhận khách có lịch hẹn #" + appointmentId)
                        + (emergency ? " (CẤP CỨU)" : "")
                        + (doctorId == null ? "" : ", xếp vào hàng đợi Bác sĩ #" + doctorId));
        // Chỉ được gửi sang RabbitMQ SAU KHI commit (DomainEventRabbitPublisher): appointment-service sẽ chuyển lịch hẹn sang CHECKED_IN
        eventPublisher.publishEvent(new VisitCheckedInEvent(UUID.randomUUID(), now, receptionistId, visit.getId(),
                visit.getVisitCode(), pet.getId(), appointmentId, emergency, doctorId));
        return assembler.summary(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public com.vetclinic.common.dto.PageResponse<VisitResponse> search(VisitStatus status, java.time.LocalDate date,
            Long doctorId, Long petId, String keyword, int page, int size) {
        org.springframework.data.jpa.domain.Specification<PetVisit> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (doctorId != null) predicates.add(cb.equal(root.get("doctorId"), doctorId));
            if (petId != null) predicates.add(cb.equal(root.get("pet").get("id"), petId));
            if (date != null) {
                java.time.ZoneId zone = clock.getZone();
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkedInAt"), date.atStartOfDay(zone).toInstant()));
                predicates.add(cb.lessThan(root.get("checkedInAt"), date.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (keyword != null && !keyword.isBlank()) {
                String escaped = keyword.trim().toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("visitCode")), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
        org.springframework.data.domain.Page<PetVisit> result = visitRepository.findAll(spec, pageable);
        // Một truy vấn lịch sử cho cả trang (VisitTimeline), không N+1
        Map<Long, VisitResponse> byId = assembler.summaries(result.getContent()).stream()
                .collect(Collectors.toMap(VisitResponse::id, Function.identity()));
        return com.vetclinic.common.dto.PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isLast(), v -> byId.get(v.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public VisitResponse getById(Long id) {
        Actor actor = currentActor();
        PetVisit visit = visitRepository.findWithPetById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        // Chủ nuôi chỉ xem được bệnh án của thú cưng mình; của người khác trả "không tìm thấy"
        if (actor.role() == RoleCode.PET_OWNER && !visit.getPet().getOwnerId().equals(actor.id())) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        return assembler.detail(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitHistoryResponse> getHistory(Long id) {
        if (!visitRepository.existsById(id)) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        return historyRepository.findByVisitIdOrderByIdAsc(id).stream()
                .map(h -> new VisitHistoryResponse(h.getId(), h.getFromStatus(), h.getToStatus(),
                        h.getChangedByUserId(), h.getNotes(), h.getCreatedAt())).toList();
    }

    // ================================================================== phân công Bác sĩ (UC-21)

    /** KHÔNG @Transactional: kiểm tra Bác sĩ qua Feign NGOÀI transaction; việc ghi chạy trong transactionTemplate. */
    @Override
    public VisitResponse assignDoctor(Long id, AssignDoctorRequest request) {
        Actor actor = currentActor();
        UserSummaryResponse doctor = fetchActiveDoctor(request.doctorId());
        return transactionTemplate.execute(tx -> applyAssignment(id, doctor));
    }

    private VisitResponse applyAssignment(Long visitId, UserSummaryResponse doctor) {
        PetVisit visit = support.lock(visitId);   // khóa dòng + @Version: người đến sau thấy trạng thái mới nhất (FR-25)
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ xếp / chuyển được ca đang chờ khám (WAITING)");
        if (doctor.id().equals(visit.getDoctorId())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ca này đã được xếp vào hàng đợi của bác sĩ này");
        }
        // KHÔNG kiểm tra Bác sĩ rảnh/bận (v3.0). Giữ nguyên queue_order: ca đến trước vẫn đứng trước trong hàng đợi mới.
        // Trạng thái không đổi nên không ghi pet_visit_status_histories.
        visit.setDoctorId(doctor.id());
        visitRepository.save(visit);
        return assembler.summary(visit).withAssignedRoom(doctor.roomNumber());
    }

    @Override
    @Transactional
    public VisitResponse unassignDoctor(Long id) {
        PetVisit visit = support.lock(id);
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ gỡ phân công được ca đang chờ khám (WAITING)");
        if (visit.getDoctorId() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ca này chưa được xếp vào hàng đợi của bác sĩ nào");
        }
        visit.setDoctorId(null);
        visitRepository.save(visit);
        return assembler.summary(visit);
    }

    // ================================================================== khám giai đoạn 1 (UC-22, UC-23)

    @Override
    @Transactional
    public VisitResponse startExam(Long id, StartExamRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.WAITING,
                "Chỉ có thể bắt đầu khám đối với ca đang ở trạng thái Chờ khám (WAITING)");
        // BR-12: một Bác sĩ chỉ khám một ca tại một thời điểm
        if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
        }

        if (request != null) {
            applyClinicalInfo(visit, request.weightAtVisit(), request.temperature(), request.clinicalNotes());
        }
        // Thời điểm bắt đầu khám = created_at của dòng lịch sử này (không còn cột started_at)
        support.transition(visit, VisitStatus.IN_PROGRESS, doctorId, "Bác sĩ bắt đầu khám lâm sàng Giai đoạn 1");

        // T02: tự tạo MỘT dịch vụ khám (EXAM_GEN) ở trạng thái IN_PROGRESS kèm hóa đơn PENDING (phí khám là một dịch vụ)
        serviceRepository.findByCode(EXAM_SERVICE_CODE)
                .filter(ClinicService::isActive)
                .filter(s -> !detailRepository.existsByVisitAndServiceCode(visit.getId(), s.getCode()))
                .ifPresent(s -> createDetail(visit.getId(), s, 1, "Phí khám lâm sàng tổng quát (tự động)", doctorId,
                        ServiceDetailStatus.IN_PROGRESS));
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse updateClinicalInfo(Long id, ClinicalInfoRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ cập nhật được thông tin lâm sàng khi ca đang khám (IN_PROGRESS)");
        applyClinicalInfo(visit, request.weightAtVisit(), request.temperature(), request.clinicalNotes());
        visitRepository.save(visit);
        return assembler.detail(visit);
    }

    private static void applyClinicalInfo(PetVisit visit, BigDecimal weight, BigDecimal temperature, String notes) {
        if (weight != null) {
            visit.setWeightAtVisit(weight);
        }
        if (temperature != null) {
            visit.setTemperature(temperature);
        }
        if (notes != null) {
            visit.setClinicalNotes(blankToNull(notes));
        }
    }

    @Override
    @Transactional
    public VisitResponse orderParaclinical(Long id, ParaclinicalOrderRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        List<Long> serviceIds = request.serviceIds().stream().distinct().toList();
        List<ClinicService> services = loadActiveServices(serviceIds);
        if (services.stream().anyMatch(s -> !s.getCategory().isParaclinical())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chỉ định cận lâm sàng chỉ nhận dịch vụ xét nghiệm (LABORATORY) hoặc chẩn đoán hình ảnh (IMAGING)");
        }

        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chỉ định cận lâm sàng khi ca khám đang diễn ra (IN_PROGRESS)");
        if (support.isPhaseTwo(visit.getId())) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Ca đã ở Giai đoạn 2: không chỉ định thêm cận lâm sàng");
        }
        // T04: phải có cân nặng và thân nhiệt (> 0) trước khi đẩy sang cận lâm sàng
        if (visit.getWeightAtVisit() == null || visit.getTemperature() == null || visit.getTemperature().signum() <= 0) {
            throw new AppException(ErrorCode.VITAL_SIGNS_REQUIRED);
        }
        // T04: các dịch vụ làm tại phòng đã chỉ định ở Giai đoạn 1 phải xong (COMPLETED) hoặc hủy (CANCELLED)
        if (detailRepository.countByVisitAndCategoriesAndStatuses(visit.getId(), ServiceCategory.IN_ROOM, ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.IN_ROOM_SERVICE_PENDING);
        }

        String notes = blankToNull(request.notes());
        for (ClinicService service : services) {
            // Mỗi dịch vụ: dòng chỉ định PENDING (tự vào hàng đợi phòng, queue_order = lúc chỉ định) + hóa đơn PENDING, cùng giao dịch
            createDetail(visit.getId(), service, 1, notes, doctorId, ServiceDetailStatus.PENDING);
        }
        support.completeExamDetails(visit.getId(), doctorId);   // dịch vụ khám của GĐ 1 hoàn tất
        // FR-33: ca rời IN_PROGRESS nên trạng thái Bác sĩ tính ra AVAILABLE: Bác sĩ gọi số tiếp theo mà không chờ kết quả
        support.transition(visit, VisitStatus.WAITING_RESULTS, doctorId,
                "Bác sĩ chỉ định cận lâm sàng, ca chuyển sang chờ kết quả và Bác sĩ được giải phóng");
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse orderInRoomServices(Long id, OrderServicesRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        List<Long> serviceIds = request.services().stream().map(ServiceOrderItem::serviceId).distinct().toList();
        Map<Long, ClinicService> found = loadActiveServices(serviceIds).stream()
                .collect(Collectors.toMap(ClinicService::getId, Function.identity()));
        if (found.values().stream().anyMatch(s -> !s.getCategory().isInRoom())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chỉ nhận dịch vụ thực hiện tại phòng (tiêm, tẩy giun, ngoại ký sinh, truyền dịch, thủ thuật, phẫu thuật)");
        }

        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chỉ định dịch vụ khi ca khám đang diễn ra (IN_PROGRESS)");

        for (ServiceOrderItem item : request.services()) {
            // PENDING cho tới khi Bác sĩ bấm "Đã thực hiện"; hóa đơn PENDING lập ngay cùng giao dịch
            createDetail(visit.getId(), found.get(item.serviceId()), item.quantity() == null ? 1 : item.quantity(),
                    blankToNull(item.notes()), doctorId, ServiceDetailStatus.PENDING);
        }
        return assembler.detail(visit);
    }

    private List<ClinicService> loadActiveServices(List<Long> serviceIds) {
        List<ClinicService> found = serviceRepository.findByIdInAndActiveTrue(serviceIds);
        if (found.size() != serviceIds.size()) {
            throw new AppException(ErrorCode.SERVICE_NOT_FOUND);
        }
        return found;
    }

    /**
     * Tạo dòng chỉ định + HÓA ĐƠN RIÊNG của nó trong cùng giao dịch (BR-19, FR-32). Đơn giá / VAT được snapshot vào hóa đơn
     * (BR-25), visit_service_details không chứa giá. {@code queue_order} = lúc chỉ định (hàng đợi Lab / CĐHA).
     */
    private VisitServiceDetail createDetail(Long visitId, ClinicService service, int quantity, String notes, Long actorId,
                                            ServiceDetailStatus status) {
        VisitServiceDetail detail = detailRepository.saveAndFlush(VisitServiceDetail.builder()
                .visitId(visitId)
                .service(service)
                .quantity(quantity)
                .status(status)
                .notes(notes)
                .queueOrder(QueueOrders.now(clock))
                .build());
        support.createInvoice(detail, service);
        support.recordDetail(detail.getId(), null, status, "ORDERED", actorId, notes);
        return detail;
    }

    // ================================================================== khám giai đoạn 2 (UC-30, UC-33)

    @Override
    @Transactional
    public VisitResponse startPhase2(Long id) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.RESULTS_READY,
                "Chỉ bắt đầu Giai đoạn 2 với ca đã có kết quả cận lâm sàng (RESULTS_READY)");
        // BR-12: một Bác sĩ chỉ khám một ca tại một thời điểm
        if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
        }
        // Hai giai đoạn của IN_PROGRESS phân biệt bằng lịch sử: đã qua WAITING_RESULTS thì là Giai đoạn 2
        support.transition(visit, VisitStatus.IN_PROGRESS, doctorId, "Bác sĩ bắt đầu khám Giai đoạn 2 (đọc kết quả cận lâm sàng)");
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse closeVisit(Long id, CloseVisitRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        String diagnosis = blankToNull(request.diagnosis());
        // EX-12: chặn chốt ca khi chưa có chẩn đoán xác định
        if (diagnosis == null) {
            throw new AppException(ErrorCode.DIAGNOSIS_REQUIRED);
        }

        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chốt được ca đang khám (IN_PROGRESS)");
        if (detailRepository.countByVisitAndCategoriesAndStatuses(visit.getId(), ServiceCategory.PARACLINICAL,
                ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Còn dịch vụ cận lâm sàng chưa hoàn tất");
        }
        if (visit.getWeightAtVisit() == null || visit.getTemperature() == null || visit.getTemperature().signum() <= 0) {
            throw new AppException(ErrorCode.VITAL_SIGNS_REQUIRED);
        }
        if (detailRepository.countByVisitAndCategoriesAndStatuses(visit.getId(), ServiceCategory.IN_ROOM, ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.IN_ROOM_SERVICE_PENDING);
        }

        visit.setDiagnosis(diagnosis);
        visit.setTreatmentPlan(blankToNull(request.treatmentPlan()));
        visit.setDoctorAdvice(blankToNull(request.doctorAdvice()));
        if (request.clinicalNotes() != null) {
            visit.setClinicalNotes(blankToNull(request.clinicalNotes()));
        }
        support.completeExamDetails(visit.getId(), doctorId);   // dịch vụ khám hoàn tất khi Bác sĩ rời giai đoạn khám
        // Khóa hồ sơ chuyên môn một chiều (BR-27); thời điểm chốt = created_at của dòng lịch sử (không còn cột closed_at)
        support.transition(visit, VisitStatus.WAITING_PAYMENT, doctorId,
                "Bác sĩ chốt chuyên môn, khóa hồ sơ bệnh án và chuyển sang thanh toán");
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse cancelExam(Long id, ReasonRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ hủy ca / chuyển viện được khi ca đang khám (IN_PROGRESS)");
        if (support.isPhaseTwo(visit.getId())) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Ca đã có kết quả cận lâm sàng: không hủy ca, hãy chốt ca để thu phí các dịch vụ đã làm");
        }
        if (detailRepository.countByVisitAndCategoriesAndStatuses(visit.getId(), ServiceCategory.IN_ROOM,
                List.of(ServiceDetailStatus.COMPLETED)) > 0) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Đã thực hiện dịch vụ tại phòng: không hủy ca, hãy chốt ca để thu phí dịch vụ đã làm");
        }
        String reason = request.reason().trim();
        cancelOpenDetails(visit.getId(), doctorId, "Hủy ca / chuyển viện: " + reason, true);
        // Lý do hủy ghi nguyên văn vào notes của dòng lịch sử (không còn cột cancel_reason)
        support.transition(visit, VisitStatus.CANCELLED, doctorId, reason);
        return assembler.detail(visit);
    }

    /**
     * Hủy các dòng chỉ định còn dở (PENDING / IN_PROGRESS) kèm hóa đơn của chính chúng, cùng giao dịch (BR-33). {@code includeExam = false}
     * thì chỉ hủy dịch vụ cận lâm sàng (khách bỏ về: dịch vụ khám và dịch vụ tại phòng đã làm giữ nguyên làm công nợ).
     */
    private void cancelOpenDetails(Long visitId, Long actorId, String reason, boolean includeExam) {
        for (VisitServiceDetail detail : detailRepository.findByVisitIdOrderByIdAsc(visitId)) {
            if (!ServiceDetailStatus.OPEN.contains(detail.getStatus())) {
                continue;
            }
            if (!includeExam && !detail.getService().getCategory().isParaclinical()) {
                continue;
            }
            ServiceDetailStatus from = detail.getStatus();
            detail.setStatus(ServiceDetailStatus.CANCELLED);
            detailRepository.save(detail);
            support.recordDetail(detail.getId(), from, ServiceDetailStatus.CANCELLED, "CANCELLED", actorId, reason);
            support.cancelInvoiceOf(detail.getId());   // dịch vụ CANCELLED <=> hóa đơn CANCELLED (hóa đơn PAID thì CLIN_1310)
        }
    }

    @Override
    @Transactional
    public VisitResponse reopen(Long id, ReasonRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.VETERINARIAN && actor.role() != RoleCode.ADMIN) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        String reason = request.reason().trim();
        PetVisit visit = support.lock(id);
        if (actor.role() == RoleCode.VETERINARIAN) {
            support.assertAssignedTo(visit, actor.id());
        }
        support.assertStatus(visit, VisitStatus.WAITING_PAYMENT, "Chỉ mở lại được ca đang chờ thanh toán (WAITING_PAYMENT)");
        Long doctorId = visit.getDoctorId();
        // Bác sĩ phụ trách đang khám ca khác thì chưa mở lại được (BR-12)
        if (doctorId == null || visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE, "Bác sĩ phụ trách đang bận ca khác, hãy mở lại khi bác sĩ rảnh");
        }
        support.transition(visit, VisitStatus.IN_PROGRESS, actor.id(), "Mở lại hồ sơ để điều chỉnh chuyên môn: " + reason);
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse abandon(Long id) {
        Actor actor = currentActor();
        PetVisit visit = support.lock(id);
        if (actor.role() == RoleCode.VETERINARIAN) {
            support.assertAssignedTo(visit, actor.id());
        } else if (actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (visit.getStatus() != VisitStatus.RESULTS_READY && visit.getStatus() != VisitStatus.WAITING_RESULTS) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Chỉ đóng ca \"khách bỏ về\" được với ca đang chờ kết quả (WAITING_RESULTS) hoặc đã có kết quả (RESULTS_READY)");
        }
        // T12: không đóng ca khi kỹ thuật viên đang thực hiện dở một chỉ định (khách đang ở trong phòng)
        if (detailRepository.countByVisitAndCategoriesAndStatuses(visit.getId(), ServiceCategory.PARACLINICAL,
                List.of(ServiceDetailStatus.IN_PROGRESS)) > 0) {
            throw new AppException(ErrorCode.PARACLINICAL_SERVICE_IN_PROGRESS);
        }
        // Chỉ định cận lâm sàng chưa thực hiện bị hủy kèm hóa đơn; dịch vụ đã làm giữ hóa đơn PENDING làm công nợ
        cancelOpenDetails(visit.getId(), actor.id(), "Khách bỏ về: hủy chỉ định chưa thực hiện", false);
        support.transition(visit, VisitStatus.COMPLETED, actor.id(),
                "Khách bỏ về: đóng ca, lưu kết quả vào bệnh án và ghi nợ viện phí (EX-11)");
        eventPublisher.publishEvent(new VisitCompletedEvent(UUID.randomUUID(), clock.instant(), actor.id(), visit.getId(),
                visit.getVisitCode(), visit.getPet().getId(), true));
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse cancel(Long id, ReasonRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        PetVisit visit = support.lock(id);
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ hủy tiếp nhận được ca đang chờ khám (WAITING)");
        // Lý do hủy ghi nguyên văn vào notes của dòng lịch sử (không còn cột cancel_reason)
        support.transition(visit, VisitStatus.CANCELLED, actor.id(), request.reason().trim());
        return assembler.detail(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> myVisits() {
        return assembler.summaries(visitRepository.findDoctorVisits(
                SecurityUtils.getCurrentUserId(),
                List.of(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, VisitStatus.RESULTS_READY)));
    }

    // ================================================================== không cần người dùng đăng nhập

    /**
     * TẠM (xóa ở bước 3.B13): billing v2 vẫn gọi endpoint này. Giá lấy từ hóa đơn dịch vụ đã snapshot lúc chỉ định
     * (visit_service_details không còn unit_price / subtotal).
     */
    @Override
    @Transactional(readOnly = true)
    public List<BillableServiceResponse> billableServices(Long visitId) {
        if (!visitRepository.existsById(visitId)) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        List<VisitServiceDetail> done = detailRepository.findByVisitIdAndStatusOrderByIdAsc(visitId, ServiceDetailStatus.COMPLETED);
        var invoices = support.invoicesOf(done.stream().map(VisitServiceDetail::getId).toList());
        return done.stream().map(d -> {
            var invoice = invoices.get(d.getId());
            var unitPrice = invoice != null ? invoice.getUnitPrice() : d.getService().getPrice();
            return new BillableServiceResponse(d.getService().getId(), d.getService().getCode(), d.getService().getName(),
                    d.getService().getCategory(), d.getQuantity(), unitPrice,
                    unitPrice.multiply(java.math.BigDecimal.valueOf(d.getQuantity())));
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VisitSummaryResponse summary(Long visitId) {
        return visitRepository.findWithPetById(visitId).map(VisitServiceImpl::toSummary)
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitSummaryResponse> summaries(Collection<Long> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) {
            return List.of();
        }
        return visitRepository.findWithPetByIdIn(visitIds).stream().map(VisitServiceImpl::toSummary).toList();
    }

    private static VisitSummaryResponse toSummary(PetVisit v) {
        return new VisitSummaryResponse(v.getId(), v.getVisitCode(), v.getStatus(), v.getPet().getId(),
                v.getPet().getName(), v.getPet().getOwnerId(), v.getDoctorId());
    }

    // ================================================================== helper

    private UserSummaryResponse fetchActiveDoctor(Long doctorId) {
        ApiResponse<UserSummaryResponse> response = authClient.getUser(doctorId);
        UserSummaryResponse doctor = response == null ? null : response.getData();
        if (doctor == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (doctor.role() != RoleCode.VETERINARIAN || !"ACTIVE".equals(doctor.status())) {
            throw new AppException(ErrorCode.INVALID_DOCTOR);
        }
        return doctor;
    }

    private Actor currentActor() {
        return new Actor(SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
