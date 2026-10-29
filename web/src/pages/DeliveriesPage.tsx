import {
  getListDeliveriesQueryKey,
  useListDeliveries,
  useMarkMonthPrinted,
  useRetryDelivery,
  type DeliveryRow,
  type ListDeliveriesParams,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { billingMonthLabel } from '../components/charges/billingMonth';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DeliveryCounts } from '../components/deliveries/DeliveryCounts';
import {
  DELIVERY_CHANNELS,
  DELIVERY_KINDS,
  DELIVERY_STATUSES,
  DeliveryFilters,
} from '../components/deliveries/DeliveryFilters';
import { DeliveryTable } from '../components/deliveries/DeliveryTable';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { Button, Notice, PageHeader, Panel } from '../components/ui';
import { kyivToday } from '../kyivToday';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 50;
const POLL_MS = 3000;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** The deliveries of one billing month with their outcome; the filters live in the address. */
export function DeliveriesPage() {
  const t = useT();
  const d = t.deliveries;
  useDocumentTitle(d.title);
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const monthParam = params.get('month');
  const month = monthParam !== null && MONTH.test(monthParam) ? monthParam : kyivToday().slice(0, 7);
  const kind = DELIVERY_KINDS.find((v) => v === params.get('kind'));
  const status = DELIVERY_STATUSES.find((v) => v === params.get('status'));
  const channel = DELIVERY_CHANNELS.find((v) => v === params.get('channel'));
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const [confirmingPrint, setConfirmingPrint] = useState(false);
  const [printed, setPrinted] = useState<number>();
  const [actionError, setActionError] = useState<unknown>();

  const query: ListDeliveriesParams = { month, kind, status, channel, page, size: PAGE_SIZE };
  const list = useListDeliveries(query, {
    query: {
      placeholderData: keepPreviousData,
      // While something waits in the queue the outcome changes in the background: look again.
      refetchInterval: (q) => (q.state.data?.items.some((r) => r.status === 'QUEUED') ? POLL_MS : false),
    },
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: getListDeliveriesQueryKey() });
  const retry = useRetryDelivery({
    mutation: {
      onSuccess: async () => {
        setActionError(undefined);
        await refresh();
      },
      onError: (error: unknown) => setActionError(error),
    },
  });
  const markPrinted = useMarkMonthPrinted({
    mutation: {
      onSuccess: async (data) => {
        setConfirmingPrint(false);
        setActionError(undefined);
        setPrinted(data.printed);
        await refresh();
      },
      onError: (error: unknown) => {
        setConfirmingPrint(false);
        setActionError(error);
      },
    },
  });

  function change(patch: Partial<Record<'month' | 'kind' | 'status' | 'channel', string>>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    next.delete('page');
    setPrinted(undefined);
    setParams(next);
  }

  const toPrint = (list.data?.summary ?? [])
    .filter((s) => s.status === 'TO_PRINT')
    .reduce((sum, s) => sum + s.count, 0);
  const filtered = kind !== undefined || status !== undefined || channel !== undefined;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={d.title} />
      <Panel>
        <div className="flex flex-col gap-4">
          <DeliveryFilters values={{ month, kind, status, channel }} onChange={change} />
          {list.data !== undefined && <DeliveryCounts summary={list.data.summary} />}
          {toPrint > 0 && (
            <div>
              <Button variant="secondary" onClick={() => setConfirmingPrint(true)}>
                {d.printDone}
              </Button>
            </div>
          )}
          {printed !== undefined && (
            <Notice tone="success">{format(d.printDoneResult, { count: printed })}</Notice>
          )}
          {actionError !== undefined && <Notice tone="danger">{problemMessage(t, actionError)}</Notice>}
          <QueryBoundary
            query={list}
            isEmpty={(data) => data.items.length === 0}
            emptyMessage={filtered ? d.nothingFound : d.empty}
          >
            {(data) => (
              <>
                <DeliveryTable
                  rows={data.items}
                  retryingId={retry.isPending ? retry.variables?.id : undefined}
                  onRetry={(row: DeliveryRow) => retry.mutate({ id: row.id })}
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
        </div>
      </Panel>
      {confirmingPrint && (
        <ConfirmDialog
          title={d.printDoneTitle}
          message={format(d.printDoneMessage, { count: toPrint, month: billingMonthLabel(month, t) })}
          confirmLabel={d.printDone}
          busy={markPrinted.isPending}
          onConfirm={() => markPrinted.mutate({ month })}
          onCancel={() => setConfirmingPrint(false)}
        />
      )}
    </div>
  );
}
