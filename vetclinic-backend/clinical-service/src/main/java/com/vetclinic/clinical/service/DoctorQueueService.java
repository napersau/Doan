package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;

import java.util.List;

/**
 * Hàng đợi riêng của từng Bác sĩ (v3.0, thay hàng đợi sảnh chung + "Khóa ưu tiên"). Thứ tự mọi hàng đợi:
 * {@code ORDER BY (is_emergency AND skip_count = 0) DESC, queue_order ASC}.
 */
public interface DoctorQueueService {

    /** UC-20, FR-21: bảng điều phối của Lễ tân: danh sách chờ phân công + hàng đợi từng Bác sĩ + Bác sĩ được gợi ý. */
    QueueBoardResponse board();

    /** Hàng đợi của chính Bác sĩ đang đăng nhập (ca WAITING và RESULTS_READY), theo thứ tự gọi. */
    List<VisitResponse> myQueue();

    /**
     * Q3 "Gọi số tiếp theo": Bác sĩ KHÔNG có ca IN_PROGRESS (CLIN_1302) chọn ca đầu hàng đợi của mình (CLIN_1303 nếu trống), phát
     * PatientCalledEvent. Ca CHƯA đổi trạng thái: bệnh nhân vào phòng mới bấm "Bắt đầu khám" (T02) hoặc "Bắt đầu GĐ 2" (T07).
     */
    VisitResponse callNext();

    /**
     * Q4 "Đẩy lùi 1 bậc" (EX-10, FR-47): gọi mà khách vắng. Ca (WAITING hoặc RESULTS_READY) giữ nguyên trạng thái, skip_count + 1,
     * queue_order mới theo {@link QueueOrders#afterPushBack}; người kế tiếp được gọi ngay. Trả về người vừa được gọi tiếp, hoặc
     * chính ca đó nếu không còn ai phía sau. Không ghi lịch sử trạng thái vì trạng thái không đổi.
     */
    VisitResponse skip(Long visitId);

    /**
     * "Gọi" một ca cụ thể trong hàng đợi của chính Bác sĩ (không nhất thiết là người đứng đầu): chỉ phát thông báo, **không đổi trạng thái, không đổi thứ tự**. Gọi lại khi khách quay lại là gọi thêm một lần.
     */
    VisitResponse call(Long visitId);
}
