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
      {visit.emergency && visit.skipCount === 0 && <span className="ui-emergency">CẤP CỨU</span>}
      {visit.skipCount > 0 && <Badge tone="amber">Đã bị lùi {visit.skipCount} lần</Badge>}
      <small>{visit.visitCode} · tiếp nhận {formatTimeOfInstant(visit.checkedInAt)}{visit.initialSymptoms ? ` · ${visit.initialSymptoms}` : ''}</small></div>
    <div className="ui-actions"><Badge map={VISIT_STATUS} value={visit.status} />{children}</div>
  </li>;
}

/**
 * Hàng đợi riêng của bác sĩ (v3.0). "Gọi số tiếp theo" chỉ gọi người đứng đầu hàng đợi và KHÔNG đổi trạng thái ca; thú cưng vào phòng thì
 * bấm "Bắt đầu khám" (hoặc "Bắt đầu giai đoạn 2" với ca đã có kết quả). Gọi mà không thấy → "Đẩy lùi 1 bậc": ca lùi sau người kế tiếp, người kế tiếp được gọi ngay.
 */
export default function TodayPage() {
  const board = useAsync(load, []);
  const [called, setCalled] = useState(null);
  const action = useAction();
  const navigate = useNavigate();
  useNotificationEvents(() => board.refresh(), ['VISIT_CHECKED_IN', 'RESULTS_READY', 'PATIENT_CALLED']);
  usePolling(board.refresh, 20000);

  const callNext = async () => {
    const visit = await action.run(() => visitsApi.callNext());
    if (visit && visit !== true) { setCalled(visit); board.refresh(); }
  };
  const skip = async (visit) => {
    const next = await action.run(() => visitsApi.skip(visit.id), `Đã đẩy lùi ${visit.petName} một bậc.`);
    if (next) { setCalled(next === true ? null : next); board.refresh(); }
  };
  const start = async (visit) => {
    if (visit.status === 'RESULTS_READY') {
      if (!(await action.run(() => visitsApi.startPhase2(visit.id)))) return;
    }
    setCalled(null);
    navigate(`/vet/visits/${visit.id}`);
  };

  return <StaffLayout area="vet" title="Hàng đợi của tôi">
    <PageHead eyebrow="BÁC SĨ" title="Hàng đợi của tôi" actions={<Button onClick={board.reload}>Làm mới</Button>}>
      Cấp cứu trước, rồi theo thứ tự đến. Ca đã có kết quả cận lâm sàng tự lên đầu hàng đợi.
    </PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={board}>
      {({ queue, inProgress }) => {
        const ready = queue.filter((visit) => visit.status === 'RESULTS_READY').length;
        const busy = inProgress.length > 0;
        const calledVisit = called && queue.find((visit) => visit.id === called.id);
        return <>
          <Stats items={[['Đang khám', inProgress.length], ['Đang chờ', queue.length], ['Đã có kết quả', ready, ready ? 'Đứng đầu hàng đợi' : undefined, ready ? 'amber' : '']]} />

          {busy && <Panel title="Đang khám" subtitle="Hoàn tất hoặc chốt ca đang khám trước khi gọi số tiếp theo.">
            <ul className="ui-list">{inProgress.map((visit) => <VisitLine key={visit.id} visit={visit}><Link className="ui-button small primary" to={`/vet/visits/${visit.id}`}>Mở hồ sơ</Link></VisitLine>)}</ul>
          </Panel>}

          {calledVisit && <div className="ui-alert info" role="status" style={{ alignItems: 'center' }}>
            <span>Đang gọi <strong>{calledVisit.petName}</strong> ({calledVisit.visitCode}) vào phòng{calledVisit.assignedRoom ? ` ${calledVisit.assignedRoom}` : ''}. Khách vào phòng thì bấm Bắt đầu; không thấy khách thì Đẩy lùi 1 bậc.</span>
            <span className="ui-actions"><Button className="small primary" onClick={() => start(calledVisit)}>{calledVisit.status === 'RESULTS_READY' ? 'Bắt đầu giai đoạn 2' : 'Bắt đầu khám'}</Button>
              <Button className="small" onClick={() => skip(calledVisit)}>Không thấy khách — Đẩy lùi 1 bậc</Button></span>
          </div>}

          <Panel title="Hàng đợi" subtitle="Người đứng đầu sẽ được gọi khi bấm Gọi số tiếp theo."
            actions={<Button variant="primary" disabled={busy || queue.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>}>
            {queue.length === 0 ? <Empty title="Hàng đợi trống">Lễ tân xếp ca cho bạn thì ca hiện ở đây ngay (và bạn nhận thông báo).</Empty>
              : <ul className="ui-list">{queue.map((visit, index) => <VisitLine key={visit.id} visit={visit} highlight={index === 0}>
                {index === 0 && <Badge tone="blue">Kế tiếp</Badge>}
                {visit.status === 'RESULTS_READY' && <Badge tone="green">Có kết quả</Badge>}
                <Link className="ui-button small" to={`/vet/visits/${visit.id}`}>Xem hồ sơ</Link>
              </VisitLine>)}</ul>}
            {busy && queue.length > 0 && <p className="ui-muted" style={{ marginBottom: 0 }}>Bạn đang có ca đang khám nên chưa gọi số tiếp theo được.</p>}
          </Panel>
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
