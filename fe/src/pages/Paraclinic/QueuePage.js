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

/** Gom các dòng của CÙNG đợt khám thành một "ca" (giữ thứ tự xuất hiện đầu tiên): một lần gọi, một lần bắt đầu. */
function groupByVisit(items) {
  const map = new Map();
  items.forEach((item) => { if (!map.has(item.visitId)) map.set(item.visitId, []); map.get(item.visitId).push(item); });
  return [...map.values()].map((rows) => ({ key: rows[0].visitId, head: rows[0], rows }));
}
const serviceNames = (rows) => rows.map((r) => r.serviceName).join(' + ');

/**
 * Hàng đợi Lab / CĐHA (v3.0): hàng đợi tự có khi bác sĩ chỉ định. Mỗi ca có nút "Gọi" (chỉ phát thông báo, KHÔNG đổi trạng thái và KHÔNG đổi thứ tự)
 * và "Bắt đầu" khi khách vào phòng. Khách vắng thì gọi ca khác; khách quay lại thì gọi lại. "Gọi số tiếp theo" là phím tắt gọi người đứng đầu.
 * Nhiều dịch vụ của CÙNG một đợt khám trong CÙNG phòng (vd. siêu âm + X-quang) hiện thành MỘT ca: gọi một lần, bắt đầu cùng nhau; nhập kết quả và hủy vẫn theo từng dịch vụ.
 */
export default function QueuePage({ kind }) {
  const room = ROOM[kind];
  const queue = useAsync(() => room.load(), [kind]);
  const [calledAt, setCalledAt] = useState({});
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  useNotificationEvents(() => queue.refresh(), ['PATIENT_CALLED']);
  usePolling(queue.refresh, 15000);
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); queue.refresh(); };

  const markCalled = (item) => setCalledAt((current) => ({ ...current, [item.visitId]: new Date() }));
  const callNext = async () => {
    const item = await action.run(() => room.callNext());
    if (item && item !== true) { markCalled(item); queue.refresh(); }
  };
  const call = async (group) => {
    if (await action.run(() => paraclinicApi.call(group.head.serviceDetailId), `Đã gọi ${group.head.petName} vào phòng.`)) { markCalled(group.head); queue.refresh(); }
  };
  const start = async (group) => {
    const ok = await action.run(async () => { for (const row of group.rows) await paraclinicApi.start(row.serviceDetailId); return true; },
      `Đã bắt đầu ${serviceNames(group.rows)} cho ${group.head.petName}.`);
    if (ok) queue.refresh();
  };
  const sample = async (item) => { if (await action.run(() => paraclinicApi.sampleCollected(item.serviceDetailId), `Đã ghi nhận lấy mẫu của ${item.petName}.`)) queue.refresh(); };
  const calledLabel = (group) => (calledAt[group.key] ? `Đã gọi lúc ${calledAt[group.key].toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}` : null);

  return <StaffLayout area={room.area} title={room.title}>
    <PageHead eyebrow={room.eyebrow} title={room.title} actions={<Button onClick={queue.reload}>Làm mới</Button>}>Ca cấp cứu lên đầu, còn lại theo thứ tự chỉ định. Khách vắng thì bấm Gọi ca khác (thứ tự không đổi). Bạn chỉ thấy ca thuộc phòng của mình.</PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={queue}>
      {(items) => {
        const workingGroups = groupByVisit(items.filter((item) => item.status === 'IN_PROGRESS'));
        const waitingGroups = groupByVisit(items.filter((item) => item.status !== 'IN_PROGRESS'));
        const urgent = waitingGroups.filter((g) => g.head.emergency);
        return <>
          <Stats items={[['Đang thực hiện', workingGroups.length], ['Đang chờ', waitingGroups.length], ['Cấp cứu', urgent.length, undefined, urgent.length ? 'red' : '']]} />

          {workingGroups.length > 0 && <Panel title="Đang thực hiện" flush>
            <Table caption="Đang thực hiện" rows={workingGroups} rowKey="key" columns={[
              { title: 'Thú cưng', render: (g) => <><strong>{g.head.petName}</strong><small>{g.head.speciesName} · {g.head.visitCode}</small></> },
              { title: 'Dịch vụ', render: (g) => <div style={{ display: 'grid', gap: 8 }}>{g.rows.map((row) => <div key={row.serviceDetailId} className="ui-actions" style={{ justifyContent: 'space-between' }}>
                <span>{row.serviceName} {row.stage ? <Badge map={QUEUE_STAGE} value={row.stage} /> : <Badge tone="blue">Đang thực hiện</Badge>}</span>
                <span className="ui-actions">
                  {kind === 'lab' && row.stage === 'SAMPLING' && <Button className="small" onClick={() => sample(row)}>Đã lấy mẫu</Button>}
                  <Link className="ui-button small primary" to={`${room.base}/entry/${row.serviceDetailId}`}>Nhập kết quả</Link>
                  <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy</Button></span></div>)}</div> }]} />
          </Panel>}

          <Panel title="Hàng đợi" subtitle="Bấm Gọi để báo chủ nuôi vào phòng; các dịch vụ cùng đợt khám được gọi chung một lượt. Gọi không làm đổi thứ tự." actions={<Button variant="primary" disabled={waitingGroups.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>} flush>
            {waitingGroups.length === 0 ? <Empty>Hiện không có ca nào chờ ở phòng này.</Empty> : <Table caption={room.title} rows={waitingGroups} rowKey="key" rowClass={(g) => (g.head.emergency ? 'emergency' : '')} columns={[
              { title: 'Thú cưng', render: (g) => <><strong>{g.head.petName}</strong>{g.head.emergency && <span className="ui-emergency"> · CẤP CỨU</span>}{calledLabel(g) && <Badge tone="amber">{calledLabel(g)}</Badge>}<small>{g.head.speciesName} · {g.head.visitCode}</small></> },
              { title: 'Dịch vụ', render: (g) => <div style={{ display: 'grid', gap: 6 }}>{g.rows.map((row) => <div key={row.serviceDetailId} className="ui-actions" style={{ justifyContent: 'space-between' }}>
                <span>{row.serviceName}{row.notes && <small>{row.notes}</small>}</span>
                {g.rows.length > 1 && <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy dịch vụ</Button>}</div>)}</div> },
              { title: 'Chỉ định lúc', render: (g) => formatTimeOfInstant(g.head.orderedAt) },
              { title: kind === 'imaging' ? 'Liên phòng' : 'Giai đoạn', render: (g) => (kind === 'imaging'
                ? (g.head.labStatus ? <Badge map={LAB_BADGE} value={g.head.labStatus} /> : <span className="ui-muted">—</span>)
                : <Badge map={QUEUE_STAGE} value={g.head.stage || 'QUEUED'} />) },
              { title: '', align: 'right', render: (g) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Button className="small" onClick={() => call(g)}>{calledAt[g.key] ? 'Gọi lại' : 'Gọi'}</Button>
                <Button className="small primary" onClick={() => start(g)}>Bắt đầu</Button>
                {g.rows.length === 1 && <Button className="small danger" onClick={() => setDialog({ item: g.head })}>Hủy</Button>}</div> }]} />}
          </Panel>
          {dialog && <ReasonDialog title="Hủy dịch vụ" danger item={dialog.item} onClose={() => setDialog(null)} onSubmit={async (reason) => { await paraclinicApi.cancel(dialog.item.serviceDetailId, reason); done(`Đã hủy dịch vụ của ${dialog.item.petName}.`); }} />}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
