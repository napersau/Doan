import { useEffect, useState } from 'react';
import Modal from '../common/Modal';
import { PrescriptionView } from './MedicalEntry';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, Field, Input, Notice, Panel, Table } from '../ui';
import { billingApi, pharmacyApi } from '../../api/endpoints';
import useAction from '../../hooks/useAction';
import useAsync from '../../hooks/useAsync';
import { formatDateTime, formatMoney } from '../../lib/format';
import { PAYMENT_STATUS } from '../../lib/labels';

const SELLABLE = ['WAITING_PAYMENT', 'COMPLETED'];

/** In đơn cho khách mua thuốc ngoài (BR-32): không tạo hóa đơn, không đổi gì trong hệ thống. */
function PrintDialog({ prescriptionId, onClose }) {
  const printable = useAsync(() => pharmacyApi.printPrescription(prescriptionId), [prescriptionId]);
  return <Modal title="Đơn thuốc" id="print-prescription" onClose={onClose}>
    <AsyncView state={printable}>{(p) => <div className="ui-stack" style={{ gap: 12 }}>
      <p className="ui-confirm-text">{p.visitCode} · {p.petName} · Kê lúc {formatDateTime(p.createdAt)}</p>
      <PrescriptionView prescription={p} />
      <div className="ui-actions ui-no-print"><Button variant="primary" onClick={() => window.print()}>In đơn</Button><Button onClick={onClose}>Đóng</Button></div>
    </div>}</AsyncView>
  </Modal>;
}

