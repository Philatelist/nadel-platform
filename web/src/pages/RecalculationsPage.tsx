import { useListRecalculations, type RecalculationSummary } from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { Pagination } from '../components/Pagination';
import { causeLabel } from '../components/recalculation/recalcLabels';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { buttonClass, DataTable, Money, PageHeader, type Column } from '../components/ui';
import { useCan } from '../me';
import { useLocale, useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 20;

/** `/charges/recalculations`: the log of recalculations, newest first. Read only. */
export function RecalculationsPage() {
  const t = useT();
  const { locale } = useLocale();
  const r = t.recalculation;
  useDocumentTitle(r.title);
  const canRun = useCan('RECALCULATION_RUN');
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const query = useListRecalculations(
    { page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  const columns: Column<RecalculationSummary>[] = [
    { key: 'time', header: r.columns.time, cell: (row) => formatDateTime(row.createdAt, locale) },
    { key: 'cause', header: r.columns.cause, cell: (row) => causeLabel(t, row) },
    {
      key: 'author',
      header: r.columns.author,
      cell: (row) => (row.createdByName ? <UserContent>{row.createdByName}</UserContent> : r.system),
    },
    { key: 'reason', header: r.columns.reason, cell: (row) => <UserContent>{row.reason}</UserContent> },
    {
      key: 'total',
      header: r.columns.total,
      numeric: true,
      cell: (row) => (
        <span className="flex flex-col items-end">
          <Money amount={row.total} />
          <span className="font-sans text-xs text-ink-muted">
            {format(r.plotCount, { count: row.plotCount })}
          </span>
        </span>
      ),
    },
    {
      key: 'actions',
      header: r.columns.actions,
      cell: (row) => (
        <Link to={`/charges/recalculations/${row.id}`} className="text-accent underline">
          {r.open}
        </Link>
      ),
    },
  ];
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={r.heading}
        description={r.description}
        actions={
          canRun && (
            <Link to="/charges/recalculations/new" className={buttonClass()}>
              {r.newButton}
            </Link>
          )
        }
      />
      <ChargesTabs />
      <QueryBoundary query={query} isEmpty={(data) => data.items.length === 0} emptyMessage={r.empty}>
        {(data) => (
          <>
            <DataTable
              columns={columns}
              rows={data.items}
              rowKey={(row) => row.id}
              caption={r.listCaption}
              emptyMessage={r.empty}
            />
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              onPageChange={(next) => setParams(next === 0 ? {} : { page: String(next) })}
            />
          </>
        )}
      </QueryBoundary>
    </div>
  );
}
