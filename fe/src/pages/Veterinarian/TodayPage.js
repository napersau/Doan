import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Empty, Notice, PageHead, Panel, Stats } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import usePolling from '../../hooks/usePolling';
import { formatTimeOfInstant } from '../../lib/format';
import { VISIT_STATUS } from '../../lib/labels';

async function load() {
  const [queue, mine] = await Promise.all([visitsApi.myQueue(), visitsApi.myVisits()]);
  return { queue, inProgress: mine.filter((visit) => visit.status === 'IN_PROGRESS') };
}

function VisitLine({ visit, highlight, children }) {
  return <li style={highlight ? { background: 'var(--ui-raised)', borderRadius: 8 } : undefined}>
    <div><strong>{visit.petName}</strong> <small style={{ display: 'inline' }}>({visit.speciesName})</small>{' '}
      {visit.emergency && <span className="ui-emergency">CẤP CỨU</span>}
      <small>{visit.visitCode} · tiếp nhận {formatTimeOfInstant(visit.checkedInAt)}{visit.initialSymptoms ? ` · ${visit.initialSymptoms}` : ''}</small></div>
    <div className="ui-actions"><Badge map={VISIT_STATUS} value={visit.status} />{children}</div>
  </li>;
}

/**
 * Hàng đợi riêng của bác sĩ (v3.0). Mỗi ca có nút "Gọi" (chỉ phát thông báo gọi số, KHÔNG đổi trạng thái và KHÔNG đổi thứ tự) và nút
 * "Bắt đầu khám" (hoặc "Bắt đầu giai đoạn 2" với ca đã có kết quả). Gọi mà khách vắng thì gọi ca khác trong danh sách; khách quay lại thì gọi lại.
 * "Gọi số tiếp theo" là phím tắt gọi người đứng đầu.
 */
export default function TodayPage() {
  const board = useAsync(load, []);
  const [calledAt, setCalledAt] = useState({});
  const action = useAction();
  const navigate = useNavigate();
  useNotificationEvents(() => board.refresh(), ['VISIT_CHECKED_IN', 'RESULTS_READY', 'PATIENT_CALLED']);
  usePolling(board.refresh, 20000);

  const markCalled = (visit) => setCalledAt((current) => ({ ...current, [visit.id]: new Date() }));
  const callNext = async () => {
    const visit = await action.run(() => visitsApi.callNext());
    if (visit && visit !== true) { markCalled(visit); board.refresh(); }
  };
  const call = async (visit) => {
    if (await action.run(() => visitsApi.call(visit.id), `Đã gọi ${visit.petName} vào phòng.`)) { markCalled(visit); board.refresh(); }
  };
  const start = async (visit) => {
    if (visit.status === 'RESULTS_READY') {
      if (!(await action.run(() => visitsApi.startPhase2(visit.id)))) return;
    }
    navigate(`/vet/visits/${visit.id}`);
  };
  const calledLabel = (visit) => (calledAt[visit.id] ? `Đã gọi lúc ${calledAt[visit.id].toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}` : null);

  return <StaffLayout area="vet" title="Hàng đợi của tôi">
    <PageHead eyebrow="BÁC SĨ" title="Hàng đợi của tôi" actions={<Button onClick={board.reload}>Làm mới</Button>}>
      Cấp cứu trước, rồi theo thứ tự đến. Ca đã có kết quả cận lâm sàng tự lên đầu hàng đợi. Khách vắng thì bấm Gọi ca khác; thứ tự không đổi.
    </PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={board}>
      {({ queue, inProgress }) => {
        const ready = queue.filter((visit) => visit.status === 'RESULTS_READY').length;
        const busy = inProgress.length > 0;
        return <>
          <Stats items={[['Đang khám', inProgress.length], ['Đang chờ', queue.length], ['Đã có kết quả', ready, ready ? 'Đứng đầu hàng đợi' : undefined, ready ? 'amber' : '']]} />

          {busy && <Panel title="Đang khám" subtitle="Hoàn tất hoặc chốt ca đang khám trước khi gọi bệnh nhân khác.">
            <ul className="ui-list">{inProgress.map((visit) => <VisitLine key={visit.id} visit={visit}><Link className="ui-button small primary" to={`/vet/visits/${visit.id}`}>Mở hồ sơ</Link></VisitLine>)}</ul>
          </Panel>}

          <Panel title="Hàng đợi" subtitle="Bấm Gọi để báo chủ nuôi vào phòng; bệnh nhân vào phòng thì bấm Bắt đầu khám. Gọi không làm đổi thứ tự."
            actions={<Button variant="primary" disabled={busy || queue.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>}>
            {queue.length === 0 ? <Empty title="Hàng đợi trống">Lễ tân xếp ca cho bạn thì ca hiện ở đây ngay (và bạn nhận thông báo).</Empty>
              : <ul className="ui-list">{queue.map((visit, index) => <VisitLine key={visit.id} visit={visit} highlight={index === 0}>
                {index === 0 && <Badge tone="blue">Kế tiếp</Badge>}
                {visit.status === 'RESULTS_READY' && <Badge tone="green">Có kết quả</Badge>}
                {calledLabel(visit) && <Badge tone="amber">{calledLabel(visit)}</Badge>}
                <Button className="small" disabled={busy} onClick={() => call(visit)}>{calledAt[visit.id] ? 'Gọi lại' : 'Gọi'}</Button>
                <Button className="small primary" disabled={busy} onClick={() => start(visit)}>{visit.status === 'RESULTS_READY' ? 'Bắt đầu giai đoạn 2' : 'Bắt đầu khám'}</Button>
                <Link className="ui-button small" to={`/vet/visits/${visit.id}`}>Xem hồ sơ</Link>
              </VisitLine>)}</ul>}
            {busy && queue.length > 0 && <p className="ui-muted" style={{ marginBottom: 0 }}>Bạn đang có ca đang khám nên chưa gọi hay bắt đầu ca khác được.</p>}
          </Panel>
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
