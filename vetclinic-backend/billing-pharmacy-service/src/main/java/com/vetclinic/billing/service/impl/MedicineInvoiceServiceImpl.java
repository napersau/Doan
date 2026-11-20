package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.InventoryTransactionType;
import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.constant.VisitStatus;
import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.dto.response.VisitSummaryResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.MedicineInvoiceItem;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.entity.PrescriptionItem;
import com.vetclinic.billing.repository.InventoryTransactionRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.PrescriptionRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.MedicineInvoiceAmounts;
import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.billing.service.VisitLookup;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Không đánh @Transactional ở mức class: các hàm vừa gọi Feign (ngoài transaction) vừa ghi DB (trong transaction) dùng
 * TransactionTemplate. Tiền / kho nằm trọn trong service này (quy chuẩn): thu tiền + trừ kho + nhật ký kho là MỘT giao dịch.
 */
@Service
public class MedicineInvoiceServiceImpl implements MedicineInvoiceService {

    /** aggregate_type của các dòng outbox gắn với một hóa đơn thuốc (aggregate_id = id hóa đơn). */
    private static final String AGGREGATE_INVOICE = "MEDICINE_INVOICE";

    private final MedicineInvoiceRepository invoiceRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicineRepository medicineRepository;
    private final InventoryTransactionRepository inventoryRepository;
    private final VisitLookup visitLookup;
    private final BillingAssembler assembler;
    private final StockAlertPublisher stockAlerts;
    private final OutboxWriter outboxWriter;
    private final EntityManager entityManager;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public MedicineInvoiceServiceImpl(MedicineInvoiceRepository invoiceRepository,
                                      PrescriptionRepository prescriptionRepository,
                                      MedicineRepository medicineRepository,
                                      InventoryTransactionRepository inventoryRepository, VisitLookup visitLookup,
                                      BillingAssembler assembler, StockAlertPublisher stockAlerts,
                                      OutboxWriter outboxWriter, EntityManager entityManager,
                                      Clock clock, PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.medicineRepository = medicineRepository;
        this.inventoryRepository = inventoryRepository;
        this.visitLookup = visitLookup;
        this.assembler = assembler;
        this.stockAlerts = stockAlerts;
        this.outboxWriter = outboxWriter;
        this.entityManager = entityManager;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ================================================================== lập hóa đơn

    @Override
    public MedicineInvoiceResponse create(Long prescriptionId, MedicineInvoiceRequest request) {
        Long visitId = readTx.execute(s -> prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND)).getVisitId());
        VisitSummaryResponse visit = visitLookup.require(visitId);   // Feign ngoài transaction
        if (visit.status() != VisitStatus.WAITING_PAYMENT && visit.status() != VisitStatus.COMPLETED) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, "Đợt khám chưa được Bác sĩ chốt chuyên môn");
        }
        MedicineInvoiceResponse created = tx.execute(s -> persistNew(prescriptionId, request));
        return assembler.withVisit(created, visit);
    }

    private MedicineInvoiceResponse persistNew(Long prescriptionId, MedicineInvoiceRequest request) {
        // Khóa dòng đơn thuốc: hai lần lập đồng thời (hoặc lập chen với sửa đơn) được xếp hàng; prescription_id UNIQUE là chốt chặn cuối
        Prescription prescription = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (invoiceRepository.findByPrescriptionId(prescriptionId).isPresent()) {
            throw new AppException(ErrorCode.PRESCRIPTION_ALREADY_INVOICED);
        }

        Map<Long, PrescriptionItem> prescribed = prescription.getItems().stream()
                .collect(Collectors.toMap(PrescriptionItem::getId, Function.identity()));
        MedicineInvoice invoice = MedicineInvoice.builder()
                .invoiceCode("TMP-" + UUID.randomUUID())   // thay bằng mã thật ngay khi có id, cùng transaction
                .prescription(prescription).build();
        Set<Long> seen = new HashSet<>();
        for (MedicineInvoiceRequest.Line line : request.items()) {
            PrescriptionItem item = prescribed.get(line.prescriptionItemId());
            if (item == null) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Dòng thuốc #" + line.prescriptionItemId() + " không thuộc đơn thuốc này");
            }
            if (!seen.add(item.getId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Dòng thuốc #" + item.getId() + " xuất hiện nhiều lần, hãy gộp thành một dòng");
            }
            if (line.quantity() < 1) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số lượng mua phải lớn hơn 0");
            }
            Medicine medicine = item.getMedicine();
            if (!medicine.isActive()) {
                throw new AppException(ErrorCode.MEDICINE_NOT_FOUND,
                        "Thuốc [" + medicine.getName() + "] đã ngừng kinh doanh, không thể bán");
            }
            assertWithinStock(medicine, line.quantity());   // số lượng kê chỉ để tham khảo: mua nhiều / ít / bằng đều được, nhưng không vượt tồn kho
            invoice.addItem(MedicineInvoiceItem.builder().prescriptionItem(item).quantity(line.quantity())
                    .unitPrice(medicine.getSalePrice()).vatRate(medicine.getVatRate()).build());   // snapshot giá / VAT (BR-26)
        }
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO
                : MedicineInvoiceAmounts.money(request.discountAmount());
        assertDiscountWithinInvoice(discount, invoice.getItems());
        invoice.setDiscountAmount(discount);

        MedicineInvoice saved;
        try {
            saved = invoiceRepository.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.PRESCRIPTION_ALREADY_INVOICED);
        }
        saved.setInvoiceCode(invoiceCode(saved.getId()));
        return assembler.toResponse(invoiceRepository.saveAndFlush(saved));
    }

    /** MINV-yyyy-NNNN: năm lập + id nên luôn duy nhất, không cần sinh số rồi thử lại khi trùng (không dùng count + 1). */
    private String invoiceCode(Long id) {
        return "MINV-" + LocalDate.now(clock).getYear() + "-" + String.format("%04d", id);
    }

    // ================================================================== sửa / hủy

    @Override
    public MedicineInvoiceResponse updateItems(Long id, MedicineInvoiceRequest request) {
        MedicineInvoiceResponse updated = tx.execute(s -> doUpdateItems(id, request));
        return enrich(updated);
    }

    private MedicineInvoiceResponse doUpdateItems(Long id, MedicineInvoiceRequest request) {
        MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        assertPending(invoice);

        Map<Long, MedicineInvoiceItem> lines = invoice.getItems().stream()
                .collect(Collectors.toMap(i -> i.getPrescriptionItem().getId(), Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (MedicineInvoiceRequest.Line line : request.items()) {
            MedicineInvoiceItem item = lines.get(line.prescriptionItemId());
            if (item == null) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Dòng thuốc #" + line.prescriptionItemId() + " không có trong hóa đơn này");
            }
            if (!seen.add(line.prescriptionItemId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Dòng thuốc #" + line.prescriptionItemId() + " xuất hiện nhiều lần, hãy gộp thành một dòng");
            }
            if (line.quantity() == 0) {
                invoice.getItems().remove(item);   // quantity = 0: bỏ dòng khỏi hóa đơn
            } else {
                assertWithinStock(item.getPrescriptionItem().getMedicine(), line.quantity());
                item.setQuantity(line.quantity());   // giữ nguyên snapshot unit_price / vat_rate
            }
        }
        if (invoice.getItems().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Hóa đơn phải còn ít nhất một dòng thuốc; nếu khách không mua nữa hãy hủy hóa đơn");
        }
        BigDecimal discount = request.discountAmount() == null ? invoice.getDiscountAmount()
                : MedicineInvoiceAmounts.money(request.discountAmount());
        assertDiscountWithinInvoice(discount, invoice.getItems());   // kiểm tra lại cả khi chỉ đổi số lượng (EX-14)
        invoice.setDiscountAmount(discount);
        return assembler.toResponse(invoiceRepository.saveAndFlush(invoice));
    }

    @Override
    public MedicineInvoiceResponse cancel(Long id) {
        MedicineInvoiceResponse cancelled = tx.execute(s -> {
            MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
            assertPending(invoice);
            invoice.setPaymentStatus(PaymentStatus.CANCELLED);   // kho không đổi (chưa trừ); hủy là kết thúc
            return assembler.toResponse(invoiceRepository.saveAndFlush(invoice));
        });
        return enrich(cancelled);
    }

    // ================================================================== thu tiền + trừ kho

    @Override
    public MedicineInvoiceResponse confirmPayment(Long id) {
        Long cashierId = SecurityUtils.getCurrentUserId();
        MedicineInvoiceResponse paid = tx.execute(s -> doConfirmPayment(id, cashierId));
        return enrich(paid);
    }

    private MedicineInvoiceResponse doConfirmPayment(Long id, Long cashierId) {
        // Khóa dòng hóa đơn: hai thu ngân bấm cùng lúc thì người sau chờ, rồi thấy PAID -> BILL_1403 (EX-16); @Version là lớp bảo vệ thứ hai
        MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        assertPending(invoice);

        // Sắp theo id thuốc để hai giao dịch bán chồng nhau luôn khóa theo cùng thứ tự, không deadlock
        List<MedicineInvoiceItem> lines = invoice.getItems().stream()
                .sorted(Comparator.comparing((MedicineInvoiceItem i) -> i.getPrescriptionItem().getMedicine().getId())).toList();
        List<Medicine> touched = new ArrayList<>();
        for (MedicineInvoiceItem line : lines) {
            Medicine medicine = line.getPrescriptionItem().getMedicine();
            if (medicineRepository.deductStock(medicine.getId(), line.getQuantity()) == 0) {
                // UPDATE ... WHERE stock_quantity >= :qty ảnh hưởng 0 dòng = thiếu hàng: rollback TOÀN BỘ (kể cả các dòng đã trừ)
                entityManager.refresh(medicine);
                throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không thể bán: thuốc [" + medicine.getName()
                        + "] trong kho không đủ số lượng (còn " + medicine.getStockQuantity() + ", cần " + line.getQuantity()
                        + "). Hãy sửa số lượng trong hóa đơn hoặc báo kho nhập thêm hàng.");
            }
            entityManager.refresh(medicine);   // tồn kho thật sau khi trừ (không tin giá trị cũ trong entity)
            inventoryRepository.save(InventoryTransaction.builder()
                    .medicineId(medicine.getId())
                    .transactionType(InventoryTransactionType.EXPORT_SALE)
                    .quantity(-line.getQuantity())   // quantity có dấu: xuất là số âm
                    .medicineInvoiceId(invoice.getId())
                    .createdBy(cashierId)
                    .build());
            touched.add(medicine);
        }

        invoice.setPaymentStatus(PaymentStatus.PAID);
        invoice.setCashierId(cashierId);
        invoice.setPaidAt(clock.instant());
        MedicineInvoice saved = invoiceRepository.saveAndFlush(invoice);   // kiểm tra @Version ngay trong giao dịch

        // Transactional Outbox: ghi cùng giao dịch trừ kho + thu tiền; OutboxRelay gửi sang RabbitMQ sau khi commit
        outboxWriter.write(AGGREGATE_INVOICE, saved.getId(), new MedicineInvoicePaidEvent(UuidV7.generate(clock), clock.instant(), cashierId,
                saved.getId(), saved.getInvoiceCode(), saved.getPrescription().getId(), saved.getPrescription().getVisitId(),
                MedicineInvoiceAmounts.payable(saved.getItems(), saved.getDiscountAmount())));
        touched.forEach(medicine -> stockAlerts.publishIfLow(medicine, cashierId));
        return assembler.toResponse(saved);
    }

    // ================================================================== tra cứu

    @Override
    public MedicineInvoiceResponse getById(Long id) {
        MedicineInvoiceResponse response = readTx.execute(s -> assembler.toResponse(invoiceRepository.findWithItemsById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND))));
        return enrich(response);
    }

    // ================================================================== helper

    private MedicineInvoiceResponse enrich(MedicineInvoiceResponse response) {
        return assembler.withVisit(response, visitLookup.tryFind(response.visitId()));
    }

    private static void assertPending(MedicineInvoice invoice) {
        if (invoice.getPaymentStatus() == PaymentStatus.PAID) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_CANCELLED);
        }
    }

    /**
     * Số lượng bác sĩ kê chỉ để tham khảo (khách có thể mua nhiều hơn / ít hơn / bằng), nhưng không được bán quá tồn kho hiện tại.
     * Kiểm tra sớm ở bước lập / sửa hóa đơn; lúc thu tiền vẫn kiểm tra lại bằng UPDATE nguyên tử (kho có thể đổi giữa hai bước, EX-15).
     * Mức tồn tối thiểu ({@code min_stock_alert}) chỉ là ngưỡng cảnh báo cho Admin, không chặn bán.
     */
    private static void assertWithinStock(Medicine medicine, int quantity) {
        if (quantity > medicine.getStockQuantity()) {
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không thể bán thuốc [" + medicine.getName() + "] với số lượng "
                    + quantity + ": trong kho chỉ còn " + medicine.getStockQuantity() + " " + medicine.getUnit());
        }
    }

    /** Giảm giá không được vượt tổng tiền thuốc của chính hóa đơn này (BILL_1402, EX-14). */
    private static void assertDiscountWithinInvoice(BigDecimal discount, List<MedicineInvoiceItem> items) {
        if (discount.compareTo(MedicineInvoiceAmounts.gross(items)) > 0) {
            throw new AppException(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT);
        }
    }
}
