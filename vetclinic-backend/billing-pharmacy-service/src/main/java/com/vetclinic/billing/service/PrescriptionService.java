package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.request.PrescriptionRequest;
import com.vetclinic.billing.dto.response.PrescriptionResponse;
import com.vetclinic.billing.dto.response.PrescriptionSummaryResponse;
import com.vetclinic.common.dto.PageResponse;

import java.util.Collection;
import java.util.List;

/** Đơn thuốc điện tử (UC-31): chỉ định chuyên môn, không giá, không trạng thái. Bán thuốc nằm ở {@link MedicineInvoiceService}. */
public interface PrescriptionService {

    /**
     * Bác sĩ phụ trách kê đơn khi ca đang IN_PROGRESS (PHARM_1411 nếu đã khóa). Tối đa một đơn cho mỗi đợt khám. Kê vượt tồn kho
     * chỉ cảnh báo (EX-13): phản hồi kèm {@code stockWarnings}, không chặn lưu.
     */
    PrescriptionResponse create(PrescriptionRequest request);

    /**
     * Sửa đơn (thay nội dung các dòng). Chỉ khi ca còn IN_PROGRESS (kể cả sau "Mở lại hồ sơ") và đơn chưa có hóa đơn thuốc nào ở
     * trạng thái PENDING / PAID (PHARM_1411).
     */
    PrescriptionResponse update(Long id, PrescriptionRequest request);

    /** Chủ nuôi chỉ xem được đơn của thú cưng mình (UC-39); đơn của người khác trả PRESCRIPTION_NOT_FOUND. */
    PrescriptionResponse getById(Long id);

    PrescriptionResponse getByVisit(Long visitId);

    /** In đơn cho khách mua ngoài: cùng dữ liệu {@link #getById}, không tạo hóa đơn. */
    PrescriptionResponse print(Long id);

    /** Cho clinical-service dựng bệnh án trọn đời: đơn của nhiều đợt khám, theo lô. Không kiểm tra người xem. */
    List<PrescriptionResponse> findByVisits(Collection<Long> visitIds);

    PageResponse<PrescriptionSummaryResponse> search(Long visitId, int page, int size);
}
