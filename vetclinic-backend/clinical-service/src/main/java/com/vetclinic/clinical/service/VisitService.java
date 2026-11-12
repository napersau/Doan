package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.ClinicalInfoRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.response.BillableServiceResponse;
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;

import java.util.Collection;
import java.util.List;

/** Vòng đời một đợt khám ngoại trú hai pha (SM-01). */
public interface VisitService {

    /** UC-19: tiếp nhận khách có hẹn (appointmentId) hoặc vãng lai (petId). Tạo đợt khám WAITING. */
    VisitResponse checkIn(CheckInRequest request);

    /** Danh sách đợt khám có lọc (bảng điều phối): trạng thái, ngày check-in theo giờ phòng khám, bác sĩ, thú cưng, mã ca. Mới nhất trước. */
    com.vetclinic.common.dto.PageResponse<VisitResponse> search(com.vetclinic.clinical.constant.VisitStatus status,
            java.time.LocalDate date, Long doctorId, Long petId, String keyword, int page, int size);

    VisitResponse getById(Long id);

    List<VisitHistoryResponse> getHistory(Long id);

    /**
     * UC-21, FR-24: Lễ tân xếp ca WAITING vào hàng đợi của Bác sĩ ACTIVE BẤT KỲ, bất cứ lúc nào, KHÔNG cần Bác sĩ rảnh. Gọi lại với
     * Bác sĩ khác = chuyển hàng đợi. Giữ nguyên queue_order (ca đến trước vẫn đứng trước), không đổi trạng thái nên không ghi lịch sử.
     * Chống xung đột (FR-25): Bác sĩ vừa bấm "Bắt đầu khám" ca này thì ca không còn WAITING nên bị từ chối.
     */
    VisitResponse assignDoctor(Long id, AssignDoctorRequest request);

    /** UC-21 Luồng 5c: gỡ phân công (doctor_id = NULL), ca về danh sách chờ phân công; giữ nguyên queue_order. */
    VisitResponse unassignDoctor(Long id);

    /**
     * T02, UC-22: Bác sĩ bắt đầu khám giai đoạn 1 (WAITING -> IN_PROGRESS). Chỉ Bác sĩ được gán (CLIN_1311) và không có ca
     * IN_PROGRESS khác (CLIN_1302). Tự tạo MỘT dòng dịch vụ khám (EXAM_GEN, IN_PROGRESS) kèm hóa đơn PENDING. Sinh hiệu tùy chọn.
     */
    VisitResponse startExam(Long id, StartExamRequest request);

    /** Cập nhật sinh hiệu / ghi chú lâm sàng khi ca đang khám (IN_PROGRESS) và chưa bị khóa. */
    VisitResponse updateClinicalInfo(Long id, ClinicalInfoRequest request);

    /**
     * T04, UC-23: chỉ định cận lâm sàng (chỉ LABORATORY / IMAGING). Yêu cầu đã có cân nặng và thân nhiệt (CLIN_1308), không còn
     * dịch vụ tại phòng dang dở (CLIN_1309), và đang ở Giai đoạn 1. Mỗi dịch vụ: dòng chỉ định PENDING (tự vào hàng đợi Lab / CĐHA)
     * + hóa đơn PENDING, cùng giao dịch; dịch vụ khám hoàn tất; ca IN_PROGRESS -> WAITING_RESULTS, Bác sĩ được giải phóng.
     */
    VisitResponse orderParaclinical(Long id, ParaclinicalOrderRequest request);

    /**
     * FR-30: chỉ định dịch vụ tại phòng (tiêm, tẩy giun, thủ thuật...). Mỗi dịch vụ một dòng PENDING + một hóa đơn PENDING; ca vẫn
     * IN_PROGRESS. Bác sĩ bấm "Đã thực hiện" (POST /service-details/{id}/perform) để hoàn tất.
     */
    VisitResponse orderInRoomServices(Long id, OrderServicesRequest request);

    /** UC-33: chốt chuyên môn, khóa hồ sơ. IN_PROGRESS -> WAITING_PAYMENT, phát sự kiện để billing lập hóa đơn. */
    VisitResponse closeVisit(Long id, CloseVisitRequest request);

    /**
     * Mở lại đợt khám đã chốt để điều chỉnh chuyên môn (T12, "người có thẩm quyền" = Admin): WAITING_PAYMENT -> IN_PROGRESS,
     * bỏ khóa hồ sơ, hủy hóa đơn PENDING bên billing. Chỉ khi chưa thu tiền và bác sĩ phụ trách đang không BUSY. Chốt lại bằng
     * close-visit thì hóa đơn được dựng lại với nội dung mới.
     */
    VisitResponse reopen(Long id, ReasonRequest request);

    /** EX-11: khách bỏ về hẳn không đọc kết quả. RESULTS_READY -> COMPLETED, ghi nợ viện phí. */
    VisitResponse abandon(Long id);

    /** EX-03 / FR-26 / FR-34 / FR-45: hủy đợt khám. */
    VisitResponse cancel(Long id, ReasonRequest request);

    /** Danh sách ca của Bác sĩ đang đăng nhập: chờ vào phòng, đang khám, chờ tái khám. */
    List<VisitResponse> myVisits();

    // ------------------------------------------------------------------ không cần người dùng đăng nhập

    /** Cho billing-pharmacy-service lập hóa đơn: chỉ dịch vụ COMPLETED. */
    List<BillableServiceResponse> billableServices(Long visitId);

    /** Cho billing-pharmacy-service: thông tin rút gọn của một đợt khám. */
    VisitSummaryResponse summary(Long visitId);

    /** Như summary nhưng theo lô; id không tồn tại thì bị bỏ qua (dùng làm giàu danh sách). */
    List<VisitSummaryResponse> summaries(Collection<Long> visitIds);
}
