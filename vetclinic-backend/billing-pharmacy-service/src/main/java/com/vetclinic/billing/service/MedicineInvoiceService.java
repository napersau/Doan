package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;

/**
 * Hóa đơn thuốc (UC-36, SM-06). Chỉ phát sinh khi khách chọn mua tại phòng khám; mua ngoài thì không có hóa đơn.
 * Không bao giờ đổi trạng thái đợt khám.
 */
public interface MedicineInvoiceService {

    /**
     * Lập hóa đơn thuốc cho một đơn (khách có thể mua một phần). Đợt khám phải đang WAITING_PAYMENT / COMPLETED (BILL_1409);
     * mỗi đơn chỉ được một hóa đơn thuốc, kể cả hóa đơn đã hủy (PHARM_1407); số lượng bác sĩ kê chỉ để tham khảo, khách mua nhiều / ít / bằng đều được nhưng không vượt tồn kho (PHARM_1406);
     * snapshot giá bán và VAT hiện hành; giảm giá ≤ tổng tiền thuốc (BILL_1402). Trạng thái PENDING, mã MINV-YYYY-XXXX.
     */
    MedicineInvoiceResponse create(Long prescriptionId, MedicineInvoiceRequest request);

    MedicineInvoiceResponse getById(Long id);

    /**
     * Sửa hóa đơn khi còn PENDING (BILL_1403 nếu đã thu, BILL_1408 nếu đã hủy): quantity = 0 bỏ dòng, quantity ≤ tồn kho
     * (PHARM_1406), còn ít nhất một dòng, giữ nguyên snapshot giá, kiểm tra lại giảm giá (BILL_1402). Dùng để xử lý EX-15.
     */
    MedicineInvoiceResponse updateItems(Long id, MedicineInvoiceRequest request);

    /** Khách đổi ý: PENDING → CANCELLED, kho không đổi. Hủy là kết thúc: đơn này không lập lại hóa đơn thuốc. */
    MedicineInvoiceResponse cancel(Long id);

    /**
     * Thu tiền + trừ kho + ghi nhật ký kho EXPORT_SALE trong MỘT giao dịch (EX-15: thiếu kho ở bất kỳ dòng nào thì rollback toàn bộ,
     * hóa đơn giữ PENDING, PHARM_1406). Thu hai lần → BILL_1403 (EX-16). Sau commit phát MedicineInvoicePaidEvent và
     * MedicineLowStockEvent.
     */
    MedicineInvoiceResponse confirmPayment(Long id);
}
