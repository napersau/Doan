package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.dto.response.DoctorStatusResponse;

import java.util.List;

/**
 * Trạng thái phục vụ tức thời của Bác sĩ, TÍNH ĐỘNG từ các ca khám, không lưu cột trạng thái (state machine SM-02).
 * v3.0 chỉ còn hai giá trị; KHÔNG còn "Khóa ưu tiên" (AVAILABLE_PRIORITY_CALLBACK đã bỏ):
 * <ul>
 *   <li>BUSY: đang có ca IN_PROGRESS, hoặc đã được phân công một ca WAITING (chưa vào phòng) - ĐIỀU KIỆN VẾ SAU CHỈ TẠM GIỮ
 *       của v2 cho tới bước 3.B2 (khi đó BUSY chỉ còn "có ca IN_PROGRESS" và Lễ tân được xếp ca bất cứ lúc nào).</li>
 *   <li>AVAILABLE: rảnh hoàn toàn.</li>
 * </ul>
 */
public interface DoctorStatusService {

    DoctorStatus statusOf(Long doctorId);

    /** Bảng điều phối của Lễ tân: mọi Bác sĩ đang ACTIVE kèm số phòng và trạng thái. */
    List<DoctorStatusResponse> board();
}
