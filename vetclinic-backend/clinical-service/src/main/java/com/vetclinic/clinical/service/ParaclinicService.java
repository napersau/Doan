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

    /** UC-25: nhập chỉ số xét nghiệm, tự đối chiếu khoảng tham chiếu theo loài và snapshot lại (BR-23, BR-24). */
    ServiceDetailResponse submitLabResults(Long detailId, LabResultRequest request);

    /** UC-26: mô tả, kết luận hình thái học và tải ảnh. Cần ít nhất một ảnh hợp lệ. */
    ServiceDetailResponse submitImagingResult(Long detailId, String description, String conclusion,
                                              List<MultipartFile> files);

    AttachmentDownload downloadAttachment(Long attachmentId);
}
