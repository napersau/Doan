// Quét MỌI trang giao diện theo từng vai trò (Chrome headless, qua Gateway) + các thao tác CRUD chưa nằm trong fe_smoke_v3.mjs:
// lễ tân tạo khách hàng, chủ nuôi đổi / hủy lịch, Admin tạo - khóa - mở khóa nhân sự, thêm loài, thêm dịch vụ. Mỗi trang phải tải được,
// không hiện khung lỗi và không phát sinh lỗi console / HTTP ≥ 400.
// Chạy: node e2e/fe_pages_v3.mjs   (stack đang chạy, giao diện ở :3000 — image frontend của compose — và dữ liệu seed)
import { launchChrome, sleep } from './fe_smoke_v3.mjs';

const API = process.env.API_URL || 'http://localhost:8080';
const FE = `http://localhost:${process.env.FE_PORT || 3000}`;
const PASSWORD = 'Admin@123';
const RUN = Date.now().toString().slice(-6);
let passed = 0; const failures = [];
const check = (name, ok, extra = '') => { if (ok) { passed += 1; console.log(`  PASS ${name}`); } else { failures.push(name); console.log(`  FAIL ${name} ${extra}`); } };
const step = (title) => console.log(`\n== ${title}`);

async function api(method, url, body, token) {
  const r = await fetch(`${API}${url}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); let j = {}; try { j = t ? JSON.parse(t) : {}; } catch { j = {}; }
  return { status: r.status, data: j.data, code: j.errorCode };
}
const apiLogin = async (u) => (await api('POST', '/api/v1/auth/login', { username: u, password: PASSWORD })).data.accessToken;
const dayOffset = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

// Thử một thao tác giao diện: lỗi (không thấy nút, hết thời gian...) được ghi là FAIL thay vì làm dừng cả bộ kiểm tra
async function attempt(name, fn) {
  try { check(name, (await fn()) !== false); } catch (problem) { check(name, false, String(problem.message).slice(0, 300)); }
}

async function main() {
  const alive = await fetch(FE).then(() => true).catch(() => false);
  if (!alive) throw new Error(`Giao diện chưa chạy ở ${FE}: dùng docker compose up -d (image frontend) hoặc node e2e/fe_smoke_v3.mjs để dựng từ fe/build.`);
  const { browser: b, child, profile } = await launchChrome();
  const sweep = async (role, username, routes) => {
    step(`Quét trang của ${role}`);
    check(`${role} đăng nhập`, await b.login(username, PASSWORD));
    for (const [route, marker] of routes) {
      await b.goto(route);
      const ok = await b.hasText(marker, 15000);
      const text = await b.text();
      check(`${route} hiện "${marker}" và không có khung lỗi`, ok && !text.includes('Thử lại') && !text.includes('Không tải được'), text.replace(/\s+/g, ' ').slice(0, 200));
    }
  };
  try {
    await sweep('chủ nuôi', 'khach_lan', [['/portal/profile', 'Thông tin cá nhân'], ['/portal/pets', 'Thú cưng của tôi'], ['/portal/pets/1', 'Lu'], ['/portal/booking', 'Đặt lịch'], ['/portal/appointments', 'Lịch hẹn của tôi'], ['/portal/medical-history', 'Lịch sử khám bệnh'], ['/portal/notifications', 'Thông báo']]);
    await sweep('lễ tân', 'letan_hoa', [['/reception/queue', 'Bảng điều phối'], ['/reception/intake', 'Tiếp nhận khách hàng'], ['/reception/appointments', 'Lịch hẹn'], ['/reception/invoices', 'Hóa đơn & thanh toán'], ['/reception/dispense', 'Bán thuốc'], ['/reception/customers', 'Thông tin khách hàng'], ['/reception/pets', 'Thông tin thú cưng'], ['/reception/history', 'Tra cứu bệnh án']]);
    await sweep('bác sĩ', 'bs_tien', [['/vet/today', 'Hàng đợi của tôi'], ['/vet/history', 'Tra cứu bệnh án'], ['/vet/medicines', 'Tra cứu thuốc']]);
    await sweep('KTV xét nghiệm', 'ktv_lab_minh', [['/lab/queue', 'Hàng đợi xét nghiệm']]);
    await sweep('KTV CĐHA', 'ktv_cdha_dung', [['/imaging/queue', 'Hàng đợi chẩn đoán hình ảnh']]);
    await sweep('Admin', 'admin', [['/admin/dashboard', 'Tổng quan'], ['/admin/users', 'Tài khoản'], ['/admin/visits', 'Đợt khám'], ['/admin/services', 'Dịch vụ'], ['/admin/lab-config', 'Xét nghiệm & khoảng tham chiếu'], ['/admin/species', 'Loài thú cưng'], ['/admin/medicines', 'Thuốc & kho'], ['/admin/reports', 'Báo cáo & thống kê']]);

    step('Lễ tân: tạo khách hàng, tra cứu, mở hồ sơ, khách đăng nhập bằng mật khẩu tạm');
    const custPhone = `0961${RUN}`; const custName = `Khach UI ${RUN}`;
    let tempPassword = '';
    await b.login('letan_hoa', PASSWORD);
    await b.goto('/reception/customers');
    await attempt('tạo khách hàng mới qua giao diện, hiện mật khẩu tạm một lần', async () => {
      await b.click('+ Thêm khách hàng'); await b.fill('Họ và tên', custName); await b.fill('Số điện thoại', custPhone);
      await b.click('Tạo tài khoản', 'button[type=submit]');
      const ok = await b.waitFor('document.querySelector("[data-testid=temporary-password]")', 10000);
      tempPassword = ok ? await b.eval('document.querySelector("[data-testid=temporary-password]").textContent') : '';
      await b.click('Đã ghi lại');
      return ok && tempPassword.length >= 8;
    });
    await attempt('tìm khách theo SĐT và mở hồ sơ khách hàng', async () => {
      await b.fill('Tìm khách hàng', custPhone);
      await b.waitFor(`document.body.innerText.includes(${JSON.stringify(custName)})`, 8000);
      await b.click('Hồ sơ');
      return b.hasText('Hồ sơ khách hàng', 10000);
    });
    await attempt('khách mới đăng nhập được bằng mật khẩu tạm và vào cổng khách hàng', async () => {
      const ok = await b.login(custPhone, tempPassword);
      return ok && await b.hasText('Thông tin cá nhân', 10000);
    });

    step('Chủ nuôi: đổi lịch và hủy lịch trên giao diện');
    const ownerToken = await apiLogin('khach_lan');
    const petId = (await api('POST', '/api/v1/pets', { speciesId: 2, name: `UI-Lich-${RUN}` }, ownerToken)).data.id;
    const appt = await api('POST', '/api/v1/appointments', { petId, appointmentDate: dayOffset(7), startTime: '09:30', reason: 'Kiểm tra giao diện' }, ownerToken);
    check('chuẩn bị lịch hẹn qua API', appt.status === 201, JSON.stringify(appt).slice(0, 200));
    await b.login('khach_lan', PASSWORD);
    await b.goto('/portal/appointments');
    await attempt('đổi lịch: chọn khung giờ khác và lưu', async () => {
      await b.clickRow(appt.data.appointmentCode, 'Đổi lịch');
      await b.waitFor('document.querySelector(".ui-slot:not([disabled])")', 10000);
      await b.eval('[...document.querySelectorAll(".ui-slot:not([disabled])")].find((s) => !s.textContent.startsWith("09:30")).click()');
      await b.click('Đổi lịch', 'button[type=submit]');
      return b.hasText('Đã đổi lịch', 10000);
    });
    await attempt('hủy lịch: nhập lý do và xác nhận', async () => {
      await b.clickRow(appt.data.appointmentCode, 'Hủy');
      await b.fill('Lý do', 'Bận việc đột xuất'); await b.click('Xác nhận hủy', 'button[type=submit]');
      return b.hasText('Đã hủy lịch', 10000);
    });
    await attempt('lịch đã hủy hiện ở tab Đã hủy', async () => { await b.click('Đã hủy', '[role=tab]'); return b.hasText(appt.data.appointmentCode, 8000); });

    step('Lễ tân: duyệt và hủy lịch trên giao diện');
    const a2 = await api('POST', '/api/v1/appointments', { petId, appointmentDate: dayOffset(8), startTime: '11:00', reason: 'Lễ tân xử lý' }, ownerToken);
    await b.login('letan_hoa', PASSWORD);
    await b.goto('/reception/appointments');
    await attempt('lễ tân xác nhận lịch chờ duyệt', async () => { await b.clickRow(a2.data.appointmentCode, 'Xác nhận'); return b.hasText('Đã xác nhận lịch', 10000); });

    step('Admin: nhân sự (tạo, khóa, mở khóa), loài, dịch vụ');
    const staffPhone = `0971${RUN}`;
    await b.login('admin', PASSWORD);
    await b.goto('/admin/users');
    await attempt('tạo bác sĩ mới có số phòng qua giao diện', async () => {
      await b.click('+ Thêm nhân sự'); await b.fill('Vai trò', 'Bác sĩ thú y'); await b.fill('Họ và tên', `BS UI ${RUN}`); await b.fill('Số điện thoại', staffPhone);
      await b.fill('Số phòng làm việc', `U${RUN.slice(-3)}`); await b.click('Tạo ngẫu nhiên');
      await b.click('Tạo tài khoản', 'button[type=submit]').catch(async () => { await b.click('Thêm', 'button[type=submit]'); });
      await b.waitFor('!document.querySelector("dialog[open]")', 10000);
      const found = await api('GET', `/api/v1/auth/users?keyword=${staffPhone}`, undefined, await apiLogin('admin'));
      return found.data?.items?.some((u) => u.role === 'VETERINARIAN' && u.roomNumber === `U${RUN.slice(-3)}`);
    });
    await attempt('tìm nhân sự và khóa tài khoản', async () => {
      await b.fill('Tìm tài khoản', staffPhone);
      await b.waitFor(`document.body.innerText.includes(${JSON.stringify(`BS UI ${RUN}`)})`, 8000);
      await b.clickRow(`BS UI ${RUN}`, 'Khóa'); await b.click('Xác nhận', 'button[type=submit]');
      return b.waitFor(`[...document.querySelectorAll('tr')].some((r) => r.innerText.includes(${JSON.stringify(`BS UI ${RUN}`)}) && r.innerText.includes('Mở khóa'))`, 10000);
    });
    await attempt('mở khóa lại', async () => { await b.clickRow(`BS UI ${RUN}`, 'Mở khóa'); await b.click('Xác nhận', 'button[type=submit]'); return b.waitFor(`[...document.querySelectorAll('tr')].some((r) => r.innerText.includes(${JSON.stringify(`BS UI ${RUN}`)}) && r.innerText.includes('Khóa') && !r.innerText.includes('Mở khóa'))`, 10000); });
    await b.goto('/admin/species');
    await attempt('thêm loài mới', async () => { await b.click('+ Thêm loài'); await b.fill('Mã loài', `UI${RUN}`); await b.fill('Tên loài', `Loài UI ${RUN}`); await b.click('Thêm loài', 'button[type=submit]'); return b.hasText(`Đã lưu loài Loài UI ${RUN}`, 10000); });
    await b.goto('/admin/services');
    await attempt('thêm dịch vụ có VAT 8% qua giao diện, bảng hiện VAT', async () => {
      await b.click('+ Thêm dịch vụ'); await b.fill('Mã dịch vụ', `UI_SV${RUN}`); await b.fill('Tên dịch vụ', `Dịch vụ UI ${RUN}`); await b.fill('Đơn giá', '120000'); await b.fill('VAT', '8');
      await b.click('Thêm dịch vụ', 'button[type=submit]');
      return (await b.hasText(`Đã lưu dịch vụ Dịch vụ UI ${RUN}`, 10000)) && (await b.waitFor(`[...document.querySelectorAll('tr')].some((r) => r.innerText.includes(${JSON.stringify(`Dịch vụ UI ${RUN}`)}) && r.innerText.includes('VAT 8%'))`, 8000));
    });
  } finally {
    const unexpected = b.problems.filter((p) => !/HTTP 404 \/api\/v1\/pharmacy\/prescriptions\/by-visit/.test(p) && !/HTTP 401 \/api\/v1\/auth\/(login|me)/.test(p) && !/HTTP 409 \/api\/v1\/auth\/users/.test(p));
    console.log(`\nLỗi console/mạng phát sinh trong lúc chạy (${unexpected.length}):`); [...new Set(unexpected)].slice(0, 25).forEach((p) => console.log(`  - ${p}`));
    check('không có lỗi console / HTTP ≥ 400 ngoài dự kiến', unexpected.length === 0);
    child.kill(); try { (await import('node:fs')).rmSync(profile, { recursive: true, force: true }); } catch { /* Chrome còn giữ tệp */ }
  }
  console.log(`\n${passed} đạt, ${failures.length} lỗi${failures.length ? `: ${failures.join('; ')}` : ''}`);
  process.exit(failures.length ? 1 : 0);
}
void sleep;
main().catch((problem) => { console.error(problem); process.exit(2); });
