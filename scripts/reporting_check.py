"""Kiểm tra THẬT bước 7.1 (MySQL thật + RabbitMQ thật + Redis thật, không cần dựng cả hệ thống Docker): reporting-service dựng mô hình đọc
báo cáo (CQRS) từ sự kiện: visit_facts, revenue_facts (SALE / REFUND âm), daily_revenue_summaries (cộng dồn nguyên tử), doctor_daily_performances,
idempotent (giao lại không cộng đôi), đổi tên Bác sĩ / chủ nuôi / thú cưng lan sang báo cáo, sự kiện không xử lý được -> thử lại 3 lần -> DLQ.
  python scripts/reporting_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh reporting-service --reset), Docker, JDK 21 và jar của auth-service /
clinical-service / billing-pharmacy-service / reporting-service trong <module>/target (mvn -o -pl ... install -DskipTests).
Dọn bảng báo cáo ở ĐẦU mỗi lần chạy (chạy lại nhiều lần được); trả lại tên Bác sĩ / chủ nuôi và tồn kho ở cuối (kể cả khi lỗi)."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT, RABBIT_UI = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673", "15673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"
PORTS = {"auth-service": 18081, "clinical-service": 18083, "billing-pharmacy-service": 18084, "reporting-service": 18086}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien: Bác sĩ phụ trách mọi ca thử
BASE = "/api/v1/clinical/pet-visits"
CLIN = "/api/v1/clinical"
BILL = "/api/v1/billing/medicine-invoices"
CDB, BDB, RDB = "pet_clinic_clinical_db", "pet_clinic_billing_pharmacy_db", "pet_clinic_reporting_db"
TODAY = datetime.now(timezone(timedelta(hours=7))).strftime("%Y-%m-%d")   # ngày nghiệp vụ theo giờ phòng khám


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return (subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or "").strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query)


def b(uid):
    return f"UNHEX(REPLACE('{uid}','-',''))"


def uuid_of(hex32):
    h = hex32.lower()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def call(service, method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(f"http://localhost:{PORTS[service]}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:  # tự đóng vai Gateway: gắn header danh tính từ claim của JWT
        c = claims(token)
        req.add_header("X-User-Id", c["userId"])
        req.add_header("X-User-Roles", ",".join(c["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def clin(method, path, token=None, body=None):
    return call("clinical-service", method, path, token, body)


def bill(method, path, token=None, body=None):
    return call("billing-pharmacy-service", method, path, token, body)


def login(username):
    st, res = call("auth-service", "POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"})
    return res["data"]["accessToken"]


def wait_for(fn, seconds=25):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


def rabbit_api(method, path, body=None):
    auth = base64.b64encode(b"vetclinic:vetclinic_dev").decode()
    req = urllib.request.Request(f"http://localhost:{RABBIT_UI}/api{path}", data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.loads(res.read() or b"null")


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"reporting-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT, REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false"]
    for name, port in PORTS.items():
        args.append(f"--spring.cloud.discovery.client.simple.instances.{name}[0].uri=http://localhost:{port}")
    proc = subprocess.Popen(args, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


def dec(value):
    return Decimal(value) if value not in ("", "NULL", None) else Decimal("0")


saved_stock, restore = {}, []
AMOX = None
try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh reporting-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine")
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-p", f"{RABBIT_UI}:15672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    # bảng báo cáo dọn trước khi chạy: mọi dòng sau đây đều do chính lần chạy này sinh ra
    for table in ("visit_facts", "revenue_facts", "daily_revenue_summaries", "doctor_daily_performances", "processed_events"):
        sql(f"DELETE FROM {RDB}.{table}")
    sql(f"DELETE FROM {CDB}.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1 (UNIQUE loại + ngày + số)
    sql(f"UPDATE {CDB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id = {b(TIEN)} AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    for module in ("reporting-service", "billing-pharmacy-service", "clinical-service", "auth-service"):   # reporting khai báo queue trước khi service khác phát sự kiện
        start_jar(module)

    # ================================================================== hạ tầng: 8 queue + 8 DLQ, mỗi queue 1 consumer
    queues = {q["name"]: q for q in rabbit_api("GET", "/queues")}
    expected = ["visit.checked_in", "visit.completed", "visit.cancelled", "invoice.service_paid", "invoice.service_refunded",
                "pharmacy.medicine_invoice_paid", "user.profile_updated", "pet.updated"]
    check("RabbitMQ: 8 queue reporting-service.<routing-key> bền vững, mỗi queue có 1 consumer",
          all(f"reporting-service.{k}" in queues and queues[f"reporting-service.{k}"]["durable"] and queues[f"reporting-service.{k}"]["consumers"] == 1 for k in expected),
          [k for k in expected if f"reporting-service.{k}" not in queues])
    check("RabbitMQ: mỗi queue có DLQ <queue>.dlq", all(f"reporting-service.{k}.dlq" in queues for k in expected))

    rec, tien, lan, admin = login("letan_hoa"), login("bs_tien"), login("khach_lan"), login("admin")
    lan_id = claims(lan)["userId"]
    XRAY = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'IMG_XRAY'"))
    AMOX = uuid_of(sql(f"SELECT HEX(id) FROM {BDB}.medicines WHERE code = 'MED_AMOX'"))
    saved_stock = {AMOX: int(sql(f"SELECT quantity_on_hand FROM {BDB}.medicine_stocks WHERE medicine_id = {b(AMOX)}"))}
    sql(f"UPDATE {BDB}.medicine_stocks SET quantity_on_hand = 200 WHERE medicine_id = {b(AMOX)}")
    tien_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(TIEN)}")
    lan_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(lan_id)}")
    species_code = sql(f"SELECT code FROM {CDB}.species WHERE id = 1")

    pets = {}

    def new_visit(label, emergency=False):
        st, res = clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"})
        pet = res["data"]["id"]
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": pet, "doctorId": TIEN, "isEmergency": emergency, "reasonForVisit": label})
        assert st in (200, 201), (st, res)
        pets[res["data"]["id"]] = (pet, f"{label}-{SUFFIX}")
        return res["data"]["id"]

    def statement(vid):
        return clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]

    def pay(vid, predicate=lambda i: True):
        ids = [i["id"] for i in statement(vid)["invoices"] if i["paymentStatus"] == "PENDING" and predicate(i)]
        return clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})

    def wait_ticket(vid):
        return wait_for(lambda: statement(vid)["tickets"], 30)

    def start_exam(vid):
        assert wait_ticket(vid), "phiếu không xuất hiện"
        st, res = clin("POST", f"{BASE}/{vid}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})
        assert st == 200, (st, res)

    def fact(vid):
        return sql(f"SELECT current_status, is_emergency, pet_name, species_code, owner_name, doctor_name, business_date, completed_at IS NOT NULL "
                   f"FROM {RDB}.visit_facts WHERE visit_id = {b(vid)}").split("\t")

    # ================================================================== V1: ca thường, có đơn thuốc -> thu tiền thuốc -> hoàn tất
    v1 = new_visit("Rpt1")
    row = wait_for(lambda: fact(v1) if sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE visit_id = {b(v1)}") == "1" else None)
    check("VisitCheckedInEvent -> visit_facts: WAITING, tên thú cưng / loài / chủ nuôi / Bác sĩ, ngày nghiệp vụ hôm nay",
          bool(row) and row[:7] == ["WAITING", "0", pets[v1][1], species_code, lan_name, tien_name, TODAY] and row[7] == "0", row)

    check("thu phí khám -> 200", pay(v1)[0] == 200)
    exam = wait_for(lambda: sql(f"SELECT category_code, entry_type, doctor_name FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)} AND revenue_type = 'SERVICE'"))
    check("ServiceInvoicePaidEvent -> revenue_facts: 1 dòng SERVICE / SALE, nhóm EXAMINATION, gắn tên Bác sĩ", exam == f"EXAMINATION\tSALE\t{tien_name}", exam)
    exam_row = sql(f"SELECT amount_before_vat, vat_amount, discount_amount, net_amount, business_date FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)}").split("\t")
    expected_exam = sql(f"SELECT amount_before_vat, vat_amount, discount_amount, payable_amount FROM {CDB}.v_service_invoice_amounts WHERE visit_id = {b(v1)}").split("\t")
    check("số tiền dòng phí khám khớp VIEW của clinical (trước VAT, VAT, giảm giá, phải trả)", [dec(x) for x in exam_row[:4]] == [dec(x) for x in expected_exam] and exam_row[4] == TODAY, (exam_row, expected_exam))
    cat_name = sql(f"SELECT category_name FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)}")
    check("tên nhóm dịch vụ lấy từ sự kiện (không phải mã nhóm)", cat_name == sql(f"SELECT sc.name FROM {CDB}.service_categories sc WHERE sc.code = 'EXAMINATION'"), cat_name)

    start_exam(v1)
    st, res = clin("PUT", f"{BASE}/{v1}/prescription", tien, {"items": [{"medicineId": AMOX, "quantity": 10, "dosage": "1 viên", "frequency": "2 lần/ngày", "durationDays": 5}]})
    check("Bác sĩ kê đơn Clavamox x10 -> 200", st == 200, (st, res))
    check("chốt ca -> 200", clin("POST", f"{BASE}/{v1}/close-visit", tien, {"finalDiagnosis": "Viêm da"})[0] == 200)
    draft = wait_for(lambda: (bill("GET", f"{BILL}?visitId={v1}", rec)[1].get("data") or [None])[0], 30)
    check("hóa đơn thuốc nháp xuất hiện (PrescriptionCreatedEvent)", bool(draft))
    st, res = bill("POST", f"{BILL}/{draft['id']}/confirm-payment", rec)
    check("thu tiền thuốc + trừ kho -> 200", st == 200, (st, res))
    med = wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts WHERE source_invoice_id = {b(draft['id'])}") == "1")
    check("MedicineInvoicePaidEvent -> revenue_facts: 1 dòng MEDICINE / SALE cho Clavamox", bool(med))
    med_row = sql(f"SELECT revenue_type, entry_type, item_code, quantity, category_code, net_amount FROM {RDB}.revenue_facts WHERE source_invoice_id = {b(draft['id'])}").split("\t")
    payable = sql(f"SELECT payable_amount FROM {BDB}.v_medicine_invoice_amounts WHERE invoice_id = {b(draft['id'])}")
    check("dòng thuốc: số lượng 10, số phải trả khớp VIEW của billing (157.500)", med_row[:4] == ["MEDICINE", "SALE", "MED_AMOX", "10"] and dec(med_row[5]) == dec(payable) == Decimal("157500"), (med_row, payable))
    check("nhóm thuốc đến từ sự kiện (mã nhóm khác rỗng)", med_row[4] not in ("", "UNKNOWN"), med_row)

    check("thu nốt T10 (không còn hóa đơn dịch vụ chờ) -> ca COMPLETED", clin("POST", f"{BASE}/{v1}/service-invoices/confirm-payment", rec, {"invoiceIds": []})[0] == 200)
    done = wait_for(lambda: fact(v1) if fact(v1)[0] == "COMPLETED" else None)
    check("VisitCompletedEvent -> visit_facts COMPLETED, có completed_at", bool(done) and done[7] == "1", done)

    # ================================================================== V2: cấp cứu (thu sau) -> hoàn tất
    v2 = new_visit("Rpt2", emergency=True)
    start_exam(v2)
    check("chốt ca cấp cứu -> 200", clin("POST", f"{BASE}/{v2}/close-visit", tien, {"finalDiagnosis": "Chấn thương"})[0] == 200)
    check("thu phí khám cấp cứu (thu sau) + hoàn tất -> 200", pay(v2)[0] == 200)
    done2 = wait_for(lambda: fact(v2) if fact(v2)[0] == "COMPLETED" else None)
    check("ca cấp cứu: visit_facts COMPLETED, is_emergency = 1", bool(done2) and done2[1] == "1", done2)

    # ================================================================== V3: hủy tiếp nhận
    v3 = new_visit("Rpt3")
    check("Lễ tân hủy tiếp nhận -> 200", clin("POST", f"{BASE}/{v3}/cancel", rec, {"reason": "Khách bỏ về"})[0] == 200)
    cancelled = wait_for(lambda: fact(v3) if len(fact(v3)) > 1 and fact(v3)[0] == "CANCELLED" else None)
    check("VisitCancelledEvent -> visit_facts CANCELLED", bool(cancelled), cancelled)

    # ================================================================== V4: X-quang đã thu rồi hoàn (phòng khám không làm được) -> khách bỏ về
    v4 = new_visit("Rpt4")
    check("thu phí khám V4 -> 200", pay(v4)[0] == 200)
    start_exam(v4)
    st, res = clin("POST", f"{BASE}/{v4}/paraclinical-orders", tien, {"serviceIds": [XRAY], "notes": "kiểm tra"})
    check("chỉ định X-quang -> 200", st == 200, (st, res))
    check("thu tiền X-quang -> 200", pay(v4)[0] == 200)
    xray = wait_for(lambda: sql(f"SELECT entry_type, category_code, net_amount FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND category_code = 'IMAGING'"))
    check("dòng thu X-quang vào revenue_facts (SERVICE / SALE, nhóm IMAGING)", bool(xray) and xray.startswith("SALE\tIMAGING"), xray)
    detail = sql(f"SELECT HEX(vsd.id) FROM {CDB}.visit_service_details vsd JOIN {CDB}.services s ON s.id = vsd.service_id WHERE vsd.visit_id = {b(v4)} AND s.code = 'IMG_XRAY'")
    st, res = clin("POST", f"{CLIN}/service-details/{uuid_of(detail)}/cancel", tien, {"reason": "Máy X-quang hỏng", "reasonType": "CLINIC"})
    check("hủy X-quang do phòng khám (đã thu) -> hoàn tiền, 200", st == 200, (st, res))
    refund = wait_for(lambda: sql(f"SELECT quantity, net_amount, amount_before_vat, business_date FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND entry_type = 'REFUND'"))
    sale = sql(f"SELECT quantity, net_amount, amount_before_vat FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND entry_type = 'SALE' AND category_code = 'IMAGING'").split("\t")
    r = (refund or "\t\t\t").split("\t")
    check("ServiceInvoiceRefundedEvent -> dòng REFUND: số lượng và tiền ÂM đúng bằng dòng thu", bool(refund) and int(r[0]) == -int(sale[0]) and dec(r[1]) == -dec(sale[1]) and dec(r[2]) == -dec(sale[2]) and dec(r[1]) < 0, (refund, sale))
    check("thu - hoàn của X-quang = 0 trong revenue_facts", dec(sql(f"SELECT SUM(net_amount) FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND category_code = 'IMAGING'")) == 0)
    check("khách bỏ về (ca hết chỉ định CLS) -> 200", clin("POST", f"{BASE}/{v4}/abandon", tien)[0] == 200)
    done4 = wait_for(lambda: fact(v4) if fact(v4)[0] == "COMPLETED" else None)
    check("ca bỏ về: visit_facts COMPLETED", bool(done4), done4)

    # ================================================================== bất biến: tổng hợp = tổng các dòng fact; khớp nguồn gốc
    time.sleep(2)
    visits = ",".join(b(v) for v in (v1, v2, v4))
    reported = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'SERVICE' AND visit_id IN ({visits})"))
    source = dec(sql(f"SELECT IFNULL(SUM(payable_amount), 0) FROM {CDB}.v_service_invoice_amounts WHERE payment_status = 'PAID' AND visit_id IN ({visits})"))
    check("doanh thu dịch vụ của 3 ca trong báo cáo (thu trừ hoàn) = tổng hóa đơn PAID trong clinical", reported == source and reported > 0, (reported, source))
    facts_by_group = sql(f"SELECT business_date, revenue_type, category_code, SUM(net_amount), SUM(vat_amount), SUM(discount_amount), SUM(amount_before_vat), "
                         f"SUM(CASE entry_type WHEN 'SALE' THEN 1 ELSE -1 END) FROM {RDB}.revenue_facts GROUP BY business_date, revenue_type, category_code ORDER BY 1, 2, 3")
    summary_by_group = sql(f"SELECT business_date, revenue_type, category_code, net_amount, vat_amount, discount_amount, amount_before_vat, line_count "
                           f"FROM {RDB}.daily_revenue_summaries ORDER BY 1, 2, 3")
    norm = lambda text: [[x if i < 3 else str(dec(x)) for i, x in enumerate(line.split("\t"))] for line in text.splitlines()]
    check("daily_revenue_summaries khớp GROUP BY của revenue_facts (từng ngày / loại / nhóm: tiền, VAT, giảm giá, số dòng)", bool(summary_by_group) and norm(facts_by_group) == norm(summary_by_group), (facts_by_group, summary_by_group))
    check("tổng hợp có cả nhóm thuốc (MEDICINE) và nhóm dịch vụ (SERVICE) hôm nay",
          sql(f"SELECT COUNT(DISTINCT revenue_type) FROM {RDB}.daily_revenue_summaries WHERE business_date = '{TODAY}'") == "2")
    perf = sql(f"SELECT completed_visits, cancelled_visits, emergency_visits, service_net_revenue, doctor_name FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}").split("\t")
    doc_rev = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'SERVICE' AND doctor_id = {b(TIEN)} AND business_date = '{TODAY}'"))
    check("hiệu suất Bác sĩ hôm nay: 3 ca hoàn tất (thường, cấp cứu, bỏ về), 1 ca hủy, 1 ca cấp cứu", perf[:3] == ["3", "1", "1"], perf)
    check("hiệu suất Bác sĩ: doanh thu dịch vụ = tổng revenue_facts SERVICE của Bác sĩ (đã trừ hoàn tiền)", dec(perf[3]) == doc_rev and doc_rev > 0, (perf, doc_rev))

    # ================================================================== EX-19: giao lại sự kiện -> không cộng đôi
    totals_before = sql(f"SELECT (SELECT SUM(net_amount) FROM {RDB}.daily_revenue_summaries), (SELECT COUNT(*) FROM {RDB}.revenue_facts), "
                        f"(SELECT SUM(completed_visits) FROM {RDB}.doctor_daily_performances)")
    sql(f"UPDATE {CDB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE aggregate_id IN ('{v1}', '{v2}', '{v4}') AND routing_key IN ('invoice.service_paid', 'visit.completed', 'invoice.service_refunded')")
    sql(f"UPDATE {BDB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE routing_key = 'pharmacy.medicine_invoice_paid'")
    wait_for(lambda: sql(f"SELECT COUNT(*) FROM {CDB}.outbox_events WHERE status = 'PENDING' AND routing_key IN ('invoice.service_paid', 'visit.completed', 'invoice.service_refunded')") == "0"
             and sql(f"SELECT COUNT(*) FROM {BDB}.outbox_events WHERE status = 'PENDING' AND routing_key = 'pharmacy.medicine_invoice_paid'") == "0", 40)
    time.sleep(4)
    totals_after = sql(f"SELECT (SELECT SUM(net_amount) FROM {RDB}.daily_revenue_summaries), (SELECT COUNT(*) FROM {RDB}.revenue_facts), "
                       f"(SELECT SUM(completed_visits) FROM {RDB}.doctor_daily_performances)")
    check("EX-19: giao lại 4 loại sự kiện thu / hoàn / hoàn tất / thuốc -> tổng hợp, số dòng fact và số ca hoàn tất KHÔNG đổi", totals_before == totals_after and totals_before != "", (totals_before, totals_after))
    dup = sql(f"SELECT COUNT(*) FROM (SELECT event_id, consumer FROM {RDB}.processed_events GROUP BY event_id, consumer HAVING COUNT(*) > 1) x")
    check("processed_events: mỗi (sự kiện, listener) đúng một dòng", dup == "0" and sql(f"SELECT COUNT(DISTINCT consumer) FROM {RDB}.processed_events") == "6", sql(f"SELECT consumer, COUNT(*) FROM {RDB}.processed_events GROUP BY consumer"))

    # ================================================================== đổi tên lan sang báo cáo
    new_doc, new_owner, new_pet = f"BS Đổi {SUFFIX}", f"Lan Đổi {SUFFIX}", f"Pet Đổi {SUFFIX}"
    restore.append(lambda: call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": tien_name}))
    restore.append(lambda: call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": lan_name}))
    check("Admin đổi tên Bác sĩ -> 200", call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": new_doc})[0] == 200)
    renamed = wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.doctor_daily_performances WHERE doctor_id = {b(TIEN)} AND doctor_name = '{new_doc}'") != "0")
    check("UserProfileUpdatedEvent -> tên Bác sĩ mới trên visit_facts, revenue_facts, doctor_daily_performances",
          bool(renamed) and sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE doctor_id = {b(TIEN)} AND doctor_name <> '{new_doc}'") == "0"
          and sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts WHERE doctor_id = {b(TIEN)} AND doctor_name <> '{new_doc}'") == "0")
    check("Admin đổi tên chủ nuôi -> 200", call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": new_owner})[0] == 200)
    check("tên chủ nuôi mới trên visit_facts", bool(wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE owner_id = {b(lan_id)} AND owner_name <> '{new_owner}'") == "0")))
    pet_id = pets[v1][0]
    check("chủ nuôi đổi tên thú cưng -> 200", clin("PUT", f"/api/v1/pets/{pet_id}", lan, {"name": new_pet})[0] == 200)
    check("PetUpdatedEvent -> pet_name mới trên visit_facts", bool(wait_for(lambda: sql(f"SELECT pet_name FROM {RDB}.visit_facts WHERE visit_id = {b(v1)}") == new_pet)))

    # ================================================================== sự kiện không xử lý được (hoàn tiền không có dòng thu) -> thử lại 3 lần -> DLQ, không ghi gì
    bad = {"eventId": "019a9442-d810-7000-8000-00000000f00d", "occurredAt": "2026-11-21T03:00:00Z", "actorUserId": None, "visitId": v1, "visitCode": "VIS-X",
           "invoiceId": "019a9442-d810-7000-8000-00000000beef", "invoiceCode": "SINV-X", "visitServiceDetailId": None, "serviceId": None, "serviceName": "X",
           "category": "IMAGING", "amount": 1000, "reason": "kiểm tra DLQ"}
    rows_before = sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts")
    rabbit_api("POST", "/exchanges/%2F/clinic.events/publish", {"properties": {"content_type": "application/json"}, "routing_key": "invoice.service_refunded",
                                                              "payload": json.dumps(bad), "payload_encoding": "string"})
    in_dlq = wait_for(lambda: next((q["messages"] for q in rabbit_api("GET", "/queues") if q["name"] == "reporting-service.invoice.service_refunded.dlq"), 0) >= 1, 45)
    check("hoàn tiền không có dòng thu: thử lại 3 lần rồi vào DLQ (queue chính trống)", bool(in_dlq))
    check("sự kiện lỗi KHÔNG để lại dòng fact / processed_events (rollback trọn gói)", sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts") == rows_before
          and sql(f"SELECT COUNT(*) FROM {RDB}.processed_events WHERE event_id = {b('019a9442-d810-7000-8000-00000000f00d')}") == "0")
finally:
    for undo in restore:
        try:
            undo()
        except Exception:
            pass
    for mid, qty in saved_stock.items():
        sql(f"UPDATE {BDB}.medicine_stocks SET quantity_on_hand = {qty} WHERE medicine_id = {b(mid)}")
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: reporting-service dựng visit_facts / revenue_facts / tổng hợp / hiệu suất Bác sĩ từ 8 loại sự kiện, idempotent, đổi tên lan sang báo cáo, lỗi vào DLQ — chạy đúng trên MySQL + RabbitMQ thật")
