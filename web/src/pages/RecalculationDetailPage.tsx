import { useGetRecalculation, type Recalculation, type RecalculationLine } from '@nadel/api-client';
import { format, formatDateTime, formatDay, formatMonth } from '@nadel/shared';
import { Link, useParams } from 'react-router';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { MonthTotals } from '../components/charges/MonthTotals';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { actionLabel, causeLabel, changeReasonLabel } from '../components/recalculation/recalcLabels';
import { QueryBoundary } from '../components/QueryBoundary';
import { formatUser, UserContent } from '../components/UserContent';
import { DataTable, Money, PageHeader, Panel, type Column } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

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
function groupLines(lines: readonly RecalculationLine[]): PlotGroup[] {
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

/** `/charges/recalculations/:id`: one recalculation, read only. */
export function RecalculationDetailPage() {
  const t = useT();
  const r = t.recalculation;
  useDocumentTitle(r.detail.title);
  const { id = '' } = useParams();
  const query = useGetRecalculation(id);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={r.detail.title} />
      <ChargesTabs />
      <Link to="/charges/recalculations" className="w-fit text-accent underline">
        {r.detail.back}
      </Link>
      <QueryBoundary query={query}>{(data) => <RecalculationView recalculation={data} />}</QueryBoundary>
    </div>
  );
}

function RecalculationView({ recalculation }: { recalculation: Recalculation }) {
  const t = useT();
  const { locale } = useLocale();
  const d = t.recalculation.detail;
  const groups = groupLines(recalculation.lines);
  const info: [string, React.ReactNode][] = [
    [d.cause, causeLabel(t, recalculation)],
    [d.months, recalculation.scope.months.map((m) => formatMonth(m, locale)).join(', ')],
    [d.plots, String(recalculation.plotCount)],
  ];
  if (recalculation.reason)
    info.push([d.reason, <UserContent key="reason">{recalculation.reason}</UserContent>]);
  if (recalculation.createdAt) info.push([d.bookedAt, formatDateTime(recalculation.createdAt, locale)]);
  info.push([
    d.author,
    recalculation.createdByName ? (
      <UserContent key="author">{recalculation.createdByName}</UserContent>
    ) : (
      t.recalculation.system
    ),
  ]);
  return (
    <>
      <Panel title={d.scope}>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-[minmax(9rem,max-content)_1fr]">
          {info.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">{label}</dt>
              <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{value}</dd>
            </div>
          ))}
        </dl>
      </Panel>
      <Panel title={d.totals}>
        <MonthTotals totals={recalculation.totals} total={recalculation.total} />
      </Panel>
      {groups.length === 0 ? (
        <p className="text-ink-muted">{d.noLines}</p>
      ) : (
        <section aria-label={d.linesCaption} className="flex flex-col gap-4">
          {groups.map((plot) => (
            <PlotLines key={plot.plotId} plot={plot} />
          ))}
        </section>
      )}
    </>
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
      header: d.columns.booked,
      numeric: true,
      cell: (line) => <Money amount={line.booked} />,
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
      cell: (line) => <Money amount={line.difference} signed />,
    },
    { key: 'action', header: d.columns.action, cell: (line) => actionLabel(t, line, locale) },
    {
      key: 'reasons',
      header: d.columns.reasons,
      cell: (line) =>
        line.reasons.length === 0 ? (
          ''
        ) : (
          <ul className="flex flex-col gap-0.5">
            {line.reasons.map((reason) => (
              <li key={reason}>{changeReasonLabel(t, reason)}</li>
            ))}
          </ul>
        ),
    },
    {
      key: 'created',
      header: d.columns.created,
      cell: (line) => {
        const links: [string | undefined, string][] = [
          [line.originalChargeId, d.created.original],
          [line.reversalChargeId, d.created.reversal],
          [line.newChargeId, d.created.newCharge],
          [line.adjustmentChargeId, d.created.adjustment],
        ];
        return (
          <ul className="flex flex-col gap-0.5">
            {links.map(([chargeId, label]) =>
              chargeId ? (
                <li key={label}>
                  <Link to={`/plots/${line.plotId}?charge=${chargeId}`} className="text-accent underline">
                    {label}
                  </Link>
                </li>
              ) : null,
            )}
          </ul>
        );
      },
    },
  ];
  return (
    <div className="flex flex-col gap-2 rounded-panel border border-line bg-surface p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-[17px] font-semibold">{formatUser(d.plot, { number: plot.plotNumber })}</h2>
        <Link to={`/plots/${plot.plotId}`} className="text-accent underline">
          {d.plotAccount}
        </Link>
      </div>
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
