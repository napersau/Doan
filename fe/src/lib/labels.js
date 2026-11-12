// Nhãn tiếng Việt + tông màu cho các enum của backend. tone: green | blue | amber | red | violet | gray
export const VISIT_STATUS = {
  WAITING: { label: 'Chờ khám', tone: 'amber' },
  IN_PROGRESS: { label: 'Đang khám', tone: 'blue' },
  WAITING_RESULTS: { label: 'Chờ kết quả CLS', tone: 'violet' },
  RESULTS_READY: { label: 'Đã có kết quả', tone: 'green' },
  WAITING_PAYMENT: { label: 'Chờ thanh toán', tone: 'amber' },
  COMPLETED: { label: 'Hoàn tất', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
};
export const APPOINTMENT_STATUS = {
  PENDING: { label: 'Chờ xác nhận', tone: 'amber' },
  CONFIRMED: { label: 'Đã xác nhận', tone: 'blue' },
  CHECKED_IN: { label: 'Đã tiếp nhận', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
  NO_SHOW: { label: 'Vắng mặt', tone: 'red' },
};
export const BOOKING_CHANNEL = { ONLINE: 'Trực tuyến', PHONE: 'Điện thoại', WALK_IN: 'Tại quầy' };
export const SERVICE_DETAIL_STATUS = {
  PENDING: { label: 'Chờ thực hiện', tone: 'amber' },
  IN_PROGRESS: { label: 'Đang thực hiện', tone: 'blue' },
  COMPLETED: { label: 'Hoàn thành', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
};
// Trạng thái con của dịch vụ cận lâm sàng trong hàng đợi (SUY RA từ lịch sử chỉ định, không có cột riêng): QUEUED/SAMPLING/ANALYZING
export const QUEUE_STAGE = {
  QUEUED: { label: 'Đang chờ', tone: 'gray' },
  SAMPLING: { label: 'Đang lấy mẫu', tone: 'blue' },
  ANALYZING: { label: 'Đang phân tích', tone: 'violet' },
};
// Huy hiệu liên phòng trên hàng đợi CĐHA (EX-09): dịch vụ Lab của cùng ca
export const LAB_BADGE = {
  SAMPLING: { label: 'Lab đang lấy mẫu', tone: 'amber' },
  SAMPLE_COLLECTED: { label: 'Đã lấy mẫu, sẵn sàng chụp', tone: 'green' },
};
export const SERVICE_CATEGORY = {
  EXAMINATION: 'Khám tổng quát', VACCINATION: 'Tiêm phòng', DEWORMING: 'Tẩy giun', EXTERNAL_PARASITE: 'Ký sinh trùng ngoài da',
  INJECTION_INFUSION: 'Tiêm / truyền dịch', LABORATORY: 'Xét nghiệm', IMAGING: 'Chẩn đoán hình ảnh', SURGERY: 'Phẫu thuật', PROCEDURE: 'Thủ thuật',
};
export const DOCTOR_STATUS = {
  AVAILABLE: { label: 'Rảnh', tone: 'green' },
  BUSY: { label: 'Đang bận', tone: 'red' },
};
export const PAYMENT_STATUS = {
  PENDING: { label: 'Chờ thanh toán', tone: 'amber' },
  PAID: { label: 'Đã thanh toán', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
};
export const LAB_EVALUATION = {
  LOW: { label: 'Thấp', tone: 'amber' },
  NORMAL: { label: 'Bình thường', tone: 'green' },
  HIGH: { label: 'Cao', tone: 'red' },
};
export const INVENTORY_TYPE = { IMPORT: 'Nhập kho', EXPORT_SALE: 'Bán thuốc', ADJUSTMENT: 'Điều chỉnh' };
export const PET_GENDER = { MALE: 'Đực', FEMALE: 'Cái', UNKNOWN: 'Chưa rõ' };
export const ACCOUNT_STATUS = {
  ACTIVE: { label: 'Đang hoạt động', tone: 'green' },
  LOCKED: { label: 'Đã khóa', tone: 'red' },
  INACTIVE: { label: 'Ngừng hoạt động', tone: 'gray' },
};

// Mã lỗi nghiệp vụ (Quy chuẩn §5.1) -> câu hướng dẫn thân thiện hơn thông báo gốc của máy chủ. Chỉ các mã cần chỉ dẫn thao tác riêng.
export const ERROR_HINTS = {
  CLIN_1302: 'Bạn đang khám một ca khác. Hãy chốt hoặc kết thúc ca hiện tại trước khi gọi số tiếp theo.',
  CLIN_1303: 'Hàng đợi hiện không còn bệnh nhân nào.',
  CLIN_1306: 'Thú cưng này đang có một đợt khám chưa kết thúc.',
  CLIN_1307: 'Cần nhập chẩn đoán trước khi chốt ca.',
  CLIN_1308: 'Cần ghi cân nặng và thân nhiệt trước khi chỉ định cận lâm sàng.',
  CLIN_1309: 'Còn dịch vụ tại phòng chưa bấm “Đã thực hiện” hoặc chưa hủy.',
  CLIN_1310: 'Dịch vụ đã thu tiền nên không hủy được.',
  CLIN_1311: 'Ca này đã được phân công cho bác sĩ khác.',
  BILL_1402: 'Số tiền giảm giá vượt quá giá trị của hóa đơn này.',
  BILL_1403: 'Hóa đơn này đã được thanh toán.',
  BILL_1408: 'Hóa đơn này đã bị hủy.',
  BILL_1409: 'Bác sĩ chưa chốt chuyên môn nên chưa thu tiền / bán thuốc được.',
  PHARM_1406: 'Kho không đủ thuốc để bán. Hãy sửa số lượng trong hóa đơn hoặc báo kho nhập thêm.',
  PHARM_1407: 'Đơn thuốc này đã lập hóa đơn thuốc rồi (mỗi đơn chỉ một hóa đơn).',
  PHARM_1410: 'Số lượng mua vượt số lượng bác sĩ đã kê.',
  PHARM_1411: 'Đơn thuốc đã khóa (ca đã chốt hoặc đã có hóa đơn thuốc) nên không sửa được.',
  SYS_1005: 'Dữ liệu vừa được người khác thay đổi. Hãy tải lại rồi thao tác lại.',
};
export const errorText = (problem) => ERROR_HINTS[problem?.code] || problem?.message || 'Thao tác không thành công.';

export const labelOf = (map, key) => (map[key]?.label ?? map[key] ?? key ?? '—');
export const toneOf = (map, key) => map[key]?.tone ?? 'gray';
