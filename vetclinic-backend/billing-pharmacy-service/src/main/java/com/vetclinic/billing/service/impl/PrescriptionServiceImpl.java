package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.constant.VisitStatus;
import com.vetclinic.billing.dto.request.PrescriptionItemRequest;
import com.vetclinic.billing.dto.request.PrescriptionRequest;
import com.vetclinic.billing.dto.response.PrescriptionResponse;
import com.vetclinic.billing.dto.response.PrescriptionSummaryResponse;
import com.vetclinic.billing.dto.response.VisitSummaryResponse;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.entity.PrescriptionItem;
import com.vetclinic.billing.repository.MedicineInvoiceItemRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.PrescriptionRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.PrescriptionService;
import com.vetclinic.billing.service.VisitLookup;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Không đánh @Transactional ở mức class: các hàm vừa gọi Feign (ngoài transaction) vừa ghi DB (trong transaction) dùng
 * TransactionTemplate để ranh giới transaction tường minh (quy chuẩn: không gọi Feign trong @Transactional).
 */
@Service
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PrescriptionRepository prescriptionRepository;
    private final MedicineRepository medicineRepository;
    private final MedicineInvoiceRepository invoiceRepository;
    private final MedicineInvoiceItemRepository invoiceItemRepository;
    private final VisitLookup visitLookup;
    private final BillingAssembler assembler;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public PrescriptionServiceImpl(PrescriptionRepository prescriptionRepository, MedicineRepository medicineRepository,
                                   MedicineInvoiceRepository invoiceRepository,
                                   MedicineInvoiceItemRepository invoiceItemRepository, VisitLookup visitLookup,
                                   BillingAssembler assembler, PlatformTransactionManager transactionManager) {
        this.prescriptionRepository = prescriptionRepository;
        this.medicineRepository = medicineRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.visitLookup = visitLookup;
        this.assembler = assembler;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ================================================================== kê đơn

    @Override
    public PrescriptionResponse create(PrescriptionRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        if (request.visitId() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chọn đợt khám để kê đơn");
        }
        VisitSummaryResponse visit = visitLookup.require(request.visitId());
        assertDoctorMayPrescribe(visit, doctorId);
        PrescriptionResponse created = tx.execute(s -> persistNew(visit.id(), request));
        return assembler.withVisit(created, visit);
    }

    private PrescriptionResponse persistNew(Long visitId, PrescriptionRequest request) {
        if (prescriptionRepository.findWithItemsByVisitId(visitId).isPresent()) {
            throw new AppException(ErrorCode.PRESCRIPTION_ALREADY_EXISTS);
        }
        Prescription prescription = Prescription.builder().visitId(visitId)
                .doctorNotes(blankToNull(request.doctorNotes())).build();
        List<String> warnings = mergeItems(prescription, request.items());
        try {
            prescription = prescriptionRepository.saveAndFlush(prescription);
        } catch (DataIntegrityViolationException e) {
            // Hai lần kê đồng thời: visit_id UNIQUE là chốt chặn cuối (BR-18)
            throw new AppException(ErrorCode.PRESCRIPTION_ALREADY_EXISTS);
        }
        return assembler.toResponse(prescription, null, warnings);
    }

    @Override
    public PrescriptionResponse update(Long id, PrescriptionRequest request) {
        Long doctorId = SecurityUtils.getCurrentUserId();
        Prescription existing = prescriptionRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        VisitSummaryResponse visit = visitLookup.require(existing.getVisitId());
        assertDoctorMayPrescribe(visit, doctorId);
        PrescriptionResponse updated = tx.execute(s -> persistUpdate(id, request));
        return assembler.withVisit(updated, visit);
    }

    private PrescriptionResponse persistUpdate(Long id, PrescriptionRequest request) {
        // Khóa dòng đơn: lập hóa đơn thuốc cùng lúc cũng khóa dòng này nên hai việc được xếp hàng, không lọt đơn đã bán
        Prescription prescription = prescriptionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (invoiceRepository.existsByPrescriptionIdAndPaymentStatusNot(id, PaymentStatus.CANCELLED)) {
            throw new AppException(ErrorCode.PRESCRIPTION_LOCKED);
        }
        prescription.setDoctorNotes(blankToNull(request.doctorNotes()));
        List<String> warnings = mergeItems(prescription, request.items());
        return assembler.toResponse(prescriptionRepository.saveAndFlush(prescription), null, warnings);
    }

    /**
     * Đưa các dòng của đơn về đúng danh sách yêu cầu, ghép theo thuốc (mỗi thuốc một dòng): dòng đã có thì cập nhật tại chỗ (giữ id
     * để hóa đơn đã hủy còn tham chiếu được), dòng mới thì thêm, dòng không còn thì bỏ (không bỏ được nếu đã có hóa đơn thuốc, kể cả đã
     * hủy, tham chiếu tới nó). Thuốc phải đang kinh doanh. Kê vượt tồn kho chỉ cảnh báo (EX-13), không chặn: khách có thể mua ngoài.
     *
     * @return danh sách cảnh báo tồn kho
     */
    private List<String> mergeItems(Prescription prescription, List<PrescriptionItemRequest> requests) {
        Set<Long> ids = new HashSet<>();
        for (PrescriptionItemRequest r : requests) {
            if (!ids.add(r.medicineId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Thuốc #" + r.medicineId() + " xuất hiện nhiều lần trong đơn, hãy gộp thành một dòng");
            }
        }
        Map<Long, Medicine> medicines = medicineRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Medicine::getId, Function.identity()));
        Map<Long, PrescriptionItem> current = new LinkedHashMap<>();
        for (PrescriptionItem item : prescription.getItems()) {
            current.put(item.getMedicine().getId(), item);
        }

        List<String> warnings = new ArrayList<>();
        for (PrescriptionItemRequest r : requests) {
            Medicine medicine = medicines.get(r.medicineId());
            if (medicine == null || !medicine.isActive()) {
                throw new AppException(ErrorCode.MEDICINE_NOT_FOUND,
                        "Thuốc #" + r.medicineId() + " không có trong danh mục hoặc đã ngừng kinh doanh");
            }
            if (r.quantity() > medicine.getStockQuantity()) {
                warnings.add(medicine.getName() + ": tồn kho " + medicine.getStockQuantity() + " < số lượng kê "
                        + r.quantity());
            }
            PrescriptionItem item = current.remove(medicine.getId());
            if (item == null) {
                item = PrescriptionItem.builder().medicine(medicine).build();
                prescription.addItem(item);
            }
            item.setQuantity(r.quantity());
            item.setDosage(r.dosage().trim());
            item.setFrequency(r.frequency().trim());
            item.setDurationDays(r.durationDays());
            item.setInstructions(blankToNull(r.instructions()));
        }
        for (PrescriptionItem removed : current.values()) {
            if (removed.getId() != null && invoiceItemRepository.existsByPrescriptionItemId(removed.getId())) {
                throw new AppException(ErrorCode.PRESCRIPTION_LOCKED,
                        "Thuốc [" + removed.getMedicine().getName() + "] đã nằm trong hóa đơn thuốc, không thể bỏ khỏi đơn");
            }
            prescription.getItems().remove(removed);
        }
        return warnings;
    }

    private static void assertDoctorMayPrescribe(VisitSummaryResponse visit, Long doctorId) {
        if (!doctorId.equals(visit.doctorId())) {
            throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);
        }
        if (visit.status() != VisitStatus.IN_PROGRESS) {
            throw new AppException(ErrorCode.PRESCRIPTION_LOCKED);
        }
    }

    // ================================================================== tra cứu

    @Override
    public PrescriptionResponse getById(Long id) {
        PrescriptionResponse response = readTx.execute(s -> {
            Prescription prescription = prescriptionRepository.findWithItemsById(id)
                    .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
            return assembler.toResponse(prescription, invoiceRepository.findByPrescriptionId(id).orElse(null), List.of());
        });
        return authorizeAndEnrich(response);
    }

    @Override
    public PrescriptionResponse getByVisit(Long visitId) {
        PrescriptionResponse response = readTx.execute(s -> {
            Prescription prescription = prescriptionRepository.findWithItemsByVisitId(visitId)
                    .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
            MedicineInvoice invoice = invoiceRepository.findByPrescriptionId(prescription.getId()).orElse(null);
            return assembler.toResponse(prescription, invoice, List.of());
        });
        return authorizeAndEnrich(response);
    }

    @Override
    public PrescriptionResponse print(Long id) {
        return getById(id);
    }

    @Override
    public List<PrescriptionResponse> findByVisits(Collection<Long> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) {
            return List.of();
        }
        return readTx.execute(s -> prescriptionRepository.findWithItemsByVisitIdIn(visitIds).stream()
                .map(p -> assembler.toResponse(p, null, List.of())).toList());
    }

    /** Chủ nuôi chỉ xem được đơn của thú cưng mình; người khác trả NOT_FOUND để không lộ sự tồn tại. */
    private PrescriptionResponse authorizeAndEnrich(PrescriptionResponse response) {
        boolean owner = SecurityUtils.getCurrentRole().filter(r -> r == RoleCode.PET_OWNER).isPresent();
        if (!owner) {
            return assembler.withVisit(response, visitLookup.tryFind(response.visitId()));
        }
        VisitSummaryResponse visit = visitLookup.require(response.visitId());
        if (!SecurityUtils.getCurrentUserId().equals(visit.ownerId())) {
            throw new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND);
        }
        return assembler.withVisit(response, visit);
    }

    @Override
    public PageResponse<PrescriptionSummaryResponse> search(Long visitId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("id").descending());
        Page<PrescriptionSummaryResponse> result = readTx.execute(s -> (visitId == null
                ? prescriptionRepository.findAll(pageable)
                : prescriptionRepository.findByVisitId(visitId, pageable)).map(assembler::toSummary));

        Map<Long, VisitSummaryResponse> visits = visitLookup.tryFindAll(
                result.getContent().stream().map(PrescriptionSummaryResponse::visitId).collect(Collectors.toSet()));
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), item -> assembler.withVisit(item, visits.get(item.visitId())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
