import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import MedicineSale from '../../components/shared/MedicineSale';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, Field, FormDialog, Input, Notice, PageHead, Panel, Table } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import { formatDateTime, formatMoney } from '../../lib/format';
import { PAYMENT_STATUS, SERVICE_CATEGORY, VISIT_STATUS } from '../../lib/labels';

/** Giảm giá trên TỪNG hóa đơn dịch vụ, tối đa bằng giá trị (đã gồm VAT) của chính hóa đơn đó (BILL_1402). */
function DiscountDialog({ invoice, onClose, onDone }) {
  const [amount, setAmount] = useState(String(invoice.discountAmount ?? 0));
  const gross = Number(invoice.payableAmount) + Number(invoice.discountAmount);
  const submit = async () => {
    const value = Number(amount);
    if (Number.isNaN(value) || value < 0) throw new Error('Số tiền giảm giá không hợp lệ.');
    if (value > gross) throw new Error(`Giảm giá không được vượt giá trị hóa đơn này (${formatMoney(gross)}).`);
    await visitsApi.discountServiceInvoice(invoice.id, value);
    onDone(`Đã cập nhật giảm giá cho ${invoice.serviceName}.`);
  };
  return <FormDialog title={`Giảm giá — ${invoice.serviceName}`} onClose={onClose} onSubmit={submit}>
    <p className="ui-confirm-text">Giá trị hóa đơn này (đã gồm VAT): <strong>{formatMoney(gross)}</strong>. Giảm giá tối đa bằng số này.</p>
    <Field label="Số tiền giảm giá (VND)"><Input type="number" min="0" step="1000" value={amount} onChange={(event) => setAmount(event.target.value)} autoFocus /></Field>
  </FormDialog>;
}

