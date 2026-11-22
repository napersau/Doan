package com.vetclinic.reporting.event;

import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import com.vetclinic.reporting.service.ReportingProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 8 listener dựng mô hình đọc báo cáo, mỗi listener một queue + DLQ ({@link ReportingRabbitConfig}). Mỗi listener bọc việc xử lý trong
 * {@link IdempotentConsumer}: dòng {@code processed_events}, các dòng fact và bảng tổng hợp cùng một giao dịch; nhận trùng (eventId + tên
 * listener) thì bỏ qua; lỗi thì RabbitMQ thử lại 3 lần (1s, 2s, 4s) rồi chuyển vào DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportingListeners {

    public static final String VISIT_CHECKED_IN = "reporting.visit-checked-in";
    public static final String VISIT_COMPLETED = "reporting.visit-completed";
    public static final String VISIT_CANCELLED = "reporting.visit-cancelled";
    public static final String INVOICE_SERVICE_PAID = "reporting.invoice-service-paid";
    public static final String INVOICE_SERVICE_REFUNDED = "reporting.invoice-service-refunded";
    public static final String MEDICINE_INVOICE_PAID = "reporting.medicine-invoice-paid";
    public static final String USER_PROFILE_UPDATED = "reporting.user-profile-updated";
    public static final String PET_UPDATED = "reporting.pet-updated";

    private final ReportingProjectionService projection;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_CHECKED_IN_QUEUE)
    public void onVisitCheckedIn(VisitCheckedInEvent event) {
        idempotentConsumer.run(event.eventId(), VISIT_CHECKED_IN, () -> projection.onVisitCheckedIn(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_COMPLETED_QUEUE)
    public void onVisitCompleted(VisitCompletedEvent event) {
        idempotentConsumer.run(event.eventId(), VISIT_COMPLETED, () -> projection.onVisitCompleted(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.VISIT_CANCELLED_QUEUE)
    public void onVisitCancelled(VisitCancelledEvent event) {
        idempotentConsumer.run(event.eventId(), VISIT_CANCELLED, () -> projection.onVisitCancelled(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.INVOICE_SERVICE_PAID_QUEUE)
    public void onServiceInvoicePaid(ServiceInvoicePaidEvent event) {
        idempotentConsumer.run(event.eventId(), INVOICE_SERVICE_PAID, () -> projection.onServiceInvoicePaid(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.INVOICE_SERVICE_REFUNDED_QUEUE)
    public void onServiceInvoiceRefunded(ServiceInvoiceRefundedEvent event) {
        idempotentConsumer.run(event.eventId(), INVOICE_SERVICE_REFUNDED, () -> projection.onServiceInvoiceRefunded(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.MEDICINE_INVOICE_PAID_QUEUE)
    public void onMedicineInvoicePaid(MedicineInvoicePaidEvent event) {
        idempotentConsumer.run(event.eventId(), MEDICINE_INVOICE_PAID, () -> projection.onMedicineInvoicePaid(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), USER_PROFILE_UPDATED, () -> projection.onUserProfileUpdated(event));
    }

    @RabbitListener(queues = ReportingRabbitConfig.PET_UPDATED_QUEUE)
    public void onPetUpdated(PetUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), PET_UPDATED, () -> projection.onPetUpdated(event));
    }
}
