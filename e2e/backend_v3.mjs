// Kiểm thử backend v3.0 qua Gateway (HTTP thật): luồng chuẩn Task 5.4 + các luồng dễ lỗi của CLAUDE.md §13.
// Chạy: node e2e/backend_v3.mjs   (cần: stack đang chạy — `docker compose up -d --build` hoặc 6 service + hạ tầng ở dev — và dữ liệu seed
//   `db/seed_current.sql` mới nạp (lộ trình v4), vì luồng chuẩn dùng ID cố định: thú cưng #1/#2, thuốc #1 tồn 200)
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
const brief = (res) => `${res.status}${res.code ? `/${res.code}` : ''} ${String(res.message || '').slice(0, 120)}`;
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
const statementOf = async (visitId) => (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data;
const pay = (visitId, ids) => call('POST', `${CLIN}/pet-visits/${visitId}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: ids } });
async function payWhere(visitId, filter) {
  const st = await statementOf(visitId);
  return pay(visitId, st.invoices.filter((i) => i.paymentStatus === 'PENDING' && filter(i)).map((i) => i.id));
}
const payAllServices = (visitId) => payWhere(visitId, () => true);
// Thu trước (17/11/2026): phí khám thu xong mới có phiếu DOCTOR; tiền XN / CĐHA thu xong mới có phiếu phòng
const payExamFee = (visitId) => payWhere(visitId, (i) => i.category === 'EXAMINATION');
const payParaclinical = (visitId) => payWhere(visitId, (i) => i.category === 'LABORATORY' || i.category === 'IMAGING');
async function admit(petId, doctorId, emergency = false) {   // tiếp đón + nộp phí khám -> có phiếu, vào hàng đợi Bác sĩ
  const id = (await checkIn(petId, doctorId, emergency)).data.id;
  if (!emergency) await payExamFee(id);
  return id;
}
const doctorCallNext = (token) => call('POST', `${CLIN}/queue-tickets/call-next?queueType=DOCTOR`, { token });
const callTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/call`, { token });
const startTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/start`, { token });
const ticketOf = (queue, visitId) => queue.find((item) => item.visitId === visitId)?.ticketId;
const myQueue = async (token) => (await call('GET', `${CLIN}/pet-visits/my-queue`, { token })).data;

async function main() {
  const tokens = { admin: 'admin', doc2: 'bs_tien', doc3: 'bs_an', rec: 'letan_hoa', lab: 'ktv_lab_minh', img: 'ktv_cdha_dung', owner: 'khach_lan' };
  const sessions = {};
  for (const [key, username] of Object.entries(tokens)) { sessions[key] = await login(username); T[key] = sessions[key].accessToken; }

  step('A. Luồng chuẩn: thu phí khám → phiếu Bác sĩ; thu XN/CĐHA → phiếu phòng; tổng dịch vụ 530.000đ + thuốc 157.500đ');
  const v1 = (await checkIn(1, 2)).data.id;
  const st0 = await statementOf(v1);
  check('check-in: lập phí khám 100.000đ PENDING, ca WAITING, CHƯA có phiếu', (await visitOf(v1)).status === 'WAITING' && st0.invoices.length === 1 && st0.invoices[0].category === 'EXAMINATION' && Number(st0.totalPending) === 100000 && st0.tickets.length === 0, JSON.stringify(st0).slice(0, 300));
  check('chưa nộp phí khám: không có trong hàng đợi bác sĩ', !(await myQueue(T.doc2)).some((v) => v.id === v1));
  const noTicket = await startExam(T.doc2, v1);
  check('chưa nộp phí khám: bắt đầu khám bị từ chối CLIN_1314', noTicket.code === 'CLIN_1314', brief(noTicket));
  check('ca nằm ở mục thu ngân "phí khám"', (await call('GET', `${CLIN}/cashier/pending?section=EXAM_FEE`, { token: T.rec })).data.some((v) => v.id === v1));
  const fee = await payExamFee(v1);
  const doctorTicket = fee.data?.tickets?.find((t) => t.queueType === 'DOCTOR');
  check('thu phí khám → phát phiếu DOCTOR cùng giao dịch, ca vẫn WAITING', fee.status === 200 && doctorTicket && doctorTicket.ticketNo >= 1 && fee.data.visitStatus === 'WAITING', brief(fee));
  check('thu phí khám lần hai → 409 BILL_1403', (await pay(v1, [st0.invoices[0].id])).code === 'BILL_1403');
  check('ca vào hàng đợi bác sĩ #2 kèm số phiếu', (await myQueue(T.doc2)).find((v) => v.id === v1)?.ticket?.ticketNo === doctorTicket.ticketNo);
  const called = await doctorCallNext(T.doc2);
  check('bác sĩ gọi số (không đổi trạng thái)', called.data?.visitId === v1 && called.data.ticketNo === doctorTicket.ticketNo && (await visitOf(v1)).status === 'WAITING', brief(called));
  check('bắt đầu khám (T02): phiếu → IN_SERVICE, không lập hóa đơn mới', (await startExam(T.doc2, v1)).status === 200 && (await statementOf(v1)).invoices.length === 1);
  const ordered = await call('POST', `${CLIN}/pet-visits/${v1}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5, 7] } });
  check('chỉ định CBC + X-quang (T04): WAITING_RESULTS', ordered.data?.status === 'WAITING_RESULTS', JSON.stringify(ordered).slice(0, 200));
  check('chưa nộp tiền XN/CĐHA: hàng đợi Lab và CĐHA chưa có ca này', !(await labQueue()).some((i) => i.visitId === v1) && !(await imagingQueue()).some((i) => i.visitId === v1));
  check('ca nằm ở mục thu ngân "XN / CĐHA"', (await call('GET', `${CLIN}/cashier/pending?section=PARACLINIC`, { token: T.rec })).data.some((v) => v.id === v1));
  const cls = await payParaclinical(v1);
  check('thu CBC + X-quang → phát phiếu Lab và phiếu CĐHA', cls.status === 200 && ['LABORATORY', 'IMAGING'].every((q) => cls.data.tickets.some((t) => t.queueType === q)), brief(cls));
  const labT = ticketOf(await labQueue(), v1);
  check('Lab gọi số → đúng phiếu', (await call('POST', `${CLIN}/queue-tickets/call-next?queueType=LABORATORY`, { token: T.lab })).data?.id === labT);
  check('Lab bắt đầu phiếu', (await startTicket(T.lab, labT)).data?.status === 'IN_SERVICE');
  const labDetail = detailOf(await labQueue(), v1);
  await call('POST', `${CLIN}/service-details/${labDetail}/sample-collected`, { token: T.lab });
  check('nhập kết quả xét nghiệm', (await labResults(labDetail)).status === 200);
  const imgT = ticketOf(await imagingQueue(), v1);
  check('CĐHA gọi số', (await call('POST', `${CLIN}/queue-tickets/call-next?queueType=IMAGING`, { token: T.img })).data?.id === imgT);
  await startTicket(T.img, imgT);
  const imgDetail = detailOf(await imagingQueue(), v1);
  check('nhập kết quả CĐHA kèm ảnh', (await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imagingForm() })).status === 200);
  const q2 = await myQueue(T.doc2);
  check('đủ kết quả → RESULTS_READY (T06) + phiếu ưu tiên 1 đứng đầu hàng đợi bác sĩ', (await visitOf(v1)).status === 'RESULTS_READY' && q2[0]?.id === v1 && q2[0]?.ticket?.priority === 1);
  check('phiếu phòng Lab / CĐHA đã DONE (không còn trong hàng đợi phòng)', !(await labQueue()).some((i) => i.visitId === v1) && !(await imagingQueue()).some((i) => i.visitId === v1));
  await doctorCallNext(T.doc2);
  check('bắt đầu giai đoạn 2 (T07)', (await call('POST', `${CLIN}/pet-visits/${v1}/start-phase2`, { token: T.doc2 })).status === 200);
  const stock0 = await stockOf(1);
  const prescription = await call('POST', `${PHARM}/prescriptions`, { token: T.doc2, body: rx(v1, 1, 10) });
  check('kê đơn 10 Clavamox (không giá, không trạng thái)', prescription.status === 201 && prescription.data.stockWarnings.length === 0 && !('status' in prescription.data));
  check('chốt ca (T09) → WAITING_PAYMENT', (await close(T.doc2, v1)).data?.status === 'WAITING_PAYMENT');
  const statement = await statementOf(v1);
  check('3 hóa đơn dịch vụ (khám, CBC, X-quang) đã thu trước đủ 530.000đ, không còn khoản chờ', statement.invoices.length === 3 && Number(statement.totalPaid) === 530000 && Number(statement.totalPending) === 0, JSON.stringify(statement).slice(0, 300));
  check('VIEW v_service_invoice_amounts = 530.000', Number(sql(`SELECT SUM(payable_amount) FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE visit_id=${v1}`)) === 530000);
  const mi = await call('POST', `${PHARM}/prescriptions/${prescription.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: prescription.data.items[0].id, quantity: 10 }], discountAmount: 0 } });
  check('hóa đơn thuốc 157.500đ (10 × 15.000 × 1,05)', mi.status === 201 && Number(mi.data.payableAmount) === 157500);
  check('VIEW v_medicine_invoice_amounts = 157.500', Number(sql(`SELECT payable_amount FROM pet_clinic_billing_pharmacy_db.v_medicine_invoice_amounts WHERE invoice_id=${mi.data.id}`)) === 157500);
  check('thu tiền thuốc: 200 → 190, trạng thái ca không đổi', (await call('POST', `${BILL}/medicine-invoices/${mi.data.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(1)) === stock0 - 10 && (await visitOf(v1)).status === 'WAITING_PAYMENT');
  check('bất biến SUM(inventory_transactions.quantity) = stock_quantity', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=1) - stock_quantity FROM pet_clinic_billing_pharmacy_db.medicines WHERE id=1`) === '0');
  const finished = await pay(v1, []);
  check('không còn gì để thu: thu ngân xác nhận (danh sách rỗng) → COMPLETED (T10, AD-03)', finished.status === 200 && (await visitOf(v1)).status === 'COMPLETED', brief(finished));

  step('A2. Dịch vụ làm tại phòng thu khi chốt ca; cấp cứu khám trước thu sau; hủy XN do phòng khám → hoàn tiền');
  const pIn = await newPet('E2E-inroom');
  const vIn = await admit(pIn, 2);
  await doctorCallNext(T.doc2); await startExam(T.doc2, vIn);
  await call('POST', `${CLIN}/pet-visits/${vIn}/in-room-services`, { token: T.doc2, body: { services: [{ serviceId: 3, quantity: 1 }] } });
  const vac = (await statementOf(vIn)).invoices.find((i) => i.category !== 'EXAMINATION');
  const early = await pay(vIn, [vac.id]);
  check('dịch vụ tại phòng chưa chốt ca → 409 BILL_1409', vac && !vac.requiresPrepayment && early.code === 'BILL_1409', brief(early));
  const vacDetail = (await visitOf(vIn)).services.find((d) => d.id === vac.visitServiceDetailId).id;
  await call('POST', `${CLIN}/service-details/${vacDetail}/perform`, { token: T.doc2 });
  await close(T.doc2, vIn);
  check('chốt ca rồi thu dịch vụ tại phòng → COMPLETED', (await pay(vIn, [vac.id])).data?.visitStatus === 'COMPLETED');

  const pEm = await newPet('E2E-emergency');
  const vEm = (await checkIn(pEm, 3, true)).data.id;
  const emQ = await myQueue(T.doc3);
  check('ca cấp cứu: có phiếu ngay (priority 2) dù chưa nộp phí, đứng đầu hàng đợi', emQ[0]?.id === vEm && emQ[0]?.ticket?.priority === 2);
  check('ca cấp cứu bắt đầu khám được ngay', (await startExam(T.doc3, vEm)).status === 200);
  await call('POST', `${CLIN}/pet-visits/${vEm}/paraclinical-orders`, { token: T.doc3, body: { serviceIds: [5] } });
  const emLab = (await labQueue()).find((i) => i.visitId === vEm);
  check('ca cấp cứu: phiếu Lab phát ngay, priority 2, chưa thu tiền', emLab?.ticketPriority === 2);
  const cancelClinic = await call('POST', `${CLIN}/service-details/${emLab.serviceDetailId}/cancel`, { token: T.lab, body: { reason: 'Máy xét nghiệm hỏng', reasonType: 'CLINIC' } });
  check('hủy XN chưa thu (do phòng khám) → hóa đơn CANCELLED, ca RESULTS_READY', cancelClinic.status === 200 && (await statementOf(vEm)).invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'CANCELLED' && (await visitOf(vEm)).status === 'RESULTS_READY', brief(cancelClinic));
  await call('POST', `${CLIN}/pet-visits/${vEm}/abandon`, { token: T.rec });

  const pRf = await newPet('E2E-refund');
  const vRf = await admit(pRf, 3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, vRf);
  await call('POST', `${CLIN}/pet-visits/${vRf}/paraclinical-orders`, { token: T.doc3, body: { serviceIds: [5, 7] } });
  await payParaclinical(vRf);
  const rfLab = detailOf(await labQueue(), vRf); const rfImg = detailOf(await imagingQueue(), vRf);
  await call('POST', `${CLIN}/service-details/${rfImg}/cancel`, { token: T.img, body: { reason: 'Máy X-quang hỏng', reasonType: 'CLINIC' } });
  await call('POST', `${CLIN}/service-details/${rfLab}/cancel`, { token: T.lab, body: { reason: 'Khách đổi ý', reasonType: 'CUSTOMER' } });
  const rfSt = await statementOf(vRf);
  check('đã thu: hủy do phòng khám → REFUNDED; khách tự hủy → giữ PAID', rfSt.invoices.find((i) => i.category === 'IMAGING').paymentStatus === 'REFUNDED' && rfSt.invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'PAID' && Number(rfSt.totalRefunded) === 180000, JSON.stringify(rfSt.invoices.map((i) => i.paymentStatus)));
  await call('POST', `${CLIN}/pet-visits/${vRf}/abandon`, { token: T.rec });

  step('B. EX-15 (thiếu kho → rollback, sửa hóa đơn, thu lại) và EX-16 (thu trùng, kể cả đồng thời)');
  const p2 = await newPet('E2E-ex15');
  const v2 = await admit(p2, 3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, v2);
  const rx2 = await call('POST', `${PHARM}/prescriptions`, { token: T.doc3, body: rx(v2, 4, 10) });   // Drontal tồn 100
  await close(T.doc3, v2);
  const stock4 = await stockOf(4);
  const mi2 = await call('POST', `${PHARM}/prescriptions/${rx2.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: rx2.data.items[0].id, quantity: 10 }] } });
  // kho giảm giữa lúc lập hóa đơn và lúc thu tiền (bán chỗ khác, kiểm kê...): lúc lập còn đủ nên được lập, lúc thu mới thiếu
  await call('POST', `${PHARM}/inventory/adjust`, { token: T.admin, body: { medicineId: 4, delta: -(stock4 - 5), reason: 'e2e đưa tồn về 5' } });
  const lack = await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec });
  check('EX-15: thiếu kho → 409 PHARM_1406', lack.status === 409 && lack.code === 'PHARM_1406');
  check('EX-15: tồn vẫn 5, không có dòng EXPORT_SALE, hóa đơn vẫn PENDING', (await stockOf(4)) === 5 && sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_invoice_id=${mi2.data.id}`) === '0' && (await call('GET', `${BILL}/medicine-invoices/${mi2.data.id}`, { token: T.rec })).data.paymentStatus === 'PENDING');
  const tooMuch = await call('PUT', `${BILL}/medicine-invoices/${mi2.data.id}/items`, { token: T.rec, body: { items: [{ prescriptionItemId: rx2.data.items[0].id, quantity: 6 }] } });
  check('sửa hóa đơn: không bán quá tồn kho (kho còn 5, sửa lên 6 → 409 PHARM_1406)', tooMuch.status === 409 && tooMuch.code === 'PHARM_1406', brief(tooMuch));
  await call('PUT', `${BILL}/medicine-invoices/${mi2.data.id}/items`, { token: T.rec, body: { items: [{ prescriptionItemId: rx2.data.items[0].id, quantity: 5 }] } });
  check('EX-15: sửa về 5 viên rồi thu lại → PAID, tồn 0', (await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(4)) === 0);
  check('EX-16: thu lần hai → 409 BILL_1403', (await call('POST', `${BILL}/medicine-invoices/${mi2.data.id}/confirm-payment`, { token: T.rec })).code === 'BILL_1403');
  // thu đồng thời trên hóa đơn khác
  await call('POST', `${PHARM}/inventory/import`, { token: T.admin, body: { medicineId: 4, quantity: 50, notes: 'e2e' } });
  const p3 = await newPet('E2E-ex16');
  const v3 = await admit(p3, 3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, v3);
  const rx3 = await call('POST', `${PHARM}/prescriptions`, { token: T.doc3, body: rx(v3, 4, 10) });
  await close(T.doc3, v3);
  const stockNow = await stockOf(4);
  const overStock = await call('POST', `${PHARM}/prescriptions/${rx3.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: rx3.data.items[0].id, quantity: stockNow + 1 }] } });
  check('lập hóa đơn: không bán quá tồn kho (409 PHARM_1406)', overStock.status === 409 && overStock.code === 'PHARM_1406', brief(overStock));
  const mi3 = await call('POST', `${PHARM}/prescriptions/${rx3.data.id}/medicine-invoices`, { token: T.rec, body: { items: [{ prescriptionItemId: rx3.data.items[0].id, quantity: 12 }] } });
  check('số lượng bác sĩ kê chỉ để tham khảo: kê 10, khách mua 12 (≤ tồn) vẫn lập được', mi3.status === 201 && mi3.data.items[0].quantity === 12 && mi3.data.items[0].prescribedQuantity === 10, brief(mi3));
  check('hóa đơn trả kèm tồn kho hiện tại để màn hình bán thuốc hiển thị', mi3.data.items[0].stockQuantity === stockNow, brief(mi3));
  const before = await stockOf(4);
  const race = await Promise.all([1, 2, 3, 4, 5].map(() => call('POST', `${BILL}/medicine-invoices/${mi3.data.id}/confirm-payment`, { token: T.rec })));
  check('EX-16 đồng thời: đúng 1/5 yêu cầu thu được, 4 bị từ chối 409', race.filter((r) => r.status === 200).length === 1 && race.filter((r) => r.status === 409).length === 4, race.map((r) => r.status).join(','));
  check('EX-16 đồng thời: kho chỉ trừ đúng một lần', (await stockOf(4)) === before - 12);
  check('bất biến kho giữ nguyên sau EX-15/16 (thuốc #4)', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=4) - stock_quantity FROM pet_clinic_billing_pharmacy_db.medicines WHERE id=4`) === '0');

  step('C. Đua RESULTS_READY: hai KTV hoàn tất cùng lúc (không kẹt WAITING_RESULTS); thu phí khám đồng thời không phát trùng số');
  let stuck = 0;
  for (let round = 1; round <= 3; round += 1) {
    const pet = await newPet(`E2E-race-${round}`);
    const v = await admit(pet, 2);
    await doctorCallNext(T.doc2); await startExam(T.doc2, v);
    await call('POST', `${CLIN}/pet-visits/${v}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5, 7] } });
    await payParaclinical(v);
    await startTicket(T.lab, ticketOf(await labQueue(), v)); await startTicket(T.img, ticketOf(await imagingQueue(), v));
    const lab = detailOf(await labQueue(), v); const img = detailOf(await imagingQueue(), v);
    await Promise.all([labResults(lab), call('POST', `${CLIN}/imaging-results/${img}`, { token: T.img, form: imagingForm() })]);
    if ((await visitOf(v)).status !== 'RESULTS_READY') stuck += 1;
    await call('POST', `${CLIN}/pet-visits/${v}/abandon`, { token: T.rec });   // dọn: khách bỏ về → COMPLETED
  }
  check('3 vòng hoàn tất đồng thời: không ca nào kẹt ở WAITING_RESULTS', stuck === 0, `kẹt ${stuck}/3`);
  const racePets = []; for (let i = 1; i <= 6; i += 1) racePets.push(await newPet(`E2E-fee-${i}`));
  const raceVisits = []; for (const pet of racePets) raceVisits.push((await checkIn(pet, null)).data.id);
  const fees = await Promise.all(raceVisits.map((id) => payExamFee(id)));
  const numbers = fees.map((r) => r.data?.tickets?.find((t) => t.queueType === 'DOCTOR')?.ticketNo);
  check('6 quầy thu phí khám cùng lúc: đều thành công, 6 số phiếu khác nhau', fees.every((r) => r.status === 200) && new Set(numbers).size === 6 && numbers.every(Boolean), numbers.join(',') + ' ' + fees.map(brief).join(' | '));
  for (const id of raceVisits) await call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'e2e dọn' } });

  step('D. Gọi theo từng phiếu (BR-34): phiếu vắng giữ nguyên vị trí, phiếu thứ 3, 4 vẫn gọi được');
  const pets = []; for (let i = 1; i <= 4; i += 1) pets.push(await newPet(`E2E-q${i}`));
  const queue = []; for (const pet of pets) { queue.push(await admit(pet, 3)); await sleep(5); }
  const order = async () => (await myQueue(T.doc3)).map((v) => v.id);
  const tickets = Object.fromEntries((await myQueue(T.doc3)).map((v) => [v.id, v.ticket.id]));
  const [A, B, C, D] = queue;
  check('hàng đợi ban đầu theo giờ phát phiếu [A,B,C,D]', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  const c1 = await doctorCallNext(T.doc3);
  check('"Gọi số tiếp theo" gọi phiếu đầu hàng (A)', c1.status === 200 && c1.data?.visitId === A, brief(c1));
  const cB = await callTicket(T.doc3, tickets[B]);
  const cC = await callTicket(T.doc3, tickets[C]);
  const cD = await callTicket(T.doc3, tickets[D]);
  check('A, B, C vắng: bác sĩ gọi tiếp B, C rồi D (phiếu thứ 4) đều được, không bị kẹt', [cB, cC, cD].every((r) => r.status === 200) && cD.data?.visitId === D, brief(cD));
  check('gọi không đổi thứ tự hàng đợi (vẫn [A,B,C,D]) và lưu giờ gọi trên phiếu', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]) && (await myQueue(T.doc3)).every((v) => v.ticket.lastCalledAt));
  const callAgain = await callTicket(T.doc3, tickets[A]);
  check('khách A quay lại: "Gọi lại" phiếu A được, thứ tự vẫn không đổi', callAgain.status === 200 && JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  check('bác sĩ khác không gọi được phiếu của bác sĩ này → CLIN_1311', (await callTicket(T.doc2, tickets[A])).code === 'CLIN_1311');
  check('API gọi số cũ theo đợt khám đã bỏ', (await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc3 })).status >= 400);
  check('cột queue_order đã bị xóa khỏi lược đồ (hàng đợi ở bảng queue_tickets)', Number(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='pet_clinic_clinical_db' AND column_name='queue_order'")) === 0);
  for (const id of queue) await call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'e2e dọn' } });
  check('hủy tiếp nhận: phiếu bị hủy, hàng đợi trống', (await order()).length === 0);

  step('E. Công nợ: khách bỏ về → COMPLETED, thu bù sau (dịch vụ tại phòng chưa thu)');
  const p5 = await newPet('E2E-debt');
  const v5 = await admit(p5, 2);
  await doctorCallNext(T.doc2); await startExam(T.doc2, v5);
  await call('POST', `${CLIN}/pet-visits/${v5}/in-room-services`, { token: T.doc2, body: { services: [{ serviceId: 3, quantity: 1 }] } });
  const d5 = (await visitOf(v5)).services.find((d) => d.category !== 'EXAMINATION').id;
  await call('POST', `${CLIN}/service-details/${d5}/perform`, { token: T.doc2 });
  await call('POST', `${CLIN}/pet-visits/${v5}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [5] } });
  const abandoned = await call('POST', `${CLIN}/pet-visits/${v5}/abandon`, { token: T.rec });
  check('khách bỏ về khi chưa nộp tiền XN: ca COMPLETED, XN hủy kèm hóa đơn', abandoned.data?.status === 'COMPLETED' && (await statementOf(v5)).invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'CANCELLED', brief(abandoned));
  check('ca xuất hiện ở danh sách công nợ (dịch vụ tại phòng đã làm chưa thu)', (await call('GET', `${CLIN}/pet-visits/with-debt`, { token: T.rec })).data.some((v) => v.id === v5));
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
