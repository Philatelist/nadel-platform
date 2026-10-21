import {
  getListImportsQueryKey,
  listImports,
  type ImportRunStatus,
  type ImportSummary,
} from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { Pagination } from '../Pagination';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, DataTable, Panel, type BadgeTone, type Column } from '../ui';

const pageSize = 20;
const tones: Readonly<Record<ImportRunStatus, BadgeTone>> = {
  PREVIEW: 'info',
  HAS_ERRORS: 'warning',
  COMMITTED: 'success',
  STALE: 'warning',
  EXPIRED: 'neutral',
};
const entities = ['plots', 'members', 'ownerships'] as const;

/** The list of uploaded files; the query key starts with the list key so a plain invalidation refreshes it. */
export function ImportHistory() {
  const t = useT();
  const { locale } = useLocale();
  const h = t.importHistory;
  const [page, setPage] = useState(0);
  const params = { page, size: pageSize };
  const query = useQuery({
    queryKey: getListImportsQueryKey(params),
    queryFn: () => listImports(params),
  });

  const columns: Column<ImportSummary>[] = [
    {
      key: 'date',
      header: h.colDate,
      cell: (r) => (
        <time dateTime={r.uploadedAt} className="whitespace-nowrap">
          {formatDateTime(r.uploadedAt, locale)}
        </time>
      ),
    },
    { key: 'file', header: h.colFile, cell: (r) => <UserContent>{r.fileName}</UserContent> },
    { key: 'by', header: h.colBy, cell: (r) => <UserContent>{r.uploadedBy}</UserContent> },
    {
      key: 'status',
      header: h.colStatus,
      cell: (r) => <Badge tone={tones[r.status]}>{h.status[r.status]}</Badge>,
    },
    {
      key: 'counts',
      header: h.colCounts,
      cell: (r) => (
        <ul className="m-0 list-none p-0">
          {entities.map((e) => (
            <li key={e}>
              {format(h.counts, {
                label: t.import.entity[e],
                created: r.counts[e].created,
                updated: r.counts[e].updated,
              })}
            </li>
          ))}
        </ul>
      ),
    },
    { key: 'problems', header: h.colProblems, numeric: true, cell: (r) => r.problemCount },
  ];

  return (
    <Panel title={h.title}>
      <QueryBoundary query={query} isEmpty={(d) => d.items.length === 0} emptyMessage={h.empty}>
        {(data) => (
          <>
            <DataTable
              columns={columns}
              rows={data.items}
              rowKey={(r) => r.id}
              caption={h.title}
              emptyMessage={h.empty}
            />
            <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
          </>
        )}
      </QueryBoundary>
    </Panel>
  );
}
