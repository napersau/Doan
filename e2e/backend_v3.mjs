// Kiểm thử backend v3.0 qua Gateway (HTTP thật): luồng chuẩn Task 5.4 + các luồng dễ lỗi của CLAUDE.md §13.
// Chạy: node e2e/backend_v3.mjs   (cần: stack đang chạy — `docker compose up -d --build` hoặc 6 service + hạ tầng ở dev — và dữ liệu seed
//   `docs/seed_demo_data.sql` mới nạp, vì luồng chuẩn dùng ID cố định: thú cưng #1/#2, thuốc #1 tồn 200)
// CẢNH BÁO: tạo dữ liệu thật (đợt khám, hóa đơn, lịch hẹn). Chỉ chạy trên môi trường thử nghiệm; muốn chạy lại hãy `docker compose down -v`.
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const API = process.env.API_URL || 'http://localhost:8080';
const PASSWORD = 'Admin@123';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

let passed = 0;
const failures = [];
const check = (name, ok, extra = '') => { if (ok) { passed += 1; console.log(`  PASS ${name}`); } else { failures.push(name); console.log(`  FAIL ${name} ${extra}`); } };
const step = (title) => console.log(`\n== ${title}`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function call(method, url, { token, body, form } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${API}${url}`, { method, headers, body: form || (body === undefined ? undefined : JSON.stringify(body)) });
  const text = await response.text();
  let json = {};
  try { json = text ? JSON.parse(text) : {}; } catch { json = { message: text }; }
  return { status: response.status, code: json.errorCode, data: json.data, message: json.message };
}
const CLIN = '/api/v1/clinical';
const PHARM = '/api/v1/pharmacy';
const BILL = '/api/v1/billing';

// Truy vấn CSDL trực tiếp (đối chiếu VIEW tính tiền, bất biến kho). Cần docker compose và .env ở thư mục gốc.
function sql(query) {
  const env = fs.readFileSync(path.join(root, '.env'), 'utf8');
  const password = /^MYSQL_ROOT_PASSWORD=(.*)$/m.exec(env)?.[1]?.trim();
  return execSync(`docker compose exec -T mysql-server mysql -uroot -p${password} -N -B -e "${query.replace(/"/g, '\\"')}"`, { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
}

async function login(username) {
  const res = await call('POST', '/api/v1/auth/login', { body: { username, password: PASSWORD } });
  if (res.status !== 200) throw new Error(`Không đăng nhập được ${username}: ${res.status} ${res.message}`);
  return res.data;
}

