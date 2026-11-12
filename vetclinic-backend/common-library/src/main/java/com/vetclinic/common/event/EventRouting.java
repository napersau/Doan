package com.vetclinic.common.event;

/**
 * Danh mục duy nhất về exchange / routing key (CLAUDE.md §5.3).
 * Tên queue của consumer theo mẫu: &lt;service-name&gt;.&lt;routing-key&gt;, ví dụ "clinical-service.payment.completed".
 */
public final class EventRouting {

    private EventRouting() {
    }

    public static final String EXCHANGE = "clinic.events";
    public static final String DEAD_LETTER_EXCHANGE = "clinic.events.dlx";
    public static final String DEAD_LETTER_SUFFIX = ".dlq";

    // ===== Danh mục chuẩn v3.0 (THIET_KE_KIEN_TRUC_MICROSERVICES.md mục 5.2) =====
    public static final String APPOINTMENT_BOOKED = "appointment.booked";
    public static final String APPOINTMENT_STATUS_CHANGED = "appointment.status_changed";
    public static final String APPOINTMENT_NO_SHOW = "appointment.no_show";
    public static final String VISIT_CHECKED_IN = "visit.checked_in";
    public static final String VISIT_PATIENT_CALLED = "visit.patient_called";
    public static final String VISIT_RESULTS_READY = "visit.results_ready";
    public static final String VISIT_COMPLETED = "visit.completed";
    public static final String PHARMACY_MEDICINE_INVOICE_PAID = "pharmacy.medicine_invoice_paid";
    public static final String PHARMACY_LOW_STOCK = "pharmacy.low_stock";

    // ===== [v2 – TẠM GIỮ, XÓA ở bước 7.1] chỉ để code v2 còn biên dịch tới khi từng service chuyển sang danh mục v3.0 =====
    public static final String APPOINTMENT_CREATED = "appointment.created";
    public static final String DOCTOR_PRIORITY_CALLBACK = "doctor.priority_callback";
    public static final String VISIT_CLOSED_TO_PAYMENT = "visit.closed_to_payment";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String STOCK_LOW_ALERT = "stock.low_alert";

    public static String queueName(String serviceName, String routingKey) {
        return serviceName + "." + routingKey;
    }
}
