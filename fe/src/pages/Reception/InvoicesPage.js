import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Input, PageHead, Pager, Panel, Table, Tabs } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import usePolling from '../../hooks/usePolling';
import { formatDateTime } from '../../lib/format';
import { VISIT_STATUS } from '../../lib/labels';

const TABS = [{ key: 'WAITING_PAYMENT', label: 'Chờ thanh toán' }, { key: 'DEBT', label: 'Công nợ (khách bỏ về)' }];

/**
 * Thu ngân v3.0: hóa đơn dịch vụ nằm theo từng đợt khám (mỗi dịch vụ một hóa đơn). Danh sách này liệt kê các đợt khám cần thu:
 * ca đã được bác sĩ chốt (WAITING_PAYMENT) và ca đã đóng nhưng còn hóa đơn chưa thu (công nợ).
 */
export default function InvoicesPage() {
  const [tab, setTab] = useState('WAITING_PAYMENT');
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const search = useDebounced(keyword);
  const list = useAsync(async () => {
    if (tab === 'DEBT') {
      const items = await visitsApi.withDebt();
      const needle = search.trim().toLowerCase();
      const filtered = needle ? items.filter((visit) => `${visit.visitCode} ${visit.petName}`.toLowerCase().includes(needle)) : items;
      return { items: filtered, page: 0, size: filtered.length, totalElements: filtered.length, totalPages: 1, last: true };
    }
    return visitsApi.list({ status: 'WAITING_PAYMENT', keyword: search.trim() || undefined, page, size: 20 });
  }, [tab, search, page]);
  useNotificationEvents(() => list.refresh(), ['VISIT_COMPLETED']);
  usePolling(list.refresh, 30000);

  return <StaffLayout area="reception" title="Hóa đơn & thanh toán">
    <PageHead eyebrow="THU NGÂN" title="Hóa đơn & thanh toán" actions={<Button onClick={list.reload}>Làm mới</Button>}>
      Mỗi dịch vụ có hóa đơn riêng; thu được một phần. Ca chỉ hoàn tất khi mọi hóa đơn dịch vụ đã thu hoặc hủy. Thuốc bán theo đơn nằm trong cùng trang chi tiết nhưng độc lập với hóa đơn dịch vụ.
    </PageHead>
    <Tabs tabs={TABS} value={tab} onChange={(key) => { setTab(key); setPage(0); }} />
    <div className="ui-toolbar"><Input aria-label="Tìm đợt khám" placeholder="Mã ca hoặc tên thú cưng" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(0); }} /></div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty={tab === 'DEBT' ? 'Không có công nợ nào.' : 'Không có ca nào đang chờ thanh toán.'}>
        {(data) => <>
          <Table caption="Đợt khám cần thu tiền" rows={data.items} columns={[
            { title: 'Mã ca', render: (row) => <Link className="ui-link" to={`/reception/invoices/${row.id}`}><strong>{row.visitCode}</strong></Link> },
            { title: 'Thú cưng', render: (row) => <>{row.petName}<small>{row.speciesName}</small></> },
            { title: 'Tiếp nhận', render: (row) => formatDateTime(row.checkedInAt) },
            { title: 'Trạng thái ca', render: (row) => <Badge map={VISIT_STATUS} value={row.status} /> },
            { title: '', align: 'right', render: (row) => <Link className="ui-button small primary" to={`/reception/invoices/${row.id}`}>{tab === 'DEBT' ? 'Thu công nợ' : 'Thu tiền'}</Link> }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
  </StaffLayout>;
}
