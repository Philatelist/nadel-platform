import { format, formatDateTime, formatDay } from '@nadel/shared';
import { useState, type ReactNode } from 'react';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, Button, Money, Panel } from '../ui';
import { billingMonthLabel } from './billingMonth';

/** What the list of range runs shows of a run; losses and common consumers runs both have it. */
export interface RunSummaryRow {
  id: string;
  from: string;
  to: string;
  status: 'BOOKED' | 'REVERSED';
  billingMonth: string;
  createdAt: string;
  createdByName?: string;
  total: string;
  plots: number;
  reversible: boolean;
  reversedAt?: string;
  reversedByName?: string;
  reverseReason?: string;
}

interface QueryLike<TData> {
  data: TData | undefined;
  error: unknown;
  isPending: boolean;
  isError: boolean;
  refetch: () => unknown;
}

/** The days of a run as shown in the list and in the reversal title. */
export function runRange(run: Pick<RunSummaryRow, 'from' | 'to'>, template: string, locale: string): string {
  return format(template, { from: formatDay(run.from, locale), to: formatDay(run.to, locale) });
}

/** Past range runs, newest first, with details on demand and a reverse button for a reversible run. */
export function RunHistory<TRun extends RunSummaryRow>({
  query,
  counts,
  renderDetails,
  renderReverse,
  initialOpenId,
}: {
  query: QueryLike<TRun[]>;
  /** The run's own counts line, e.g. nodes charged and skipped. */
  counts: (run: TRun) => string;
  renderDetails: (run: TRun) => ReactNode;
  /** The reverse dialog for a run; mounted only while open. */
  renderReverse: (run: TRun, onClose: () => void) => ReactNode;
  initialOpenId?: string | null;
}) {
  const t = useT();
  const { locale } = useLocale();
  const h = t.billing.runHistory;
  const canReverse = useCan('CHARGES_REVERSE');
  const [openId, setOpenId] = useState<string | null | undefined>(initialOpenId);
  const [reversing, setReversing] = useState<string>();
  return (
    <Panel title={h.title}>
      <QueryBoundary query={query} isEmpty={(runs) => runs.length === 0} emptyMessage={h.empty}>
        {(runs) => (
          <ul aria-label={h.listCaption} className="flex flex-col divide-y divide-line-subtle">
            {runs.map((run) => {
              const open = openId === run.id;
              return (
                <li key={run.id} className="flex flex-col gap-2 py-3">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <span className="text-[17px] font-semibold">{runRange(run, h.range, locale)}</span>
                    <Badge tone={run.status === 'REVERSED' ? 'neutral' : 'success'}>
                      {h.status[run.status]}
                    </Badge>
                    <Money amount={run.total} />
                  </div>
                  <p className="text-sm text-ink-muted">
                    {format(h.bookedIn, { month: billingMonthLabel(run.billingMonth, t) })},{' '}
                    {formatDateTime(run.createdAt, locale)}
                    {run.createdByName && (
                      <>
                        , {h.by} <UserContent>{run.createdByName}</UserContent>
                      </>
                    )}
                    . {counts(run)}
                  </p>
                  {run.status === 'REVERSED' && (
                    <p className="text-sm text-ink-muted">
                      {format(h.reversedInfo, {
                        date: run.reversedAt ? formatDateTime(run.reversedAt, locale) : '—',
                      })}
                      {run.reversedByName && (
                        <>
                          , {h.by} <UserContent>{run.reversedByName}</UserContent>
                        </>
                      )}
                      {run.reverseReason && (
                        <>
                          . {h.reason}: <UserContent>{run.reverseReason}</UserContent>
                        </>
                      )}
                    </p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="secondary"
                      aria-expanded={open}
                      onClick={() => setOpenId(open ? null : run.id)}
                    >
                      {open ? h.hide : h.details}
                    </Button>
                    {canReverse && run.reversible && (
                      <Button variant="danger" onClick={() => setReversing(run.id)}>
                        {h.reverse}
                      </Button>
                    )}
                  </div>
                  {open && <div className="mt-1">{renderDetails(run)}</div>}
                  {reversing === run.id && renderReverse(run, () => setReversing(undefined))}
                </li>
              );
            })}
          </ul>
        )}
      </QueryBoundary>
    </Panel>
  );
}
