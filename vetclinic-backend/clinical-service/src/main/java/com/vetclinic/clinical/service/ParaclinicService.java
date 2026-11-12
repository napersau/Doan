package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.constant.ServiceCategory;
import com.vetclinic.clinical.dto.request.LabResultRequest;
import com.vetclinic.clinical.dto.response.QueueItemResponse;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Hai hàng đợi cận lâm sàng độc lập: phòng Xét nghiệm (LABORATORY) và phòng Chẩn đoán hình ảnh (IMAGING). */
public interface ParaclinicService {

    record AttachmentDownload(Resource resource, String contentType, String fileName) {
    }

    /**
     * Hàng đợi của một phòng (v3.0): ORDER BY (cấp cứu AND skip_count = 0) DESC, queue_order ASC. Không còn tab "ca tạm hoãn" và
     * "ưu tiên kế tiếp": khách vắng thì "Đẩy lùi 1 bậc" (bước 3.B6) và vẫn nằm trong hàng đợi.
     */
    List<QueueItemResponse> queue(ServiceCategory category);

    /**
     * BR-34 (Q3): kỹ thuật viên của phòng bấm "Gọi số tiếp theo": chọn chỉ định {@code PENDING} đầu hàng đợi của phòng
     * (QUEUE_EMPTY nếu trống) và phát PatientCalledEvent (station LAB/IMAGING). Chỉ định CHƯA đổi trạng thái: khách vào phòng,
     * kỹ thuật viên bấm "Bắt đầu" mới chuyển PENDING -> IN_PROGRESS.
     */
    QueueItemResponse callNext(ServiceCategory category);

    /**
     * BR-34 (Q4): "Đẩy lùi 1 bậc" khi gọi mà khách vắng. skip_count + 1, queue_order mới theo {@link QueueOrders#afterPushBack};
     * chỉ định giữ {@code PENDING}, ghi lịch sử {@code action = SKIPPED}; gọi ngay người kế tiếp. Trả về chỉ định vừa được gọi
     * (hoặc chính chỉ định này nếu không còn ai phía sau).
     */
    QueueItemResponse skip(Long detailId);

    /** Phòng Lab: ghi nhận đã lấy mẫu (action SAMPLE_COLLECTED, trạng thái vẫn IN_PROGRESS) để bên CĐHA thấy huy hiệu liên phòng. */
    ServiceDetailResponse sampleCollected(Long detailId);

    /**
     * Form nhập kết quả xét nghiệm của một chỉ định: các chỉ số thuộc dịch vụ đó (dịch vụ chưa cấu hình thì mọi chỉ số),
     * kèm khoảng tham chiếu CỦA LOÀI thú cưng trong đợt khám.
     */
    List<LabTestItemResponse> labForm(Long detailId);

    /** UC-25/26: thú cưng đã vào phòng, bắt đầu thực hiện. PENDING -> IN_PROGRESS. */
    ServiceDetailResponse start(Long detailId);

    /**
     * FR-30: Bác sĩ bấm "Đã thực hiện" cho dịch vụ tại phòng (PENDING -> COMPLETED; ghi lịch sử START_PROCESSING rồi COMPLETED).
     * Chỉ Bác sĩ được gán, khi ca đang khám (IN_PROGRESS) và chưa khóa.
     */
    ServiceDetailResponse perform(Long detailId);

    /** EX-08: khách xin hủy giữa chừng, hoặc thiết bị gặp sự cố. Dịch vụ hủy không bao giờ tính tiền. */
    ServiceDetailResponse cancel(Long detailId, String reason);

    /** GET /lab-results/{detailId}: kết quả đã nhập của một chỉ định xét nghiệm, kèm nhãn đánh giá theo khoảng đã snapshot. */
    List<com.vetclinic.clinical.dto.response.LabResultResponse> labResults(Long detailId);

    /**
     * UC-25: nhập chỉ số xét nghiệm, tự đối chiếu khoảng tham chiếu theo loài và snapshot lại (BR-23, BR-24). Dịch vụ đã cấu hình
     * gói chỉ số ({@code service_lab_test_items}) thì PHẢI nhập đủ mọi chỉ số của gói (thiếu -> SYS_1001).
     */
    ServiceDetailResponse submitLabResults(Long detailId, LabResultRequest request);

    /** UC-26: mô tả, kết luận hình thái học và tải ảnh. Cần ít nhất một ảnh hợp lệ. */
    ServiceDetailResponse submitImagingResult(Long detailId, String description, String conclusion,
                                              List<MultipartFile> files);

    AttachmentDownload downloadAttachment(Long attachmentId);
}
