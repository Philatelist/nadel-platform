import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { format } from '@nadel/shared';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useCan } from '../../me';
import { problemCode, problemFieldErrors, problemMessage } from '../../problem';
import { useDocumentTitle } from '../../useDocumentTitle';
import { Button, Notice, PageHeader, Panel, TextField } from '../ui';
import { billingMonthLabel } from './billingMonth';
import { invalidateAfterRangeRun } from './chargeInvalidation';
import { ChargesTabs } from './ChargesTabs';

/** What a range run (losses, common consumers) returns that the page itself needs. */
export interface RangeRunResult {
  runId?: string;
  billingMonth?: string;
  digest: string;
  created: number;
  plots: number;
  total: string;
}

export interface RangeRunRequest {
  data: { from: string; to: string; nodeIds?: string[]; digest?: string };
  params: { preview: boolean };
}

/** The mutation of a run: the generated `useRunLosses()` and its siblings fit. */
export interface RangeRunner<TResult> {
  mutate(
    variables: RangeRunRequest,
    options: { onSuccess?: (result: TResult) => void | Promise<void>; onError?: (error: unknown) => void },
  ): void;
  isPending: boolean;
}

/** When part of a preview cannot be booked: the ids that can, and whether something is blocked. */
export interface PartialBooking {
  blocked: boolean;
  ids: string[];
}

interface Props<TResult extends RangeRunResult> {
  title: string;
  description: string;
  runner: RangeRunner<TResult>;
  /** The preview table, warnings and so on for a result. */
  renderPreview: (result: TResult) => ReactNode;
  /** The heading of the preview, from the range. */
  previewTitle: (range: { from: string; to: string }) => string;
  /** Present when parts of a preview can be refused; the page offers to book the rest. */
  partial?: (result: TResult) => PartialBooking;
  /** Texts of the blocked case. */
  blockedTexts?: { note: string; action: string; nothing: string };
  /** An extra hint for a problem code (a link to the settings, say). */
  problemHint?: (code: string | undefined) => ReactNode;
  /** The query keys of the page's own lists to refresh after booking. */
  onBooked?: () => Promise<unknown> | unknown;
  /** The history of past runs. */
  history: ReactNode;
}

/**
 * A range run page: pick a date range, preview what would be booked, book exactly that preview (its digest).
 * Running needs CHARGES_RUN; everyone who sees the page sees the history.
 */
export function RunRangePage<TResult extends RangeRunResult>({
  title,
  description,
  runner,
  renderPreview,
  previewTitle,
  partial,
  blockedTexts,
  problemHint,
  onBooked,
  history,
}: Props<TResult>) {
  const t = useT();
  const r = t.billing.rangeRun;
  const queryClient = useQueryClient();
  const canRun = useCan('CHARGES_RUN');
  useDocumentTitle(title);
  const today = kyivToday();
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [preview, setPreview] = useState<TResult>();
  const [nodeIds, setNodeIds] = useState<string[]>();
  const [done, setDone] = useState<TResult>();
  const [error, setError] = useState<unknown>();
  const [outdated, setOutdated] = useState(false);

  const rangeError = from === '' || to === '' || from > to || to > today ? r.rangeInvalid : undefined;
  const fieldErrors = problemFieldErrors(t, error);
  const code = problemCode(error);
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;

  function changeRange(next: { from?: string; to?: string }) {
    if (next.from !== undefined) setFrom(next.from);
    if (next.to !== undefined) setTo(next.to);
    setPreview(undefined);
    setNodeIds(undefined);
    setDone(undefined);
    setError(undefined);
    setOutdated(false);
  }

  function handleError(e: unknown) {
    setError(e);
    const problem = problemCode(e);
    setOutdated(problem === 'PREVIEW_OUTDATED' || problem === 'LOSSES_RANGE_OVERLAP');
  }

  function runPreview(ids?: string[]) {
    setError(undefined);
    setOutdated(false);
    setDone(undefined);
    setPreview(undefined);
    runner.mutate(
      { data: { from, to, ...(ids ? { nodeIds: ids } : {}) }, params: { preview: true } },
      {
        onSuccess: (result) => {
          setNodeIds(ids);
          setPreview(result);
        },
        onError: handleError,
      },
    );
  }

  function book(result: TResult) {
    setError(undefined);
    runner.mutate(
      {
        data: { from, to, ...(nodeIds ? { nodeIds } : {}), digest: result.digest },
        params: { preview: false },
      },
      {
        onSuccess: async (booked) => {
          setPreview(undefined);
          setDone(booked);
          await Promise.all([invalidateAfterRangeRun(queryClient, booked.billingMonth), onBooked?.()]);
        },
        onError: (e) => {
          handleError(e);
          void invalidateAfterRangeRun(queryClient, result.billingMonth);
        },
      },
    );
  }

  const parts = preview && partial ? partial(preview) : undefined;
  const bookable = preview !== undefined && !(parts?.blocked ?? false);
  const previewing = runner.isPending && preview === undefined;
  const booking = runner.isPending && preview !== undefined;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={title} description={description} />
      <ChargesTabs />
      {canRun ? (
        <Panel>
          <form
            className="flex flex-wrap items-start gap-4"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!rangeError) runPreview();
            }}
          >
            <TextField
              label={r.from}
              type="date"
              value={from}
              max={to || today}
              error={fieldErrors.from}
              onChange={(e) => changeRange({ from: e.target.value })}
            />
            <TextField
              label={r.to}
              type="date"
              value={to}
              max={today}
              hint={r.toHint}
              error={fieldErrors.to ?? rangeError}
              onChange={(e) => changeRange({ to: e.target.value })}
            />
            <div className="self-end pb-1">
              <Button type="submit" disabled={rangeError !== undefined || runner.isPending}>
                {previewing ? r.previewing : r.preview}
              </Button>
            </div>
          </form>
        </Panel>
      ) : (
        <p className="text-ink-muted">{r.noAction}</p>
      )}
      {error !== undefined && !hasFieldErrors && (
        <div className="flex flex-col gap-2">
          <Notice tone="danger">{problemMessage(t, error)}</Notice>
          {problemHint?.(code)}
          {outdated && (
            <div>
              <Button variant="secondary" onClick={() => runPreview()}>
                {r.previewAgain}
              </Button>
            </div>
          )}
        </div>
      )}
      {done && <Notice tone="success">{format(r.done, { count: done.created })}</Notice>}
      {preview && (
        <Panel title={previewTitle({ from, to })}>
          <div className="flex flex-col gap-4">
            <p className="flex flex-wrap gap-x-6 gap-y-1 text-ink-muted">
              <span>{format(r.plots, { count: preview.plots })}</span>
              {preview.billingMonth && (
                <span>{format(r.bookedMonth, { month: billingMonthLabel(preview.billingMonth, t) })}</span>
              )}
            </p>
            {renderPreview(preview)}
            {parts?.blocked && blockedTexts && (
              <Notice tone="warning">
                {parts.ids.length > 0 ? blockedTexts.note : blockedTexts.nothing}
              </Notice>
            )}
            <div className="flex flex-wrap justify-end gap-2">
              {parts?.blocked && parts.ids.length > 0 && blockedTexts && (
                <Button onClick={() => runPreview(parts.ids)} disabled={runner.isPending}>
                  {blockedTexts.action}
                </Button>
              )}
              {canRun && bookable && (
                <Button onClick={() => book(preview)} disabled={runner.isPending}>
                  {booking ? r.booking : r.book}
                </Button>
              )}
            </div>
          </div>
        </Panel>
      )}
      {history}
    </div>
  );
}
