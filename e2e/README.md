# Kiểm thử end-to-end (v3.0)

Chạy qua API Gateway (`http://localhost:8080`) trên hệ thống Docker thật, tự đối chiếu `PASS` / `FAIL`. Mỗi kịch bản in dòng cuối
`N đạt, M lỗi`. Bộ e2e của thiết kế v2 (hàng đợi sảnh 3 bậc, Khóa ưu tiên, hóa đơn gộp, `dispense`) đã bị **xóa** ở Phase 7.

| File | Phạm vi | Số kiểm tra |
|---|---|---|
| `backend_v3.mjs` | **Luồng chuẩn Task 5.4** (check-in → gọi số → khám → CBC + X-quang → có kết quả lên đầu hàng đợi → kê đơn → chốt → thu): đối chiếu `v_service_invoice_amounts` = **530.000**, `v_medicine_invoice_amounts` = **157.500**, Clavamox 200 → 190; **EX-15** (thiếu kho → rollback, sửa hóa đơn, thu lại), **EX-16** (thu trùng, kể cả 5 yêu cầu song song); **đua `RESULTS_READY`** (2 KTV hoàn tất cùng lúc, 3 vòng); **Đẩy lùi 1 bậc** (công thức `queue_order`); **công nợ** (khách bỏ về → thu bù); **đặt lịch đồng thời** (9 yêu cầu vào khung còn 6 chỗ); **refresh token dùng lại**; phân quyền, `/internal/**` bị chặn; bất biến kho `SUM(quantity) = stock_quantity` | 45 |
| `notification_v3.mjs` | 9 sự kiện v3.0 → thông báo đúng người (chủ nuôi, bác sĩ, KTV, lễ tân, Admin) qua RabbitMQ thật; `POST /notifications/{id}/read` (riêng từng người, idempotent, 404); `send-email` đã bỏ; email (chỉ khi truy cập được Mailpit) | 28 |
| `fe_smoke_v3.mjs` | **Giao diện thật** trên Chrome headless (Chrome DevTools Protocol, gói `ws` có sẵn trong `fe/node_modules`): khung giờ công khai, đặt lịch, tiếp nhận kèm chọn bác sĩ, Gọi số / Đẩy lùi, dịch vụ tại phòng, CLS Lab/CĐHA (có ảnh), giai đoạn 2, kê đơn (cảnh báo tồn), thu từng phần + giảm giá, bán thuốc, bệnh án + thông báo của chủ nuôi, báo cáo hai nguồn, phân quyền; in mọi lỗi console / HTTP ≥ 400 | 69 |
| `features_v3.mjs` | **Chức năng ngoài luồng khám chính** (165 kiểm tra): tài khoản (tự đăng ký, hồ sơ, đổi / đặt lại mật khẩu, tạo - khóa - mở khóa - đổi vai trò, phân quyền lễ tân, chặn dò mật khẩu 429); danh mục (loài, dịch vụ + VAT, chỉ số xét nghiệm + khoảng tham chiếu + gói, **đánh giá LOW/NORMAL/HIGH**, nhập thiếu chỉ số bị từ chối); thú cưng và quyền xem; lịch hẹn (đặt, duyệt, đổi giờ, hủy, **quy tắc 2 giờ**, NO_SHOW, tiếp nhận theo lịch → `CHECKED_IN`, báo cáo); quản lý đợt khám (phân công / gỡ / đổi, cấp cứu, T03, T05, T11, T12, T14 bác sĩ + Admin, dịch vụ tại phòng, giảm giá, thu một phần, `CLIN_1310`, ảnh CĐHA: kiểm tra nội dung tệp + quyền tải); báo cáo; danh mục thuốc/kho; **gộp ca CĐHA** (hai dịch vụ cùng đợt khám bị đẩy lùi cùng nhau); **WebSocket/STOMP thật** (JWT, cấm đăng ký kênh vai trò khác, cấm SEND, nhận thông báo thời gian thực) | 165 |
| `fe_pages_v3.mjs` | **Quét mọi trang giao diện** theo 6 vai trò (marker + không khung lỗi + không lỗi console/HTTP) và các thao tác chưa có trong smoke: lễ tân tạo khách (mật khẩu tạm hiện một lần, khách đăng nhập được), chủ nuôi đổi / hủy lịch, lễ tân duyệt lịch, Admin tạo - khóa - mở khóa bác sĩ, thêm loài, thêm dịch vụ có VAT | 48 |
| `auth.sh` | auth-service: đăng nhập, refresh, khóa tài khoản, endpoint nội bộ (kịch bản quan sát, không đếm PASS; tạo thêm tài khoản bác sĩ thử) | – |

## Chạy

```bash
docker compose down -v && docker compose up -d --build   # dữ liệu seed sạch; chờ mọi container healthy (lần đầu ~10 phút do máy chậm)
bash e2e/run_all.sh                                       # backend -> notification -> giao diện, in tổng kết, mã thoát 1 nếu có lỗi
# hoặc từng kịch bản:
node e2e/backend_v3.mjs
node e2e/notification_v3.mjs
node e2e/fe_smoke_v3.mjs
node e2e/features_v3.mjs        # sau backend_v3 (tạo thêm bác sĩ làm đổi sức chứa khung giờ)
node e2e/fe_pages_v3.mjs
```

Yêu cầu: Node 18+ (dùng `fetch`, `FormData`), Docker Compose (script đối chiếu VIEW/bất biến kho bằng `docker compose exec mysql-server mysql`,
đọc `MYSQL_ROOT_PASSWORD` từ `.env` ở thư mục gốc), Chrome cài sẵn cho `fe_smoke_v3` (hoặc đặt `CHROME_PATH`). Giao diện lấy ở cổng 3000
(image `frontend` của compose); nếu cổng trống, script tự phục vụ thư mục `fe/build` (cần `npm run build` trong `fe/`). Biến tùy chọn:
`API_URL`, `FE_PORT`, `MAILPIT_URL`.

## Lưu ý

- Các kịch bản **tạo và để lại dữ liệu nghiệp vụ** (đợt khám, hóa đơn, lịch hẹn, thú cưng thử, kho thuốc bị trừ / điều chỉnh) và dùng ID seed cố
  định (thú cưng #1/#2, thuốc #1 tồn 200, bác sĩ #2/#3, `Admin@123`). Muốn chạy lại phải reset CSDL (`docker compose down -v`).
  Thứ tự `backend` → `notification` → `giao-dien` trong `run_all.sh` đã tính tới tồn kho còn lại giữa các kịch bản.
- `fe_smoke_v3` tự tạo 4 tài khoản nhân sự (`0990000001..4`, mật khẩu `Smoke@1234`) nếu chưa có.
- Lịch sử thông báo của notification-service nằm trong bộ nhớ nên còn lại giữa các lần chạy.
- Kiểm tra email cần Mailpit truy cập được (cổng 8025 chỉ mở khi dùng `docker-compose.dev.yml`); nếu không, phần email được bỏ qua.
- `features_v3.mjs` chạy lại được: đầu script nó dọn các đợt khám dở dang của bác sĩ #2-#4 bằng một câu SQL (vệ sinh dữ liệu thử). Kiểm tra email cần Mailpit truy cập được (dùng `-f docker-compose.dev.yml`).
- Việc đặt lịch đồng thời dựa vào sức chứa mỗi khung = số bác sĩ ACTIVE × 2: chạy sau `auth.sh` (tạo thêm bác sĩ) sẽ làm đổi con số 6.
