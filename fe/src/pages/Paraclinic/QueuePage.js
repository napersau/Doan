import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Empty, Field, FormDialog, Notice, PageHead, Panel, Stats, Table, Textarea } from '../../components/ui';
import { paraclinicApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import usePolling from '../../hooks/usePolling';
import { formatTimeOfInstant } from '../../lib/format';
import { LAB_BADGE, QUEUE_STAGE } from '../../lib/labels';

export const ROOM = {
  lab: { area: 'lab', title: 'Hàng đợi xét nghiệm', eyebrow: 'PHÒNG XÉT NGHIỆM', base: '/lab', load: paraclinicApi.labQueue, callNext: paraclinicApi.callNextLab },
  imaging: { area: 'imaging', title: 'Hàng đợi chẩn đoán hình ảnh', eyebrow: 'PHÒNG CĐHA', base: '/imaging', load: paraclinicApi.imagingQueue, callNext: paraclinicApi.callNextImaging },
};

function ReasonDialog({ title, item, onClose, onSubmit, danger }) {
  const [reason, setReason] = useState('');
  return <FormDialog title={`${title} — ${item.petName}`} onClose={onClose} danger={danger} submitLabel={title}
    onSubmit={async () => { if (!reason.trim()) throw new Error('Nhập lý do.'); await onSubmit(reason.trim()); }}>
    <Field label="Lý do *"><Textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} rows={2} autoFocus /></Field>
  </FormDialog>;
}

/**
 * Hàng đợi Lab / CĐHA (v3.0): hàng đợi tự có khi bác sĩ chỉ định. "Gọi số tiếp theo" chọn người đứng đầu (cấp cứu chưa bị lùi trước) mà KHÔNG đổi
 * trạng thái dịch vụ; khách vào phòng thì "Bắt đầu". Không thấy khách → "Đẩy lùi 1 bậc" (dịch vụ giữ chờ thực hiện, xếp sau người kế tiếp).
 */
