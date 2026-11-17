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
     * Hàng đợi của một phòng (17/11/2026): các PHIẾU XẾP HÀNG của phòng đang chờ / đang làm, thứ tự priority DESC, issued_at, id;
     * mỗi phiếu bung ra các dịch vụ của nó. Dịch vụ chưa nộp tiền không có phiếu nên không xuất hiện. Gọi số và "Bắt đầu" thao tác
     * trên phiếu ({@link QueueTicketService}).
     */
    List<QueueItemResponse> queue(ServiceCategory category);

    /** Phòng Lab: ghi nhận đã lấy mẫu (action SAMPLE_COLLECTED, trạng thái vẫn IN_PROGRESS) để bên CĐHA thấy huy hiệu liên phòng. */
    ServiceDetailResponse sampleCollected(Long detailId);

    /**
     * Form nhập kết quả xét nghiệm của một chỉ định: các chỉ số thuộc dịch vụ đó (dịch vụ chưa cấu hình thì mọi chỉ số),
     * kèm khoảng tham chiếu CỦA LOÀI thú cưng trong đợt khám.
     */
    List<LabTestItemResponse> labForm(Long detailId);

    /**
     * FR-30: Bác sĩ bấm "Đã thực hiện" cho dịch vụ tại phòng (PENDING -> COMPLETED; ghi lịch sử START_PROCESSING rồi COMPLETED).
     * Chỉ Bác sĩ được gán, khi ca đang khám (IN_PROGRESS) và chưa khóa.
     */
    ServiceDetailResponse perform(Long detailId);

    /**
     * EX-08 (17/11/2026): hủy một dịch vụ. Hóa đơn chưa thu -> CANCELLED; đã thu và {@code reasonType = CLINIC} (phòng khám không
     * thực hiện được XN / CĐHA) -> REFUNDED; đã thu và CUSTOMER (khách tự hủy) -> giữ PAID. {@code reasonType} null: Bác sĩ = CLINIC,
     * kỹ thuật viên = CUSTOMER.
     */
    ServiceDetailResponse cancel(Long detailId, String reason, String reasonType);

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
