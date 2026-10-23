import type { CommonConsumerOutcome, CommonConsumerRunLine, CommonConsumerWarning } from '@nadel/api-client';
import { format, formatDay, type Messages } from '@nadel/shared';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Badge, DataTable, Money, type BadgeTone, type Column } from '../ui';
import { formatKwh } from './lossesFormat';
import { richFormat, RunWarnings, type RunWarningItem } from './RunWarnings';

const OUTCOME_TONE: Readonly<Record<CommonConsumerOutcome, BadgeTone>> = {
  CHARGED: 'success',
  NOT_CHARGED: 'neutral',
  NO_PLOTS: 'neutral',
  UNKNOWN: 'warning',
  ALREADY_CHARGED: 'info',
};

/** A consumer by its name (user content), or by a neutral text when it has none. */
function consumerLabel(line: Pick<CommonConsumerRunLine, 'name'>, t: Messages): ReactNode {
  return line.name ? <UserContent>{line.name}</UserContent> : t.billing.common.consumerFallback;
}

/** The text of one consumer's warning, from its code and parameters. */
export function commonWarningText(
  line: CommonConsumerRunLine,
  warning: CommonConsumerWarning,
  t: Messages,
  locale: string,
): ReactNode {
  const w = t.billing.common.warnings;
  const consumer = consumerLabel(line, t);
  switch (warning.code) {
    case 'UNKNOWN_VALUE':
      return warning.lastReadingOn
        ? richFormat(w.UNKNOWN_VALUE, { consumer, date: formatDay(warning.lastReadingOn, locale) })
        : richFormat(w.UNKNOWN_VALUE_NEVER, { consumer });
    case 'ALREADY_CHARGED': {
      const range =
        warning.from && warning.to
          ? format(t.billing.runHistory.range, {
              from: formatDay(warning.from, locale),
              to: formatDay(warning.to, locale),
            })
          : '…';
      return richFormat(w.ALREADY_CHARGED, {
        consumer,
        run: warning.runId ? (
          <Link to={`/charges/common?run=${warning.runId}`} className="text-accent underline">
            {range}
          </Link>
        ) : (
          range
        ),
      });
    }
    case 'NO_PLOTS':
      return richFormat(w.NO_PLOTS, { consumer });
  }
}

/** The warnings of the consumers of a preview or a booked run. */
export function CommonConsumerWarnings({ consumers }: { consumers: readonly CommonConsumerRunLine[] }) {
  const t = useT();
  const { locale } = useLocale();
  const items: RunWarningItem[] = consumers.flatMap((line) =>
    line.warning
      ? [
          {
            key: `${line.meterId}-${line.warning.code}`,
            text: commonWarningText(line, line.warning, t, locale),
          },
        ]
      : [],
  );
  return <RunWarnings items={items} label={t.billing.common.warningsLabel} />;
}

/** Per consumer: consumption, split, plot-days, the plots with their kWh and amounts, and the amount. */
export function CommonConsumersTable({
  consumers,
  noTariff = false,
}: {
  consumers: readonly CommonConsumerRunLine[];
  noTariff?: boolean;
}) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.billing.common;
  const columns: Column<CommonConsumerRunLine>[] = [
    {
      key: 'consumer',
      header: c.columns.consumer,
      cell: (line) => (
        <div className="flex flex-col items-start gap-1">
          <Link to={`/meters/${line.meterId}`} className="text-accent underline">
            {consumerLabel(line, t)}
          </Link>
          <span className="flex flex-wrap gap-1">
            <Badge tone={OUTCOME_TONE[line.outcome]}>{c.outcome[line.outcome]}</Badge>
            {line.calculated && <Badge tone="info">{c.calculated}</Badge>}
            {noTariff && <Badge tone="warning">{c.noTariff}</Badge>}
          </span>
        </div>
      ),
    },
    {
      key: 'consumption',
      header: c.columns.consumption,
      numeric: true,
      cell: (line) => formatKwh(line.consumption, locale),
    },
    { key: 'split', header: c.columns.split, cell: (line) => c.split[line.split] },
    { key: 'plotDays', header: c.columns.plotDays, numeric: true, cell: (line) => line.plotDays },
    {
      key: 'plots',
      header: c.columns.plots,
      cell: (line) =>
        line.plots.length === 0 ? (
          <span className="text-ink-muted">{c.noPlots}</span>
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {line.plots.map((plot) => (
              <li key={plot.plotId}>
                {richFormat(plot.amount === undefined ? c.plotNoAmount : c.plotShare, {
                  plot: (
                    <Link to={`/plots/${plot.plotId}`} className="text-accent underline">
                      <UserContent>{plot.plotNumber ?? plot.plotId}</UserContent>
                    </Link>
                  ),
                  kwh: formatKwh(plot.kwh, locale),
                  days: plot.days,
                })}
                {plot.amount !== undefined && (
                  <>
                    {' '}
                    (<Money amount={plot.amount} />)
                  </>
                )}
                {plot.reversed && (
                  <>
                    {' '}
                    <Badge tone="neutral">{c.reversedPlot}</Badge>
                  </>
                )}
                {plot.reason && (
                  <span className="block text-ink-muted">{t.billing.notCharged.reason[plot.reason]}</span>
                )}
              </li>
            ))}
          </ul>
        ),
    },
    {
      key: 'amount',
      header: c.columns.amount,
      numeric: true,
      cell: (line) => (noTariff ? '—' : <Money amount={line.amount} />),
    },
  ];
  return (
    <DataTable
      columns={columns}
      rows={consumers}
      rowKey={(line) => line.meterId}
      caption={`${c.consumersCaption} (${t.charges.line.unit.KWH})`}
      emptyMessage={c.noConsumers}
    />
  );
}