const T = {};   // token theo vai trò
async function stockOf(medicineId) { return (await call('GET', `${PHARM}/medicines/${medicineId}`, { token: T.admin })).data.stockQuantity; }
async function newPet(name, speciesId = 1) {
  const res = await call('POST', '/api/v1/pets', { token: T.rec, body: { ownerId: 8, speciesId, name } });
  if (res.status !== 201) throw new Error(`Không tạo được thú cưng ${name}: ${res.status} ${res.message}`);
  return res.data.id;
}
const checkIn = (petId, doctorId, emergency = false) => call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId, doctorId, isEmergency: emergency, initialSymptoms: 'e2e' } });
const startExam = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/start-exam`, { token, body: { weightAtVisit: 5, temperature: 38.5 } });
const visitOf = async (id) => (await call('GET', `${CLIN}/pet-visits/${id}`, { token: T.rec })).data;
const labQueue = async () => (await call('GET', `${CLIN}/lab-queue`, { token: T.lab })).data;
const imagingQueue = async () => (await call('GET', `${CLIN}/imaging-queue`, { token: T.img })).data;
const detailOf = (queue, visitId) => queue.find((item) => item.visitId === visitId)?.serviceDetailId;
const rx = (visitId, medicineId, quantity) => ({ visitId, doctorNotes: 'e2e', items: [{ medicineId, quantity, dosage: '1 viên/lần', frequency: '2 lần/ngày', durationDays: 5 }] });
const close = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/close-visit`, { token, body: { diagnosis: 'Viêm da', treatmentPlan: 'Dùng thuốc', doctorAdvice: 'Theo dõi' } });
async function labResults(detailId) {
  const form = (await call('GET', `${CLIN}/service-details/${detailId}/lab-form`, { token: T.lab })).data;
  return call('POST', `${CLIN}/lab-results/${detailId}`, { token: T.lab, body: { results: form.map((item) => ({ labTestItemId: item.id, resultValue: 10 })) } });
}
function imagingForm() { const form = new FormData(); form.append('description', 'Bình thường'); form.append('conclusion', 'Không bất thường'); form.append('files', new Blob([PNG], { type: 'image/png' }), 'sa.png'); return form; }
async function payAllServices(visitId) {
  const st = (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data;
  const ids = st.invoices.filter((i) => i.paymentStatus === 'PENDING').map((i) => i.id);
  return call('POST', `${CLIN}/pet-visits/${visitId}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: ids } });
}

async function main() {
  const tokens = { admin: 'admin', doc2: 'bs_tien', doc3: 'bs_an', rec: 'letan_hoa', lab: 'ktv_lab_minh', img: 'ktv_cdha_dung', owner: 'khach_lan' };
  const sessions = {};
  for (const [key, username] of Object.entries(tokens)) { sessions[key] = await login(username); T[key] = sessions[key].accessToken; }

  step('A. Luồng chuẩn Task 5.4: thu ngân 530.000đ + thuốc 157.500đ, ca đầu hàng đợi sau CLS, thu hóa đơn cuối → COMPLETED');
  const v1 = (await checkIn(1, 2)).data.id;
  check('check-in thú cưng #1 vào hàng đợi bác sĩ #2', (await visitOf(v1)).status === 'WAITING');
  check('bác sĩ gọi số (không đổi trạng thái)', (await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc2 })).data?.id === v1 && (await visitOf(v1)).status === 'WAITING');
  check('bắt đầu khám (T02): sinh hóa đơn khám', (await startExam(T.doc2, v1)).status === 200);
  const ordered = await call('POST', `${CLIN}/pet-visits/${v1}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5, 7] } });
  check('chỉ định CBC + X-quang (T04): WAITING_RESULTS', ordered.data?.status === 'WAITING_RESULTS', JSON.stringify(ordered).slice(0, 200));
  check('Lab call-next → ca đúng', (await call('POST', `${CLIN}/lab-queue/call-next`, { token: T.lab })).data?.visitId === v1);
  const labDetail = detailOf(await labQueue(), v1);
  await call('POST', `${CLIN}/service-details/${labDetail}/start`, { token: T.lab });
  await call('POST', `${CLIN}/service-details/${labDetail}/sample-collected`, { token: T.lab });
  check('nhập kết quả xét nghiệm', (await labResults(labDetail)).status === 200);
  check('Imaging call-next', (await call('POST', `${CLIN}/imaging-queue/call-next`, { token: T.img })).data?.visitId === v1);
  const imgDetail = detailOf(await imagingQueue(), v1);
  await call('POST', `${CLIN}/service-details/${imgDetail}/start`, { token: T.img });
  check('nhập kết quả CĐHA kèm ảnh', (await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imagingForm() })).status === 200);
  check('đủ kết quả → RESULTS_READY (T06) và đứng đầu hàng đợi bác sĩ', (await visitOf(v1)).status === 'RESULTS_READY' && (await call('GET', `${CLIN}/pet-visits/my-queue`, { token: T.doc2 })).data[0]?.id === v1);
  await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc2 });
  check('bắt đầu giai đoạn 2 (T07)', (await call('POST', `${CLIN}/pet-visits/${v1}/start-phase2`, { token: T.doc2 })).status === 200);
  const stock0 = await stockOf(1);
  const prescription = await call('POST', `${PHARM}/prescriptions`, { token: T.doc2, body: rx(v1, 1, 10) });
  check('kê đơn 10 Clavamox (không giá, không trạng thái)', prescription.status === 201 && prescription.data.stockWarnings.length === 0 && !('status' in prescription.data));
  check('chốt ca (T09) → WAITING_PAYMENT', (await close(T.doc2, v1)).data?.status === 'WAITING_PAYMENT');
  const statement = (await call('GET', `${CLIN}/pet-visits/${v1}/service-invoices`, { token: T.rec })).data;
  check('3 hóa đơn dịch vụ riêng (khám, CBC, X-quang) tổng 530.000đ', statement.invoices.length === 3 && Number(statement.totalPending) === 530000, JSON.stringify(statement).slice(0, 300));
  check('VIEW v_service_invoice_amounts = 530.000', Number(sql(`SELECT SUM(payable_amount) FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE visit_id=${v1}`)) === 530000);
  const mi = await call('POST', `${PHARM}/prescriptions/${prescription.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: prescription.data.items[0].id, quantity: 10 }], discountAmount: 0 } });
  check('hóa đơn thuốc 157.500đ (10 × 15.000 × 1,05)', mi.status === 201 && Number(mi.data.payableAmount) === 157500);
  check('VIEW v_medicine_invoice_amounts = 157.500', Number(sql(`SELECT payable_amount FROM pet_clinic_billing_pharmacy_db.v_medicine_invoice_amounts WHERE invoice_id=${mi.data.id}`)) === 157500);
  check('thu tiền thuốc: 200 → 190, trạng thái ca không đổi', (await call('POST', `${BILL}/medicine-invoices/${mi.data.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(1)) === stock0 - 10 && (await visitOf(v1)).status === 'WAITING_PAYMENT');
  check('bất biến SUM(inventory_transactions.quantity) = stock_quantity', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=1) - stock_quantity FROM pet_clinic_billing_pharmacy_db.medicines WHERE id=1`) === '0');
  const paid = await payAllServices(v1);
  check('thu hóa đơn cuối → COMPLETED cùng giao dịch (T10)', paid.status === 200 && (await visitOf(v1)).status === 'COMPLETED');

  step('B. EX-15 (thiếu kho → rollback, sửa hóa đơn, thu lại) và EX-16 (thu trùng, kể cả đồng thời)');
  const p2 = await newPet('E2E-ex15');
  const v2 = (await checkIn(p2, 3)).data.id;
  await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc3 }); await startExam(T.doc3, v2);
  const rx2 = await call('POST', `${PHARM}/prescriptions`, { token: T.doc3, body: rx(v2, 4, 10) });   // Drontal tồn 100
  await close(T.doc3, v2);
  const stock4 = await stockOf(4);
  await call('POST', `${PHARM}/inventory/adjust`, { token: T.admin, body: { medicineId: 4, delta: -(stock4 - 5), reason: 'e2e đưa tồn về 5' } });
  const mi2 = await call('POST', `${PHARM}/prescriptions/${rx2.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: rx2.data.items[0].id, quantity: 10 }] } });
  const lack = await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec });
  check('EX-15: thiếu kho → 409 PHARM_1406', lack.status === 409 && lack.code === 'PHARM_1406');
  check('EX-15: tồn vẫn 5, không có dòng EXPORT_SALE, hóa đơn vẫn PENDING', (await stockOf(4)) === 5 && sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_invoice_id=${mi2.data.id}`) === '0' && (await call('GET', `${BILL}/medicine-invoices/${mi2.data.id}`, { token: T.rec })).data.paymentStatus === 'PENDING');
  await call('PUT', `${BILL}/medicine-invoices/${mi2.data.id}/items`, { token: T.rec, body: { items: [{ prescriptionItemId: rx2.data.items[0].id, quantity: 5 }] } });
  check('EX-15: sửa về 5 viên rồi thu lại → PAID, tồn 0', (await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(4)) === 0);
  check('EX-16: thu lần hai → 409 BILL_1403', (await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec })).code === 'BILL_1403');
  // thu đồng thời trên hóa đơn khác
  await call('POST', `${PHARM}/inventory/import`, { token: T.admin, body: { medicineId: 4, quantity: 50, notes: 'e2e' } });
  const p3 = await newPet('E2E-ex16');
  const v3 = (await checkIn(p3, 3)).data.id;
  await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc3 }); await startExam(T.doc3, v3);
  const rx3 = await call('POST', `${PHARM}/prescriptions`, { token: T.doc3, body: rx(v3, 4, 10) });
  await close(T.doc3, v3);
  const mi3 = await call('POST', `${PHARM}/prescriptions/${rx3.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: rx3.data.items[0].id, quantity: 10 }] } });
  const before = await stockOf(4);
  const race = await Promise.all([1, 2, 3, 4, 5].map(() => call('POST', `${BILL}/medicine-invoices/${mi3.data.id}/confirm-payment`, { token: T.rec })));
  check('EX-16 đồng thời: đúng 1/5 yêu cầu thu được, 4 bị từ chối 409', race.filter((r) => r.status === 200).length === 1 && race.filter((r) => r.status === 409).length === 4, race.map((r) => r.status).join(','));
  check('EX-16 đồng thời: kho chỉ trừ đúng một lần', (await stockOf(4)) === before - 10);
  check('bất biến kho giữ nguyên sau EX-15/16 (thuốc #4)', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=4) - stock_quantity FROM pet_clinic_billing_pharmacy_db.medicines WHERE id=4`) === '0');

  step('C. Đua RESULTS_READY: hai KTV hoàn tất cùng lúc (không kẹt WAITING_RESULTS)');
  let stuck = 0;
  for (let round = 1; round <= 3; round += 1) {
    const pet = await newPet(`E2E-race-${round}`);
    const v = (await checkIn(pet, 2)).data.id;
    await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc2 }); await startExam(T.doc2, v);
    await call('POST', `${CLIN}/pet-visits/${v}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5, 7] } });
    await call('POST', `${CLIN}/lab-queue/call-next`, { token: T.lab }); await call('POST', `${CLIN}/imaging-queue/call-next`, { token: T.img });
    const lab = detailOf(await labQueue(), v); const img = detailOf(await imagingQueue(), v);
    await call('POST', `${CLIN}/service-details/${lab}/start`, { token: T.lab }); await call('POST', `${CLIN}/service-details/${img}/start`, { token: T.img });
    await Promise.all([labResults(lab), call('POST', `${CLIN}/imaging-results/${img}`, { token: T.img, form: imagingForm() })]);
    if ((await visitOf(v)).status !== 'RESULTS_READY') stuck += 1;
    await call('POST', `${CLIN}/pet-visits/${v}/abandon`, { token: T.rec });   // dọn: khách bỏ về → COMPLETED + công nợ
  }
  check('3 vòng hoàn tất đồng thời: không ca nào kẹt ở WAITING_RESULTS', stuck === 0, `kẹt ${stuck}/3`);

  step('D. Gọi theo từng dòng (Q3/Q4): ca vắng giữ nguyên vị trí, ca thứ 3, 4 vẫn gọi được');
  const pets = []; for (let i = 1; i <= 4; i += 1) pets.push(await newPet(`E2E-q${i}`));
  const queue = []; for (const pet of pets) { queue.push((await checkIn(pet, 3)).data.id); await sleep(5); }
  const order = async () => (await call('GET', `${CLIN}/pet-visits/my-queue`, { token: T.doc3 })).data.map((v) => v.id);
  const [A, B, C, D] = queue;
  check('hàng đợi ban đầu theo thứ tự đến [A,B,C,D]', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  const c1 = await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc3 });
  check('"Gọi số tiếp theo" gọi ca đầu hàng (A)', c1.status === 200 && c1.data?.id === A, brief(c1));
  const cB = await call('POST', `${CLIN}/pet-visits/${B}/call`, { token: T.doc3 });
  const cC = await call('POST', `${CLIN}/pet-visits/${C}/call`, { token: T.doc3 });
  const cD = await call('POST', `${CLIN}/pet-visits/${D}/call`, { token: T.doc3 });
  check('A, B, C vắng: bác sĩ gọi tiếp B, C rồi D (ca thứ 4) đều được, không bị kẹt', [cB, cC, cD].every((r) => r.status === 200) && cD.data?.id === D, brief(cD));
  check('gọi không đổi thứ tự hàng đợi (vẫn [A,B,C,D]) và không đổi trạng thái', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  const callAgain = await call('POST', `${CLIN}/pet-visits/${A}/call`, { token: T.doc3 });
  check('khách A quay lại: "Gọi lại" ca A được, thứ tự vẫn không đổi', callAgain.status === 200 && JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  check('bác sĩ khác không gọi được ca của bác sĩ này → 403 CLIN_1311', (await call('POST', `${CLIN}/pet-visits/${A}/call`, { token: T.doc2 })).code === 'CLIN_1311');
  check('API "đẩy lùi" đã bị bỏ (không còn endpoint skip)', (await call('POST', `${CLIN}/pet-visits/${A}/skip`, { token: T.doc3 })).status >= 400);
  check('cột skip_count đã bị xóa khỏi lược đồ', Number(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='pet_clinic_clinical_db' AND column_name='skip_count'")) === 0);
  for (const id of queue) await call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'e2e dọn' } });

  step('E. Công nợ: khách bỏ về → COMPLETED, thu bù sau');
  const p5 = await newPet('E2E-debt');
  const v5 = (await checkIn(p5, 2)).data.id;
  await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc2 }); await startExam(T.doc2, v5);
  await call('POST', `${CLIN}/pet-visits/${v5}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5] } });
  await call('POST', `${CLIN}/lab-queue/call-next`, { token: T.lab });
  const d5 = detailOf(await labQueue(), v5); await call('POST', `${CLIN}/service-details/${d5}/start`, { token: T.lab }); await labResults(d5);
  const abandoned = await call('POST', `${CLIN}/pet-visits/${v5}/abandon`, { token: T.rec });
  check('khách bỏ về: ca COMPLETED, không tạo hóa đơn mới', abandoned.data?.status === 'COMPLETED');
  check('ca xuất hiện ở danh sách công nợ', (await call('GET', `${CLIN}/pet-visits/with-debt`, { token: T.rec })).data.some((v) => v.id === v5));
  check('thu công nợ về sau, ca giữ COMPLETED, hết công nợ', (await payAllServices(v5)).status === 200 && (await visitOf(v5)).status === 'COMPLETED' && !(await call('GET', `${CLIN}/pet-visits/with-debt`, { token: T.rec })).data.some((v) => v.id === v5));

  step('F. Đặt lịch đồng thời (sức chứa = bác sĩ ACTIVE × 2) và slots công khai');
  const day = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10);
  const slotsBefore = await call('GET', `/api/v1/appointments/slots?date=${day}`);
  const slot = slotsBefore.data.find((s) => s.startTime.startsWith('10:00'));
  check('slots công khai không cần token', slotsBefore.status === 200 && slot.capacity === 6, JSON.stringify(slot));
  const bookPets = []; for (let i = 1; i <= 9; i += 1) bookPets.push(await newPet(`E2E-book-${i}`));
  const booking = await Promise.all(bookPets.map((pet) => call('POST', '/api/v1/appointments', { token: T.owner, body: { petId: pet, appointmentDate: day, startTime: '10:00', reason: 'e2e đặt đồng thời' } })));
  const ok = booking.filter((r) => r.status === 201).length;
  check('9 yêu cầu đồng thời vào khung còn 6 chỗ: đúng 6 thành công, 3 bị từ chối', ok === 6 && booking.filter((r) => r.code === 'APPT_1203').length === 3, booking.map((r) => `${r.status}${r.code ? `/${r.code}` : ''}`).join(' '));
  check('sau đó khung hết chỗ', (await call('GET', `/api/v1/appointments/slots?date=${day}`)).data.find((s) => s.startTime.startsWith('10:00')).available === false);

  step('G. Refresh token dùng lại bị coi là đánh cắp (Token Rotation)');
  const s0 = await login('khach_lan');
  const r1 = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: s0.refreshToken } });
  check('refresh lần 1 thành công và cấp token mới', r1.status === 200 && r1.data.refreshToken !== s0.refreshToken);
  const reuse = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: s0.refreshToken } });
  check('dùng lại token cũ → 401', reuse.status === 401);
  check('token mới cũng bị thu hồi (đăng xuất mọi thiết bị)', (await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: r1.data.refreshToken } })).status === 401);

  step('H. Phân quyền và /internal bị chặn ở Gateway');
  check('/internal/** từ ngoài bị chặn', [403, 404].includes((await call('GET', '/internal/pet-visits/1/summary')).status));
  check('lễ tân không kê đơn (403)', (await call('POST', `${PHARM}/prescriptions`, { token: T.rec, body: rx(1, 1, 1) })).status === 403);
  check('bác sĩ không thu tiền thuốc (403)', (await call('POST', `${BILL}/medicine-invoices/1/confirm-payment`, { token: T.doc2 })).status === 403);
  check('không token → 401 SYS_1002', (await call('GET', `${CLIN}/pet-visits/queue`)).code === 'SYS_1002');

  console.log(`\n${passed} đạt, ${failures.length} lỗi${failures.length ? `: ${failures.join('; ')}` : ''}`);
  process.exit(failures.length ? 1 : 0);
}

main().catch((problem) => { console.error(problem); process.exit(2); });
