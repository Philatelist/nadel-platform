import { useListNotCharged, useRetryNotCharged, type NotChargedItem } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { invalidateAfterRun } from '../components/charges/chargeInvalidation';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { NotChargedPeriod, NotChargedPlot, NotChargedReason } from '../components/charges/NotChargedReason';
import { QueryBoundary } from '../components/QueryBoundary';
import { Button, DataTable, PageHeader, type Column } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

/** `/charges/not-charged`: everything the app could not charge, with the reason and a retry. */
export function NotChargedPage() {
  const t = useT();
  const n = t.billing.notCharged;
  const canRetry = useCan('CHARGES_RUN');
  useDocumentTitle(n.title);
  const query = useListNotCharged();
  const columns: Column<NotChargedItem>[] = [
    { key: 'plot', header: n.columns.plot, cell: (item) => <NotChargedPlot item={item} /> },
    {
      key: 'type',
      header: n.columns.type,
      cell: (item) => (item.chargeType ? <ChargeTypeName type={item.chargeType} /> : n.kind[item.kind]),
    },
    { key: 'period', header: n.columns.period, cell: (item) => <NotChargedPeriod item={item} /> },
    { key: 'reason', header: n.columns.reason, cell: (item) => <NotChargedReason item={item} /> },
    ...(canRetry
      ? [
          {
            key: 'actions',
            header: n.columns.actions,
            cell: (item: NotChargedItem) => <RetryCell item={item} />,
          },
        ]
      : []),
  ];
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={n.heading} description={n.description} />
      <ChargesTabs />
      <QueryBoundary query={query} isEmpty={(items) => items.length === 0} emptyMessage={n.empty}>
        {(items) => (
          <DataTable
            columns={columns}
            rows={items}
            rowKey={(item) => item.id ?? `${item.kind}-${item.plotId}-${item.month}`}
            caption={n.listCaption}
            emptyMessage={n.empty}
          />
        )}
      </QueryBoundary>
    </div>
  );
}

function RetryCell({ item }: { item: NotChargedItem }) {
  const t = useT();
  const n = t.billing.notCharged;
  const queryClient = useQueryClient();
  const [still, setStill] = useState(false);
  const [problem, setProblem] = useState<string>();
  const retry = useRetryNotCharged({
    mutation: {
      onSuccess: async (result) => {
        setProblem(undefined);
        setStill(!result.resolved);
        await invalidateAfterRun(queryClient, item.month ?? '');
      },
      onError: (error: unknown) => setProblem(problemMessage(t, error)),
    },
  });
  if (!item.id) return null;
  const id = item.id;
  return (
    <div className="flex flex-col items-start gap-1">
      <Button variant="secondary" disabled={retry.isPending} onClick={() => retry.mutate({ id })}>
        {retry.isPending ? n.retrying : n.retry}
      </Button>
      {still && <span className="text-sm text-warning-ink">{n.stillNotCharged}</span>}
      {problem && (
        <span role="alert" className="text-sm text-danger">
          {problem}
        </span>
      )}
    </div>
  );
}
