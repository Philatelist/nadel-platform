import { ApiError, useGetPlot, type PlotDetails } from '@nadel/api-client';
import { format, formatSotkyWithUnit } from '@nadel/shared';
import type { ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, Button, PageHeader, Panel, StatusDot } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { plotStatusLabel } from '../plotStatus';
import { useDocumentTitle } from '../useDocumentTitle';
import { NotFoundPage } from './NotFoundPage';

export function PlotPage() {
  const t = useT();
  const { id = '' } = useParams();
  const plot = useGetPlot(id);
  useDocumentTitle(plot.data ? cardTitle(t, plot.data) : t.plots.title);

  if (plot.error instanceof ApiError && plot.error.status === 404) return <NotFoundPage />;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/plots" className="text-ink-muted hover:text-ink">
        {t.plot.back}
      </Link>
      <QueryBoundary query={plot}>{(data) => <PlotView key={data.id} plot={data} />}</QueryBoundary>
    </div>
  );
}

function cardTitle(t: ReturnType<typeof useT>, plot: PlotDetails): string {
  return format(plot.kind === 'COMMON' ? t.plot.cardTitleCommon : t.plot.cardTitlePlot, {
    number: plot.number,
  });
}

function PlotView({ plot }: { plot: PlotDetails }) {
  const t = useT();
  const { locale } = useLocale();
  const canView = useCan('PLOTS_VIEW');
  const canEdit = useCan('PLOTS_EDIT');
  const navigate = useNavigate();
  const f = t.plot.fields;

  const rows: [string, ReactNode][] = [
    [f.number, plot.number],
    [f.street, plot.street ?? f.none],
    [f.kind, t.plot.kind[plot.kind]],
    [f.status, <StatusDot key="status" status={plot.status} label={plotStatusLabel(t, plot)} />],
  ];
  if (canView) {
    rows.push(
      [
        f.area,
        plot.areaM2 === undefined
          ? f.none
          : format(t.plot.areaValue, {
              sotky: formatSotkyWithUnit(plot.areaM2, locale),
              m2: formatDecimal(String(plot.areaM2), locale),
            }),
      ],
      [f.cadastralNumber, plot.cadastralNumber ?? f.none],
      [f.building, plot.hasBuilding ? t.plot.hasBuilding : t.plot.noBuilding],
      [f.notes, plot.notes ?? f.none],
    );
  }

  return (
    <>
      <PageHeader
        title={cardTitle(t, plot)}
        badge={plot.archived && <Badge tone="neutral">{t.plots.archived}</Badge>}
        actions={
          canEdit && <Button onClick={() => void navigate(`/plots/${plot.id}/edit`)}>{t.plot.edit}</Button>
        }
      />
      <Panel>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-[minmax(10rem,max-content)_1fr]">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">{label}</dt>
              <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{value}</dd>
            </div>
          ))}
        </dl>
      </Panel>
      {canView && (
        <Panel title={t.plot.ownersTitle}>
          <p className="text-ink-muted">{t.plot.ownersPlaceholder}</p>
        </Panel>
      )}
    </>
  );
}

/** Swaps the decimal point of a plain decimal string for the locale's separator. */
function formatDecimal(value: string, locale: string): string {
  const separator = new Intl.NumberFormat(locale).formatToParts(1.1).find((p) => p.type === 'decimal')?.value;
  return separator ? value.replace('.', separator) : value;
}