export default function QueuePage({ kind }) {
  const room = ROOM[kind];
  const queue = useAsync(() => room.load(), [kind]);
  const [called, setCalled] = useState(null);
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  useNotificationEvents(() => queue.refresh(), ['PATIENT_CALLED']);
  usePolling(queue.refresh, 15000);
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); queue.refresh(); };

  const callNext = async () => {
    const item = await action.run(() => room.callNext());
    if (item && item !== true) { setCalled(item); queue.refresh(); }
  };
  const skip = async (item) => {
    const next = await action.run(() => paraclinicApi.skip(item.serviceDetailId), `Đã đẩy lùi ${item.petName} một bậc.`);
    if (next) { setCalled(next === true ? null : next); queue.refresh(); }
  };
  const start = async (item) => { if (await action.run(() => paraclinicApi.start(item.serviceDetailId), `Đã bắt đầu ${item.serviceName} cho ${item.petName}.`)) { setCalled(null); queue.refresh(); } };
  const sample = async (item) => { if (await action.run(() => paraclinicApi.sampleCollected(item.serviceDetailId), `Đã ghi nhận lấy mẫu của ${item.petName}.`)) queue.refresh(); };

  return <StaffLayout area={room.area} title={room.title}>
    <PageHead eyebrow={room.eyebrow} title={room.title} actions={<Button onClick={queue.reload}>Làm mới</Button>}>Ca cấp cứu lên đầu; ca bị đẩy lùi xếp lại sau người kế tiếp. Bạn chỉ thấy ca thuộc phòng của mình.</PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={queue}>
      {(items) => {
        const working = items.filter((item) => item.status === 'IN_PROGRESS');
        const waiting = items.filter((item) => item.status !== 'IN_PROGRESS');
        const calledItem = called && waiting.find((item) => item.serviceDetailId === called.serviceDetailId);
        return <>
          <Stats items={[['Đang thực hiện', working.length], ['Đang chờ', waiting.length], ['Cấp cứu', items.filter((i) => i.emergency && i.skipCount === 0).length, undefined, items.some((i) => i.emergency && i.skipCount === 0) ? 'red' : ''], ['Đã bị lùi', items.filter((i) => i.skipCount > 0).length, undefined, 'amber']]} />

          {calledItem && <div className="ui-alert info" role="status" style={{ alignItems: 'center' }}>
            <span>Đang gọi <strong>{calledItem.petName}</strong> ({calledItem.visitCode}) — {calledItem.serviceName}. Khách vào phòng thì bấm Bắt đầu; không thấy khách thì Đẩy lùi 1 bậc.</span>
            <span className="ui-actions"><Button className="small primary" onClick={() => start(calledItem)}>Bắt đầu</Button><Button className="small" onClick={() => skip(calledItem)}>Không thấy khách — Đẩy lùi 1 bậc</Button></span>
          </div>}

          {working.length > 0 && <Panel title="Đang thực hiện" flush>
            <Table caption="Đang thực hiện" rows={working} rowKey="serviceDetailId" columns={[
              { title: 'Thú cưng', render: (row) => <><strong>{row.petName}</strong><small>{row.speciesName} · {row.visitCode}</small></> },
              { title: 'Dịch vụ', render: (row) => row.serviceName },
              { title: 'Giai đoạn', render: (row) => (row.stage ? <Badge map={QUEUE_STAGE} value={row.stage} /> : <Badge tone="blue">Đang thực hiện</Badge>) },
              { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                {kind === 'lab' && row.stage === 'SAMPLING' && <Button className="small" onClick={() => sample(row)}>Đã lấy mẫu</Button>}
                <Link className="ui-button small primary" to={`${room.base}/entry/${row.serviceDetailId}`}>Nhập kết quả</Link>
                <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy</Button></div> }]} />
          </Panel>}

          <Panel title="Hàng đợi" subtitle="Người đứng đầu được gọi khi bấm Gọi số tiếp theo." actions={<Button variant="primary" disabled={waiting.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>} flush>
            {waiting.length === 0 ? <Empty>Hiện không có ca nào chờ ở phòng này.</Empty> : <Table caption={room.title} rows={waiting} rowKey="serviceDetailId" rowClass={(row) => (row.emergency && row.skipCount === 0 ? 'emergency' : '')} columns={[
              { title: 'Thú cưng', render: (row) => <><strong>{row.petName}</strong>{row.emergency && row.skipCount === 0 && <span className="ui-emergency"> · CẤP CỨU</span>}{row.skipCount > 0 && <Badge tone="amber">Đã bị lùi {row.skipCount} lần</Badge>}<small>{row.speciesName} · {row.visitCode}</small></> },
              { title: 'Dịch vụ', render: (row) => <>{row.serviceName}{row.notes && <small>{row.notes}</small>}</> },
              { title: 'Chỉ định lúc', render: (row) => formatTimeOfInstant(row.orderedAt) },
              { title: kind === 'imaging' ? 'Liên phòng' : 'Giai đoạn', render: (row) => (kind === 'imaging'
                ? (row.labStatus ? <Badge map={LAB_BADGE} value={row.labStatus} /> : <span className="ui-muted">—</span>)
                : <Badge map={QUEUE_STAGE} value={row.stage || 'QUEUED'} />) },
              { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Button className="small primary" onClick={() => start(row)}>Bắt đầu</Button>
                <Button className="small" onClick={() => skip(row)}>Đẩy lùi 1 bậc</Button>
                <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy</Button></div> }]} />}
          </Panel>
          {dialog && <ReasonDialog title="Hủy dịch vụ" danger item={dialog.item} onClose={() => setDialog(null)} onSubmit={async (reason) => { await paraclinicApi.cancel(dialog.item.serviceDetailId, reason); done(`Đã hủy dịch vụ của ${dialog.item.petName}.`); }} />}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
