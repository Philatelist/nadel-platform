import {
  ApiError,
  getInvoicePdf,
  getListInvoicesQueryKey,
  issuePlotInvoice,
  useGetInvoiceReadiness,
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
import { BusyLabel } from './BusyLabel';
import { RequisitesWarning } from './RequisitesWarning';

const PAGE_SIZE = 20;

/** The most earlier versions of one plot and month shown on one page when they are expanded. */
const VERSIONS_PAGE_SIZE = 100;

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

/**
 * The invoices of one plot, newest month first, the latest version of each month with its PDF and its earlier versions
 * collapsed. "Invoice for today" (only with `INVOICES_ISSUE`) issues the current month's invoice (the server keeps the
 * latest version when nothing changed) and downloads its PDF.
 */
export function PlotInvoices({ plotId }: { plotId: string }) {
  const t = useT();
  const i = t.invoices;
  const queryClient = useQueryClient();
  const canIssue = useCan('INVOICES_ISSUE');
  const [page, setPage] = useState(0);
  const invoices = useListInvoices({ plotId, latestOnly: true, page, size: PAGE_SIZE });
  const readiness = useGetInvoiceReadiness({ query: { enabled: canIssue } });
  const issue = usePdfAction();
  const issueToday = () =>
    void issue.runNamed(async () => {
      const row = await issuePlotInvoice(plotId, {});
      setPage(0);
      void queryClient.invalidateQueries({ queryKey: getListInvoicesQueryKey() });
      return { blob: await getInvoicePdf(row.id), fileName: invoiceFileName(row) };
    });
  const issueError: unknown = issue.error;
  const missing = canIssue ? (readiness.data?.missing ?? []) : [];
  const missingFromError = missingRequisites(issueError);
  const warned = missing.length > 0 ? missing : missingFromError;

  return (
    <Panel title={i.plotTitle}>
      <div className="flex flex-col gap-4">
        <RequisitesWarning missing={warned} />
        {canIssue && (
          <div>
            <Button disabled={issue.pending} onClick={issueToday}>
              {issue.pending ? <BusyLabel label={i.issuing} /> : i.issueToday}
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
                  <InvoiceItem key={row.id} row={row} withEarlier />
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

/**
 * One invoice row with its PDF button; `plotLabel` is set where rows of several plots are listed; `withEarlier` adds
 * the collapsed earlier versions of the row's plot and month (the row is their latest version).
 */
export function InvoiceItem({
  row,
  plotLabel,
  withEarlier = false,
}: {
  row: InvoiceRow;
  plotLabel?: string;
  withEarlier?: boolean;
}) {
  const t = useT();
  const { locale } = useLocale();
  const i = t.invoices;
  const { run, pending, error } = usePdfAction();
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {plotLabel !== undefined && <span>{plotLabel}</span>}
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
      {withEarlier && row.version > 1 && <EarlierVersions row={row} />}
    </li>
  );
}

/**
 * «Earlier versions (N)» of the row's plot and month, N = the latest version − 1 (versions are 1..N without gaps),
 * collapsed; the versions are loaded when the user expands them.
 */
function EarlierVersions({ row }: { row: InvoiceRow }) {
  const t = useT();
  const i = t.invoices;
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(0);
  const versions = useListInvoices(
    { plotId: row.plotId, month: row.month, page, size: VERSIONS_PAGE_SIZE },
    { query: { enabled: open } },
  );
  const label = format(i.earlierVersions, { count: row.version - 1 });
  return (
    <div className="basis-full">
      <button
        type="button"
        aria-expanded={open}
        className="cursor-pointer text-sm text-ink-muted underline hover:text-ink"
        onClick={() => setOpen(!open)}
      >
        {label}
      </button>
      {open && (
        <QueryBoundary query={versions}>
          {(data) => (
            <>
              <ul
                aria-label={label}
                className="mt-1 divide-y divide-line-subtle border-l-2 border-line-subtle pl-4"
              >
                {data.items
                  .filter((version) => version.id !== row.id && version.version < row.version)
                  .map((version) => (
                    <InvoiceItem key={version.id} row={version} />
                  ))}
              </ul>
              <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
            </>
          )}
        </QueryBoundary>
      )}
    </div>
  );
}