/** Hóa đơn thuốc đã lập: sửa số lượng khi còn chờ thanh toán (xử lý thiếu kho), hủy khi khách đổi ý, thu tiền + trừ kho. */
function InvoiceEditor({ invoiceId, onChanged }) {
  const invoice = useAsync(() => billingApi.medicineInvoice(invoiceId), [invoiceId]);
  const [quantities, setQuantities] = useState({});
  const [discount, setDiscount] = useState('');
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  const data = invoice.data;
  useEffect(() => {
    if (!data) return;
    setQuantities(Object.fromEntries(data.items.map((item) => [item.prescriptionItemId, String(item.quantity)])));
    setDiscount(String(data.discountAmount ?? 0));
  }, [data]);

  const reload = () => { invoice.refresh(); onChanged?.(); };
  const save = async () => {
    const items = data.items.map((item) => ({ prescriptionItemId: item.prescriptionItemId, quantity: Number(quantities[item.prescriptionItemId]) }));
    if (items.some((line) => !Number.isInteger(line.quantity) || line.quantity < 0)) { action.setNotice({ type: 'error', text: 'Số lượng phải là số nguyên không âm (0 = bỏ dòng khỏi hóa đơn).' }); return; }
    if (await action.run(() => billingApi.updateMedicineInvoiceItems(invoiceId, { items, discountAmount: Number(discount) || 0 }), 'Đã cập nhật hóa đơn thuốc.')) reload();
  };

  return <AsyncView state={invoice}>{(inv) => {
    const pending = inv.paymentStatus === 'PENDING';
    return <div className="ui-stack" style={{ gap: 14 }}>
      <Notice notice={action.notice} onClose={action.clear} />
      <Facts items={[['Mã hóa đơn thuốc', inv.invoiceCode], ['Trạng thái', <Badge key="s" map={PAYMENT_STATUS} value={inv.paymentStatus} />], ['Trước VAT', formatMoney(inv.amountBeforeVat)], ['VAT', formatMoney(inv.vatAmount)], ['Giảm giá', formatMoney(inv.discountAmount)],
        ['Phải trả', <strong key="p">{formatMoney(inv.payableAmount)}</strong>], ['Thu lúc', formatDateTime(inv.paidAt)], ['Thu ngân', inv.cashierId ? `#${inv.cashierId}` : null]]} />
      <Table caption="Thuốc trong hóa đơn" rowKey="id" rows={inv.items} columns={[
        { title: 'Thuốc', render: (row) => <><strong>{row.medicineName}</strong><small>{row.medicineCode}</small></> },
        { title: 'Số lượng mua', render: (row) => (pending
          ? <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Input type="number" min="0" max={row.prescribedQuantity} aria-label={`Số lượng ${row.medicineName}`} style={{ maxWidth: 90 }} value={quantities[row.prescriptionItemId] ?? ''} onChange={(event) => setQuantities({ ...quantities, [row.prescriptionItemId]: event.target.value })} /><span className="ui-muted">/ {row.prescribedQuantity} {row.unit} đã kê</span></div>
          : `${row.quantity} / ${row.prescribedQuantity} ${row.unit}`) },
        { title: 'Đơn giá (đã chốt)', align: 'right', render: (row) => <>{formatMoney(row.unitPrice)}<small>VAT {Math.round(Number(row.vatRate) * 100)}%</small></> },
        { title: 'Thành tiền', align: 'right', render: (row) => formatMoney(Number(row.amountBeforeVat) + Number(row.vatAmount)) }]} />
      {pending && <>
        <Field label="Giảm giá (VND)" hint="Không vượt quá tổng tiền thuốc của hóa đơn này."><Input type="number" min="0" step="1000" style={{ maxWidth: 200 }} value={discount} onChange={(event) => setDiscount(event.target.value)} /></Field>
        <p className="ui-muted" style={{ margin: 0 }}>Nếu kho không đủ thuốc: sửa số lượng (0 để bỏ dòng) rồi thu lại — cả hóa đơn chỉ được thu khi đủ kho cho mọi dòng.</p>
        <div className="ui-actions ui-no-print">
          <Button variant="primary" onClick={() => setDialog('pay')}>Thu tiền thuốc</Button>
          <Button busy={action.busy} onClick={save}>Lưu thay đổi</Button>
          <Button variant="danger" onClick={() => setDialog('cancel')}>Khách đổi ý — hủy hóa đơn</Button>
        </div></>}
      {inv.paymentStatus === 'CANCELLED' && <p className="ui-muted">Hóa đơn đã hủy. Hủy là kết thúc: đơn này không lập lại hóa đơn thuốc (khách có thể mua ngoài bằng đơn in).</p>}
      {dialog === 'pay' && <ConfirmDialog title="Thu tiền thuốc" confirmLabel="Đã thu tiền" onClose={() => setDialog(null)} onConfirm={async () => { await billingApi.confirmMedicinePayment(invoiceId); action.setNotice({ type: 'success', text: 'Đã thu tiền thuốc và trừ kho.' }); reload(); }}>
        Thu <strong>{formatMoney(inv.payableAmount)}</strong> cho hóa đơn {inv.invoiceCode}. Kho bị trừ ngay trong cùng thao tác và không hoàn tác được.</ConfirmDialog>}
      {dialog === 'cancel' && <ConfirmDialog title="Hủy hóa đơn thuốc" danger confirmLabel="Hủy hóa đơn" onClose={() => setDialog(null)} onConfirm={async () => { await billingApi.cancelMedicineInvoice(invoiceId); action.setNotice({ type: 'success', text: 'Đã hủy hóa đơn thuốc (kho không đổi).' }); reload(); }}>
        Khách không mua thuốc nữa. Kho không thay đổi. Đơn thuốc này sẽ không lập lại được hóa đơn thuốc.</ConfirmDialog>}
    </div>;
  }}</AsyncView>;
}