export default function InvoiceDetailPage() {
  const { invoiceId: visitId } = useParams();
  const visit = useAsync(() => visitsApi.get(visitId), [visitId]);
  const statement = useAsync(() => visitsApi.serviceInvoices(visitId), [visitId]);
  const [selected, setSelected] = useState({});
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  const data = statement.data;

  // Mặc định chọn mọi hóa đơn đang chờ thu; giữ lựa chọn của thu ngân khi dữ liệu làm mới
  useEffect(() => {
    if (!data) return;
    setSelected((current) => Object.fromEntries(data.invoices.filter((invoice) => invoice.paymentStatus === 'PENDING').map((invoice) => [invoice.id, current[invoice.id] ?? true])));
  }, [data]);

  const reloadAll = () => { visit.refresh(); statement.refresh(); };
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); reloadAll(); };
  const chosenIds = Object.entries(selected).filter(([, checked]) => checked).map(([id]) => Number(id));

  return <StaffLayout area="reception" title="Thu tiền">
    <AsyncView state={statement}>
      {(st) => {
        const pending = st.invoices.filter((invoice) => invoice.paymentStatus === 'PENDING');
        const chosenTotal = pending.filter((invoice) => selected[invoice.id]).reduce((sum, invoice) => sum + Number(invoice.payableAmount), 0);
        const status = st.visitStatus;
        const canCollect = status === 'WAITING_PAYMENT' || (status === 'COMPLETED' && pending.length > 0);
        return <>
          <PageHead eyebrow="THU NGÂN" title={`Đợt khám ${st.visitCode}`} actions={<><Link className="ui-button" to="/reception/invoices">← Danh sách</Link><Button onClick={() => window.print()}>In bảng kê</Button></>}>
            {visit.data ? `${visit.data.petName} (${visit.data.speciesName})` : ''}{visit.data?.checkedInAt ? ` · Tiếp nhận ${formatDateTime(visit.data.checkedInAt)}` : ''}
          </PageHead>
          <Notice notice={action.notice} onClose={action.clear} />
          <Panel title="Hóa đơn dịch vụ" subtitle="Mỗi dịch vụ một hóa đơn. Chọn các hóa đơn khách thanh toán lúc này (được thu một phần)." actions={<Badge map={VISIT_STATUS} value={status} />} flush>
            <Table caption="Hóa đơn dịch vụ" rows={st.invoices} columns={[
              { title: '', render: (row) => (row.paymentStatus === 'PENDING' ? <input type="checkbox" aria-label={`Chọn ${row.serviceName}`} disabled={!canCollect} checked={Boolean(selected[row.id])} onChange={() => setSelected({ ...selected, [row.id]: !selected[row.id] })} /> : null) },
              { title: 'Dịch vụ', render: (row) => <><strong>{row.serviceName}</strong><small>{SERVICE_CATEGORY[row.category]} · {row.invoiceCode}</small></> },
              { title: 'SL', key: 'quantity' },
              { title: 'Đơn giá', align: 'right', render: (row) => <>{formatMoney(row.unitPrice)}<small>VAT {Math.round(Number(row.vatRate) * 100)}%</small></> },
              { title: 'Giảm giá', align: 'right', render: (row) => formatMoney(row.discountAmount) },
              { title: 'Phải trả', align: 'right', render: (row) => <strong>{formatMoney(row.payableAmount)}</strong> },
              { title: 'Trạng thái', render: (row) => <><Badge map={PAYMENT_STATUS} value={row.paymentStatus} />{row.paidAt && <small>{formatDateTime(row.paidAt)}</small>}</> },
              { title: '', align: 'right', render: (row) => (row.paymentStatus === 'PENDING' ? <Button className="small" onClick={() => setDialog({ type: 'discount', invoice: row })}>Giảm giá</Button> : null) }]} />
          </Panel>
          <Panel title="Tổng kết">
            <Facts items={[['Còn phải thu', <strong key="a">{formatMoney(st.totalPending)}</strong>], ['Đã thu', formatMoney(st.totalPaid)], ['Đang chọn thu', formatMoney(chosenTotal)]]} />
            {status === 'COMPLETED' && pending.length > 0 && <div className="ui-alert warning" role="status" style={{ marginTop: 12 }}><span>Khách đã bỏ về: các hóa đơn còn lại là công nợ. Thu bù tại đây; trạng thái ca giữ nguyên là Hoàn tất.</span></div>}
            {!['WAITING_PAYMENT', 'COMPLETED'].includes(status) && <div className="ui-alert warning" role="status" style={{ marginTop: 12 }}><span>Bác sĩ chưa chốt ca nên chưa thu tiền được.</span></div>}
            <div className="ui-actions ui-no-print" style={{ marginTop: 14 }}>
              <Button variant="primary" disabled={!canCollect || chosenIds.length === 0} onClick={() => setDialog({ type: 'pay' })}>Thu {chosenIds.length} hóa đơn đã chọn</Button>
            </div>
            <p className="ui-muted ui-no-print" style={{ marginBottom: 0 }}>Muốn bỏ một khoản: bác sĩ hủy dịch vụ tương ứng (hóa đơn tự hủy theo). Hóa đơn đã thu không hủy được.</p>
          </Panel>

          <MedicineSale visitId={Number(visitId)} visitStatus={status} />

          {dialog?.type === 'discount' && <DiscountDialog invoice={dialog.invoice} onClose={() => setDialog(null)} onDone={done} />}
          {dialog?.type === 'pay' && <ConfirmDialog title="Xác nhận thu tiền" confirmLabel="Đã thu tiền" onClose={() => setDialog(null)} onConfirm={async () => {
            const result = await visitsApi.confirmServicePayment(visitId, chosenIds);
            done(result?.visitStatus === 'COMPLETED' && status === 'WAITING_PAYMENT' ? 'Đã thu tiền. Mọi hóa đơn dịch vụ đã xử lý xong — ca khám hoàn tất.' : 'Đã ghi nhận thanh toán.');
          }}>
            Thu <strong>{formatMoney(chosenTotal)}</strong> cho {chosenIds.length} hóa đơn dịch vụ. Không thể hoàn tác sau khi xác nhận.</ConfirmDialog>}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
