import {
  ApiError,
  getInvoicePdf,
  getListInvoicesQueryKey,
  useGetInvoiceReadiness,
  useIssuePlotInvoice,
  useListInvoices,
  type InvoiceRow,
  type RequisiteField,
} from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemCode, problemMessage } from '../../problem';
import { billingMonthLabel } from '../charges/billingMonth';
import { QueryBoundary } from '../QueryBoundary';
import { Pagination } from '../Pagination';
import { Button, Money, Notice, Panel } from '../ui';
import { RequisitesWarning } from './RequisitesWarning';

const PAGE_SIZE = 20;

/** `invoice_2026-10_plot-9_v2.pdf`; the plot number is user content, so path characters are replaced. */
export function invoiceFileName(row: Pick<InvoiceRow, 'month' | 'plotNumber' | 'version'>): string {
  const plot = row.plotNumber.replace(/[\\/:*?"<>|\s]+/g, '-');
  return `invoice_${row.month}_plot-${plot}_v${row.version}.pdf`;
}

/** The requisites a `REQUISITES_MISSING` problem names (`missing` in the body); empty for any other error. */
export function missingRequisites(error: unknown): RequisiteField[] {
  if (problemCode(error) !== 'REQUISITES_MISSING' || !(error instanceof ApiError)) return [];
  const body = error.body as { missing?: unknown };
  if (!Array.isArray(body.missing)) return [];
  return body.missing.filter((m): m is RequisiteField => m === 'IBAN' || m === 'EDRPOU');
}

/** The invoices of one plot, newest month first, each with its PDF; "Invoice for today" only with `INVOICES_ISSUE`. */
export function PlotInvoices({ plotId }: { plotId: string }) {
  const t = useT();
  const i = t.invoices;
  const queryClient = useQueryClient();
  const canIssue = useCan('INVOICES_ISSUE');
  const [page, setPage] = useState(0);
  const [issueError, setIssueError] = useState<unknown>();
  const invoices = useListInvoices({ plotId, page, size: PAGE_SIZE });
  const readiness = useGetInvoiceReadiness({ query: { enabled: canIssue } });
  const issue = useIssuePlotInvoice({
    mutation: {
      onSuccess: async () => {
        setIssueError(undefined);
        setPage(0);
        await queryClient.invalidateQueries({ queryKey: getListInvoicesQueryKey() });
      },
      onError: (error: unknown) => setIssueError(error),
    },
  });
  const missing = canIssue ? (readiness.data?.missing ?? []) : [];
  const missingFromError = missingRequisites(issueError);
  const warned = missing.length > 0 ? missing : missingFromError;

  return (
    <Panel title={i.plotTitle}>
      <div className="flex flex-col gap-4">
        <RequisitesWarning missing={warned} />
        {canIssue && (
          <div>
            <Button disabled={issue.isPending} onClick={() => issue.mutate({ id: plotId, data: {} })}>
              {issue.isPending ? i.issuing : i.issueToday}
            </Button>
          </div>
        )}
        {issueError !== undefined && missingFromError.length === 0 && (
          <Notice tone="danger">{problemMessage(t, issueError)}</Notice>
        )}
        <QueryBoundary query={invoices} isEmpty={(data) => data.items.length === 0} emptyMessage={i.empty}>
          {(data) => (
            <>
              <ul aria-label={i.plotTitle} className="divide-y divide-line-subtle">
                {data.items.map((row) => (
                  <InvoiceItem key={row.id} row={row} />
                ))}
              </ul>
              <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
            </>
          )}
        </QueryBoundary>
      </div>
    </Panel>
  );
}

function InvoiceItem({ row }: { row: InvoiceRow }) {
  const t = useT();
  const { locale } = useLocale();
  const i = t.invoices;
  const { run, pending, error } = usePdfAction();
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          <span>{billingMonthLabel(row.month, t)}</span>
          <span className="font-mono">{row.number}</span>
          <span className="text-ink-muted">{format(i.version, { version: row.version })}</span>
        </div>
        <div className="text-sm text-ink-muted">
          {i.due}: <Money amount={row.due} /> · {i.issuedAt}: {formatDateTime(row.issuedAt, locale)}
        </div>
        {error !== undefined && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
      </div>
      <Button
        variant="secondary"
        disabled={pending}
        onClick={() => void run(() => getInvoicePdf(row.id), invoiceFileName(row))}
      >
        {pending ? i.downloading : i.download}
      </Button>
    </li>
  );
}
