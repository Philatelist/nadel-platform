import {
  ApiError,
  InvoicePrintScope,
  buildMonthInvoices,
  getListInvoicesQueryKey,
  getMonthInvoicesPrintPdf,
  useGetInvoiceReadiness,
  useListInvoices,
  type InvoiceBuild,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemCode, problemMessage } from '../../problem';
import { billingMonthLabel } from '../charges/billingMonth';
import { Pagination } from '../Pagination';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Panel } from '../ui';
import { BusyLabel } from './BusyLabel';
import { InvoiceItem, missingRequisites } from './PlotInvoices';
import { SendInvoicesDialog } from './SendInvoicesDialog';
import { RequisitesWarning } from './RequisitesWarning';

const PAGE_SIZE = 20;

/** `invoices_2026-10_paper.pdf` / `invoices_2026-10_all.pdf`. */
export function monthPrintFileName(month: string, scope: InvoicePrintScope): string {
  return `invoices_${month}_${scope === InvoicePrintScope.PAPER ? 'paper' : 'all'}.pdf`;
}

const previewKey = (month: string, includeNothingDue: boolean) =>
  ['monthInvoicesPreview', month, includeNothingDue] as const;

/** A finished build with the month and switch it was made for. */
interface BuildResult {
  month: string;
  includeNothingDue: boolean;
  counts: InvoiceBuild;
}

/**
 * Invoices of one billing month: the preview (what a build would do, in the categories of the result), the build, the
 * PDF for printing and the list (the latest version per plot, its earlier versions collapsed). After a build its
 * result takes the place of the preview until the month or the switch changes, so a preview counted after the build
 * is never shown as if it were the one before it.
 */