/** Lập hóa đơn thuốc từ đơn: khách chọn mua một phần hoặc toàn bộ (mua ngoài thì chỉ in đơn). */
function CreateInvoice({ prescription, visitStatus, onCreated }) {  // onCreated(text) chạy sau khi lập xong
  const [picked, setPicked] = useState(() => Object.fromEntries(prescription.items.map((item) => [item.id, { checked: true, quantity: String(item.quantity) }])));
  const [discount, setDiscount] = useState('0');
  const action = useAction();
  const create = async () => {
    const items = prescription.items.filter((item) => picked[item.id]?.checked).map((item) => ({ prescriptionItemId: item.id, quantity: Number(picked[item.id].quantity) }));
    if (!items.length) { action.setNotice({ type: 'error', text: 'Chọn ít nhất một thuốc khách mua tại phòng khám.' }); return; }
    if (items.some((line) => !Number.isInteger(line.quantity) || line.quantity < 1)) { action.setNotice({ type: 'error', text: 'Số lượng mua phải là số nguyên dương.' }); return; }
    if (await action.run(() => pharmacyApi.createMedicineInvoice(prescription.id, { items, discountAmount: Number(discount) || 0 }))) onCreated('Đã lập hóa đơn thuốc. Thu tiền thuốc để trừ kho.');
  };
  const set = (id, patch) => setPicked({ ...picked, [id]: { ...picked[id], ...patch } });
  return <div className="ui-stack" style={{ gap: 12 }}>
    <Notice notice={action.notice} onClose={action.clear} />
    {!SELLABLE.includes(visitStatus) && <div className="ui-alert warning" role="status"><span>Bác sĩ chưa chốt ca nên chưa lập hóa đơn thuốc được.</span></div>}
    <p className="ui-muted" style={{ margin: 0 }}>Khách có thể mua một phần đơn tại phòng khám hoặc mua ngoài. Giá và VAT được chốt theo danh mục lúc lập hóa đơn.</p>
    {prescription.items.map((item) => <div key={item.id} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 160px', gap: 12, alignItems: 'center' }}>
      <label className="ui-check"><input type="checkbox" checked={Boolean(picked[item.id]?.checked)} onChange={() => set(item.id, { checked: !picked[item.id]?.checked })} /><span><strong>{item.medicineName}</strong> <small className="ui-muted">đã kê {item.quantity} {item.unit}</small></span></label>
      <Input type="number" min="1" max={item.quantity} aria-label={`Số lượng mua ${item.medicineName}`} disabled={!picked[item.id]?.checked} value={picked[item.id]?.quantity ?? ''} onChange={(event) => set(item.id, { quantity: event.target.value })} />
    </div>)}
    <Field label="Giảm giá (VND)"><Input type="number" min="0" step="1000" style={{ maxWidth: 200 }} value={discount} onChange={(event) => setDiscount(event.target.value)} /></Field>
    <div className="ui-actions"><Button variant="primary" disabled={!SELLABLE.includes(visitStatus)} busy={action.busy} onClick={create}>Lập hóa đơn thuốc</Button></div>
  </div>;
}

/** Bán thuốc theo đơn của một đợt khám (UC-36). Hóa đơn thuốc độc lập với hóa đơn dịch vụ và không đổi trạng thái đợt khám. */
export default function MedicineSale({ visitId, visitStatus }) {
  const prescription = useAsync(() => pharmacyApi.prescriptionByVisit(visitId).catch((problem) => { if (problem.status === 404) return null; throw problem; }), [visitId]);
  const [printing, setPrinting] = useState(false);
  const [notice, setNotice] = useState(null);
  return <Panel title="Thuốc theo đơn" subtitle="Chỉ phát sinh hóa đơn khi khách chọn mua tại phòng khám. Mua ngoài: in đơn cho khách.">
    <AsyncView state={prescription} isEmpty={() => false}>{(p) => (p === null
      ? <p className="ui-muted">Bác sĩ không kê đơn thuốc cho đợt khám này.</p>
      : <div className="ui-stack" style={{ gap: 14 }}>
        <Notice notice={notice} onClose={() => setNotice(null)} />
        <PrescriptionView prescription={p} />
        <div className="ui-actions ui-no-print"><Button onClick={() => setPrinting(true)}>In đơn cho khách mua ngoài</Button></div>
        {p.medicineInvoiceId
          ? <InvoiceEditor invoiceId={p.medicineInvoiceId} onChanged={prescription.refresh} />
          : <CreateInvoice prescription={p} visitStatus={visitStatus} onCreated={(text) => { setNotice({ type: 'success', text }); prescription.refresh(); }} />}
        {printing && <PrintDialog prescriptionId={p.id} onClose={() => setPrinting(false)} />}
      </div>)}</AsyncView>
  </Panel>;
}
