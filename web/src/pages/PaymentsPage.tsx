import { PaymentSource, useListPayments, type PaymentSummary } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { Pagination } from '../components/Pagination';
import { PlotSelect } from '../components/payments/PlotSelect';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import {
  Badge,
  DataTable,
  Money,
  MonthField,
  PageHeader,
  Panel,
  Select,
  TextField,
  buttonClass,
  type Column,
} from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 20;
const SOURCES = Object.values(PaymentSource);
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

function matching(value: string | null, pattern: RegExp): string | undefined {
  return value !== null && pattern.test(value) ? value : undefined;
}

/** The payments of the cooperative, newest first; the filters live in the address, so a view can be shared. */
export function PaymentsPage() {
  const t = useT();
  const { locale } = useLocale();
  useDocumentTitle(t.payments.title);
  const canEnter = useCan('PAYMENTS_ENTER');
  const [params, setParams] = useSearchParams();
  const from = matching(params.get('from'), DAY);
  const to = matching(params.get('to'), DAY);
  const month = matching(params.get('month'), MONTH);
  const source = SOURCES.find((value) => value === params.get('source'));
  const plotId = params.get('plotId') ?? undefined;
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);

  function setFilter(key: string, value: string | undefined) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    next.delete('page');
    setParams(next);
  }

  const query = useListPayments(
    { from, to, month, source, plotId, page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  const filtered = [from, to, month, source, plotId].some((value) => value !== undefined);

  const c = t.payments.columns;
  const columns: Column<PaymentSummary>[] = [
    {
      key: 'reference',
      header: c.reference,
      cell: (p) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to={`/payments/${p.id}`} className="font-medium text-accent underline">
            {p.receiptNumber !== undefined ? (
              format(t.payments.receiptNumber, { number: p.receiptNumber })
            ) : p.bankReference ? (
              <UserContent>{p.bankReference}</UserContent>
            ) : (
              formatDay(p.receivedOn, locale)
            )}
          </Link>
          {p.reversed && <Badge tone="neutral">{t.payments.reversed}</Badge>}
          {(p.groupSize ?? 1) > 1 && (
            <Badge tone="neutral">{format(t.payments.groupMarker, { count: p.groupSize ?? 1 })}</Badge>
          )}
        </span>
      ),
    },
    { key: 'receivedOn', header: c.receivedOn, cell: (p) => formatDay(p.receivedOn, locale) },
    {
      key: 'plot',
      header: c.plot,
      cell: (p) => (
        <Link to={`/plots/${p.plotId}`} className="text-accent underline">
          <UserContent>{p.plotNumber}</UserContent>
        </Link>
      ),
    },
    {
      key: 'payer',
      header: c.payer,
      cell: (p) => (p.payer ? <UserContent>{p.payer.shortName}</UserContent> : null),
    },
    { key: 'source', header: c.source, cell: (p) => t.payments.source[p.source] },
    { key: 'amount', header: c.amount, numeric: true, cell: (p) => <Money amount={p.amount} /> },
    { key: 'allocated', header: c.allocated, numeric: true, cell: (p) => <Money amount={p.allocated} /> },
    { key: 'prepayment', header: c.prepayment, numeric: true, cell: (p) => <Money amount={p.prepayment} /> },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.payments.title}
        actions={
          canEnter && (
            <Link to="/payments/new" className={buttonClass('primary')}>
              {t.payments.enter}
            </Link>
          )
        }
      />
      <Panel>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <TextField
            label={t.payments.filters.from}
            type="date"
            value={from ?? ''}
            onChange={(e) => setFilter('from', e.target.value)}
          />
          <TextField
            label={t.payments.filters.to}
            type="date"
            value={to ?? ''}
            onChange={(e) => setFilter('to', e.target.value)}
          />
          <MonthField
            label={t.payments.filters.month}
            value={month ?? ''}
            onChange={(value) => setFilter('month', value)}
          />
          <Select
            label={t.payments.filters.source}
            value={source ?? ''}
            onChange={(e) => setFilter('source', e.target.value)}
          >
            <option value="">{t.payments.filters.allSources}</option>
            {SOURCES.map((value) => (
              <option key={value} value={value}>
                {t.payments.source[value]}
              </option>
            ))}
          </Select>
          <div className="min-w-56">
            <PlotSelect
              label={t.payments.filters.plot}
              plotId={plotId}
              changeLabel={t.payments.filters.clearPlot}
              onChange={(id) => setFilter('plotId', id)}
            />
          </div>
        </div>
        <QueryBoundary
          query={query}
          isEmpty={(data) => data.items.length === 0}
          emptyMessage={filtered ? t.payments.nothingFound : t.payments.empty}
        >
          {(data) => (
            <>
              <DataTable
                columns={columns}
                rows={data.items}
                rowKey={(p) => p.id}
                caption={t.payments.title}
                emptyMessage={t.payments.empty}
              />
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                onPageChange={(p) => {
                  const next = new URLSearchParams(params);
                  next.set('page', String(p));
                  setParams(next);
                }}
              />
            </>
          )}
        </QueryBoundary>
      </Panel>
    </div>
  );
}