export function MonthInvoicesPanel({ month }: { month: string }) {
  const t = useT();
  const m = t.invoices.monthPanel;
  const queryClient = useQueryClient();
  const canIssue = useCan('INVOICES_ISSUE');
  const [includeNothingDue, setIncludeNothingDue] = useState(false);
  const [scope, setScope] = useState<InvoicePrintScope>(InvoicePrintScope.PAPER);
  const [page, setPage] = useState(0);
  const [built, setBuilt] = useState<BuildResult>();
  const [sending, setSending] = useState(false);
  const result =
    built !== undefined && built.month === month && built.includeNothingDue === includeNothingDue
      ? built.counts
      : undefined;
  const readiness = useGetInvoiceReadiness({ query: { enabled: canIssue } });
  const preview = useQuery({
    queryKey: previewKey(month, includeNothingDue),
    queryFn: () => buildMonthInvoices(month, { preview: true }, { includeNothingDue }),
    enabled: canIssue,
  });
  const build = useMutation({
    mutationFn: () => buildMonthInvoices(month, { preview: false }, { includeNothingDue }),
    onSuccess: async (data) => {
      setBuilt({ month, includeNothingDue, counts: data });
      setPage(0);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: getListInvoicesQueryKey() }),
        queryClient.invalidateQueries({ queryKey: ['monthInvoicesPreview', month] }),
      ]);
    },
    onError: () => setBuilt(undefined),
  });
  const print = usePdfAction();
  const invoices = useListInvoices({ month, latestOnly: true, page, size: PAGE_SIZE });
  const missingFromError = missingRequisites(build.error);
  const missing =
    (readiness.data?.missing ?? []).length > 0 ? (readiness.data?.missing ?? []) : missingFromError;
  const tooLarge = problemCode(build.error) === 'INVOICE_BUILD_TOO_LARGE';
  const showError = build.error !== null && missingFromError.length === 0;

  return (
    <Panel title={m.title}>
      <div className="flex flex-col gap-4">
        {canIssue && (
          <>
            <RequisitesWarning missing={missing} />
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={includeNothingDue}
                onChange={(e) => {
                  setIncludeNothingDue(e.target.checked);
                  setBuilt(undefined);
                }}
              />
              {m.includeNothingDue}
            </label>
            {result === undefined && <PreviewCounts query={preview} />}
            <div className="flex flex-wrap items-center gap-3">
              <Button disabled={build.isPending} onClick={() => build.mutate()}>
                {build.isPending ? (
                  <BusyLabel label={m.building} />
                ) : (
                  format(m.build, { month: billingMonthLabel(month, t) })
                )}
              </Button>
              {(invoices.data?.items.length ?? 0) > 0 && (
                <Button variant="secondary" onClick={() => setSending(true)}>
                  {format(m.send, { month: billingMonthLabel(month, t) })}
                </Button>
              )}
            </div>
            {showError && (
              <Notice tone="danger">
                {tooLarge
                  ? format(t.problem.INVOICE_BUILD_TOO_LARGE, { limit: limitOf(build.error) })
                  : problemMessage(t, build.error)}
              </Notice>
            )}
            {result !== undefined && !result.preview && (
              <Notice tone="success">
                {format(m.result, {
                  built: result.built ?? 0,
                  newVersions: result.newVersions ?? 0,
                  unchanged: result.unchanged ?? 0,
                  skipped: result.skipped ?? 0,
                })}
              </Notice>
            )}
            <fieldset className="flex flex-wrap items-center gap-3">
              <legend className="sr-only">{m.scope}</legend>
              {[InvoicePrintScope.PAPER, InvoicePrintScope.ALL].map((s) => (
                <label key={s} className="flex items-center gap-1">
                  <input
                    type="radio"
                    name="invoice-print-scope"
                    checked={scope === s}
                    onChange={() => setScope(s)}
                  />
                  {s === InvoicePrintScope.PAPER ? m.scopePAPER : m.scopeALL}
                </label>
              ))}
              <Button
                variant="secondary"
                disabled={print.pending}
                onClick={() =>
                  void print.run(
                    () => getMonthInvoicesPrintPdf(month, { scope }),
                    monthPrintFileName(month, scope),
                  )
                }
              >
                {print.pending ? m.printing : m.print}
              </Button>
            </fieldset>
            {print.error !== undefined && <Notice tone="danger">{problemMessage(t, print.error)}</Notice>}
          </>
        )}
        <QueryBoundary
          query={invoices}
          isEmpty={(data) => data.items.length === 0}
          emptyMessage={m.listEmpty}
        >
          {(data) => (
            <>
              <ul aria-label={m.listTitle} className="divide-y divide-line-subtle">
                {data.items.map((row) => (
                  <InvoiceItem
                    key={row.id}
                    row={row}
                    plotLabel={format(m.plot, { plot: row.plotNumber })}
                    withEarlier
                    canSend={canIssue}
                  />
                ))}
              </ul>
              <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
            </>
          )}
        </QueryBoundary>
      </div>
      {sending && <SendInvoicesDialog month={month} onClose={() => setSending(false)} />}
    </Panel>
  );
}

function limitOf(error: unknown): number | string {
  if (!(error instanceof ApiError)) return '';
  const limit = (error.body as { limit?: unknown }).limit;
  return typeof limit === 'number' || typeof limit === 'string' ? limit : '';
}

function PreviewCounts({ query }: { query: { data?: InvoiceBuild; isPending: boolean; isError: boolean } }) {
  const t = useT();
  const m = t.invoices.monthPanel;
  if (query.isError) return null;
  const d = query.data;
  if (d === undefined) return <p className="text-ink-muted">{m.previewLoading}</p>;
  return (
    <ul aria-label={m.previewTitle} className="flex flex-col gap-0.5">
      <li>{format(m.invoiceable, { count: d.invoiceable })}</li>
      <li>{format(m.existing, { count: d.existing ?? 0 })}</li>
      <li>
        {format(m.preview, {
          built: d.built ?? 0,
          newVersions: d.newVersions ?? 0,
          unchanged: d.unchanged ?? 0,
          skipped: d.skipped ?? 0,
        })}
      </li>
    </ul>
  );
}
