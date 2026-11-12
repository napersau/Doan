#!/usr/bin/env bash
# Chạy bộ e2e v3.0 theo thứ tự: backend (luồng chuẩn + luồng dễ lỗi) -> notification -> giao diện luồng khám -> chức năng phụ -> quét mọi trang.
# Yêu cầu: `docker compose up -d --build` đã xong, mọi container healthy, dữ liệu seed MỚI (docker compose down -v trước khi dựng lại),
# Node 18+, Chrome cài sẵn (cho fe_smoke_v3), `fe/build` có sẵn hoặc cổng 3000 đã có giao diện (image frontend của compose).
# CẢNH BÁO: tạo dữ liệu thật, không dọn lại; muốn chạy lại thì reset CSDL: docker compose down -v && docker compose up -d --build
cd "$(dirname "$0")/.." || exit 1
export PYTHONIOENCODING=utf-8
summary=(); status=0

run() { # tên lệnh...
  local name=$1; shift
  local out; out=$("$@" 2>&1)
  echo "$out" | grep -E "^\s+FAIL"
  local line; line=$(echo "$out" | grep -E "[0-9]+ đạt, [0-9]+ lỗi" | tail -1)
  summary+=("$name: ${line:-(không có dòng tổng kết, chạy riêng để xem chi tiết)}")
  echo "$line" | grep -qE ", 0 lỗi" || status=1
}

run backend node e2e/backend_v3.mjs
run notification node e2e/notification_v3.mjs
run giao-dien-luong-kham node e2e/fe_smoke_v3.mjs
run chuc-nang-phu node e2e/features_v3.mjs     # tạo thêm bác sĩ -> đổi sức chứa khung giờ, nên chạy SAU backend_v3
run quet-giao-dien node e2e/fe_pages_v3.mjs

echo
echo "===== TỔNG KẾT ====="
printf '%s\n' "${summary[@]}"
exit $status
