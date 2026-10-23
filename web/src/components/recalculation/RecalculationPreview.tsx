import { useState } from 'react';
import type { Recalculation, RecalculationLine } from '@nadel/api-client';
import { format, formatDay, formatMonth } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { MonthTotals } from '../charges/MonthTotals';
import { formatUser } from '../UserContent';
import { Button, DataTable, Money, Notice, Panel, TextArea, type Column } from '../ui';
import { actionLabel, causeLabel, changeReasonLabel } from './recalcLabels';

export const REASON_MAX_LENGTH = 500;

interface MonthGroup {
  month: string;
  lines: RecalculationLine[];
}
interface PlotGroup {
  plotId: string;
  plotNumber: string;
  months: MonthGroup[];
}

/** Lines arrive ordered by plot, then period: group them in that order without re-sorting. */
export function groupLines(lines: readonly RecalculationLine[]): PlotGroup[] {
  const plots = new Map<string, PlotGroup>();
  for (const line of lines) {
    let plot = plots.get(line.plotId);
    if (!plot) {
      plot = { plotId: line.plotId, plotNumber: line.plotNumber, months: [] };
      plots.set(line.plotId, plot);
    }
    const month = line.serviceFrom.slice(0, 7);
    let group = plot.months.find((m) => m.month === month);
    if (!group) {
      group = { month, lines: [] };
      plot.months.push(group);
    }
    group.lines.push(line);
  }
  return [...plots.values()];
}

interface Props {
  preview: Recalculation;
  reason: string;
  onReasonChange: (reason: string) => void;
  /** A server message for the reason field. */
  reasonError?: string;
  booking: boolean;
  onBook: () => void;
  onBack: () => void;
}

/**
 * What a recalculation would book, exactly as the server computed it: the lines grouped by plot and month, the
 * totals as sent, the reason to give and the two actions. Nothing is calculated here.
 */
export function RecalculationPreview({
  preview,
  reason,
  onReasonChange,
  reasonError,
  booking,
  onBook,
  onBack,
}: Props) {
  const t = useT();
  const { locale } = useLocale();
  const p = t.recalculation.preview;
  const groups = groupLines(preview.lines);
  const empty = groups.length === 0;
  const trimmed = reason.trim();
  const localReasonError = trimmed.length > REASON_MAX_LENGTH ? p.reasonTooLong : undefined;
  const [triedEmpty, setTriedEmpty] = useState(false);
  const missingReason = triedEmpty && trimmed.length === 0 ? p.reasonRequired : undefined;
  const canBook = !empty && localReasonError === undefined && !booking;
  const handleBook = () => {
    if (trimmed.length === 0) {
      setTriedEmpty(true);
      return;
    }
    onBook();
  };
  return (
    <div className="flex flex-col gap-4">
      <Panel title={p.scope}>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-[minmax(9rem,max-content)_1fr]">
          <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">
            {t.recalculation.detail.cause}
          </dt>
          <dd className="mb-2 sm:mb-0">{causeLabel(t, preview)}</dd>
          <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">{p.months}</dt>
          <dd className="mb-2 sm:mb-0">
            {preview.scope.months.map((month) => formatMonth(month, locale)).join(', ')}
          </dd>
          <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">
            {t.recalculation.detail.plots}
          </dt>
          <dd>{format(p.plots, { count: preview.plotCount })}</dd>
        </dl>
      </Panel>
      {empty ? (
        <Notice tone="warning">
          <strong>{p.noChanges}</strong>. {p.noChangesHint}
        </Notice>
      ) : (
        <>
          <Panel title={p.totals}>
            <MonthTotals totals={preview.totals} total={preview.total} />
          </Panel>
          <section aria-label={p.linesCaption} className="flex flex-col gap-4">
            {groups.map((plot) => (
              <PlotLines key={plot.plotId} plot={plot} />
            ))}
          </section>
          <Panel>
            <TextArea
              label={p.reason}
              hint={p.reasonHint}
              rows={3}
              value={reason}
              error={localReasonError ?? missingReason ?? reasonError}
              onChange={(e) => onReasonChange(e.target.value)}
            />
            <p className="mt-1 text-right text-sm text-ink-muted" data-testid="reason-counter">
              {format(p.reasonCounter, { count: trimmed.length })}
            </p>
          </Panel>
        </>
      )}
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" onClick={onBack} disabled={booking}>
          {t.recalculation.step.back}
        </Button>
        {!empty && (
          <Button onClick={handleBook} disabled={!canBook}>
            {booking ? t.recalculation.step.booking : t.recalculation.step.book}
          </Button>
        )}
      </div>
    </div>
  );
}

function PlotLines({ plot }: { plot: PlotGroup }) {
  const t = useT();
  const { locale } = useLocale();
  const d = t.recalculation.detail;
  const columns: Column<RecalculationLine>[] = [
    { key: 'type', header: d.columns.type, cell: (line) => <ChargeTypeName type={line.chargeType} /> },
    {
      key: 'period',
      header: d.columns.period,
      cell: (line) => `${formatDay(line.serviceFrom, locale)}–${formatDay(line.serviceTo, locale)}`,
    },
    {
      key: 'booked',
      header: t.recalculation.detail.columns.booked,
      numeric: true,
      cell: (line) => <LineBooked line={line} />,
    },
    {
      key: 'expected',
      header: d.columns.expected,
      numeric: true,
      cell: (line) => <Money amount={line.expected} />,
    },
    {
      key: 'difference',
      header: d.columns.difference,
      numeric: true,
      cell: (line) => <Money amount={line.difference} />,
    },
    { key: 'action', header: d.columns.action, cell: (line) => actionLabel(t, line.action) },
    {
      key: 'reasons',
      header: d.columns.reasons,
      cell: (line) => (
        <ul className="flex flex-col gap-0.5">
          {line.reasons.map((reason) => (
            <li key={reason}>{changeReasonLabel(t, reason)}</li>
          ))}
        </ul>
      ),
    },
  ];
  return (
    <div className="flex flex-col gap-2 rounded-panel border border-line bg-surface p-4">
      <h2 className="text-[17px] font-semibold">{formatUser(d.plot, { number: plot.plotNumber })}</h2>
      {plot.months.map((group) => (
        <div key={group.month} className="flex flex-col gap-1">
          <h3 className="text-sm font-semibold text-ink-muted">{formatMonth(group.month, locale)}</h3>
          <DataTable
            columns={columns}
            rows={group.lines}
            rowKey={(line) =>
              `${line.chargeType.id}-${line.serviceFrom}-${line.serviceTo}-${line.originalChargeId ?? 'new'}`
            }
            caption={format(d.plot, { number: plot.plotNumber })}
            emptyMessage={d.noLines}
          />
        </div>
      ))}
    </div>
  );
}

/** The booked amount; the adjustments made so far follow it when there are any (both as the server sent them). */
function LineBooked({ line }: { line: RecalculationLine }) {
  const hasAdjustments = line.adjustments !== '0.00';
  return (
    <span className="flex flex-col items-end">
      <Money amount={line.booked} />
      {hasAdjustments && (
        <span className="font-sans text-xs text-ink-muted">
          + <Money amount={line.adjustments} />
        </span>
      )}
    </span>
  );
}
