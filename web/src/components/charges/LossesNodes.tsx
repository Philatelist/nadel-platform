import type { LossesRunNode, LossesNodeOutcome, LossesWarning } from '@nadel/api-client';
import { format, formatDay, formatPercent, type Messages } from '@nadel/shared';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Badge, DataTable, Money, type BadgeTone, type Column } from '../ui';
import { formatKwh } from './lossesFormat';
import { richFormat, RunWarnings, type RunWarningItem } from './RunWarnings';

const OUTCOME_TONE: Readonly<Record<LossesNodeOutcome, BadgeTone>> = {
  CHARGED: 'success',
  NEGATIVE: 'warning',
  UNKNOWN: 'warning',
  SKIPPED_ABOVE: 'neutral',
  ALREADY_CHARGED: 'info',
};

/** A node by its name (user content), or by its kind when it has none. */
function nodeLabel(node: Pick<LossesRunNode, 'name' | 'kind'>, t: Messages): ReactNode {
  return node.name ? <UserContent>{node.name}</UserContent> : t.meters.kind[node.kind];
}

function meterLabel(warning: LossesWarning, t: Messages): ReactNode {
  const l = t.billing.losses.warnings;
  if (warning.meterName) return <UserContent>{warning.meterName}</UserContent>;
  if (warning.plotNumber) {
    return richFormat(l.meterPlot, { plot: <UserContent>{warning.plotNumber}</UserContent> });
  }
  return warning.meterKind ? t.meters.kind[warning.meterKind] : l.meterFallback;
}

/** The text of one node's warning, from its code and parameters. */
export function lossesWarningText(node: LossesRunNode, warning: LossesWarning, t: Messages, locale: string) {
  const l = t.billing.losses.warnings;
  const nodeText = nodeLabel(node, t);
  switch (warning.code) {
    case 'NEGATIVE_LOSSES':
      return richFormat(l.NEGATIVE_LOSSES, { node: nodeText, losses: formatKwh(node.losses, locale) });
    case 'UNKNOWN_VALUE':
      return warning.lastReadingOn
        ? richFormat(l.UNKNOWN_VALUE, {
            node: nodeText,
            meter: meterLabel(warning, t),
            date: formatDay(warning.lastReadingOn, locale),
          })
        : richFormat(l.UNKNOWN_VALUE_NEVER, { node: nodeText, meter: meterLabel(warning, t) });
    case 'SKIPPED_ABOVE':
      return richFormat(l.SKIPPED_ABOVE, { node: nodeText, meter: meterLabel(warning, t) });
    case 'ALREADY_CHARGED': {
      const range =
        warning.from && warning.to
          ? format(t.billing.runHistory.range, {
              from: formatDay(warning.from, locale),
              to: formatDay(warning.to, locale),
            })
          : '…';
      return richFormat(l.ALREADY_CHARGED, {
        node: nodeText,
        run: warning.runId ? (
          <Link to={`/charges/losses?run=${warning.runId}`} className="text-accent underline">
            {range}
          </Link>
        ) : (
          range
        ),
      });
    }
    case 'NO_PLOT_CONSUMPTION':
      return richFormat(l.NO_PLOT_CONSUMPTION, { node: nodeText });
  }
}

/** The warnings of the nodes of a preview or a booked run. */
export function LossesWarnings({ nodes }: { nodes: readonly LossesRunNode[] }) {
  const t = useT();
  const { locale } = useLocale();
  const items: RunWarningItem[] = nodes.flatMap((node) =>
    node.warning
      ? [
          {
            key: `${node.meterId}-${node.warning.code}`,
            text: lossesWarningText(node, node.warning, t, locale),
          },
        ]
      : [],
  );
  return <RunWarnings items={items} label={t.billing.losses.warningsLabel} />;
}

/** Per node: what it shows, what its children used, the losses, the plots with their shares and the amount. */
export function LossesNodesTable({ nodes }: { nodes: readonly LossesRunNode[] }) {
  const t = useT();
  const { locale } = useLocale();
  const l = t.billing.losses;
  const kwh = t.charges.line.unit.KWH;
  const columns: Column<LossesRunNode>[] = [
    {
      key: 'node',
      header: l.columns.node,
      cell: (node) => (
        <div className="flex flex-col items-start gap-1">
          <Link to={`/meters/${node.meterId}`} className="text-accent underline">
            {nodeLabel(node, t)}
          </Link>
          <span className="flex flex-wrap gap-1">
            <Badge tone={OUTCOME_TONE[node.outcome]}>{l.outcome[node.outcome]}</Badge>
            {node.calculated && <Badge tone="info">{l.calculated}</Badge>}
          </span>
        </div>
      ),
    },
    {
      key: 'consumption',
      header: l.columns.consumption,
      numeric: true,
      cell: (n) => formatKwh(n.consumption, locale),
    },
    {
      key: 'children',
      header: l.columns.children,
      numeric: true,
      cell: (n) => formatKwh(n.children, locale),
    },
    { key: 'losses', header: l.columns.losses, numeric: true, cell: (n) => formatKwh(n.losses, locale) },
    {
      key: 'pct',
      header: l.columns.pct,
      numeric: true,
      cell: (n) => (n.pct === undefined ? '—' : formatPercent(n.pct, locale)),
    },
    {
      key: 'plots',
      header: l.columns.plots,
      cell: (node) =>
        node.plots.length === 0 ? (
          <span className="text-ink-muted">{l.noPlots}</span>
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {node.plots.map((plot) => (
              <li key={plot.plotId}>
                {richFormat(plot.amount === undefined ? l.plotNoAmount : l.plotShare, {
                  plot: (
                    <Link to={`/plots/${plot.plotId}`} className="text-accent underline">
                      <UserContent>{plot.plotNumber ?? plot.plotId}</UserContent>
                    </Link>
                  ),
                  kwh: formatKwh(plot.kwh, locale),
                })}
                {plot.amount !== undefined && (
                  <>
                    {' '}
                    (<Money amount={plot.amount} />)
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
    { key: 'amount', header: l.columns.amount, numeric: true, cell: (n) => <Money amount={n.amount} /> },
  ];
  return (
    <DataTable
      columns={columns}
      rows={nodes}
      rowKey={(node) => node.meterId}
      caption={`${l.nodesCaption} (${kwh})`}
      emptyMessage={l.noNodes}
    />
  );
}
