import {
  ApiError,
  getGetPlotQueryKey,
  getListPlotsQueryKey,
  useArchivePlot,
  useDeletePlot,
  useGetPlot,
  useRestorePlot,
  type PlotDetails,
} from '@nadel/api-client';
import { format, formatSotkyWithUnit } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { UserContent, formatUser } from '../components/UserContent';
import { PlotTariffsPanel } from '../components/tariffs/PlotTariffsPanel';
import { OwnersPanel } from '../components/ownership/OwnersPanel';
import { Badge, Button, Notice, PageHeader, Panel, StatusDot } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan, useMe } from '../me';
import { useCanEditPlots } from '../hooks/useCanEditPlots';
import { plotStatusLabel } from '../plotStatus';
import { problemMessage } from '../problem';
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

function cardTitleNode(t: ReturnType<typeof useT>, plot: PlotDetails): ReactNode {
  return formatUser(plot.kind === 'COMMON' ? t.plot.cardTitleCommon : t.plot.cardTitlePlot, {
    number: plot.number,
  });
}

function PlotView({ plot }: { plot: PlotDetails }) {
  const t = useT();
  const { locale } = useLocale();
  const canView = useCan('PLOTS_VIEW');
  const canEdit = useCanEditPlots();
  const isOwner = useMe().ownedPlots.some((p) => p.plotId === plot.id);
  const navigate = useNavigate();
  const f = t.plot.fields;
  const queryClient = useQueryClient();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [actionError, setActionError] = useState<string>();

  const toggleOptions = {
    onSuccess: async (updated: PlotDetails) => {
      queryClient.setQueryData(getGetPlotQueryKey(plot.id), updated);
      await queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() });
      await queryClient.invalidateQueries({ queryKey: getGetPlotQueryKey(plot.id) });
    },
    onError: (error: unknown) => setActionError(problemMessage(t, error)),
  };
  const archive = useArchivePlot({ mutation: toggleOptions });
  const restore = useRestorePlot({ mutation: toggleOptions });
  const remove = useDeletePlot({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() });
        void navigate('/plots');
      },
      onError: (error: unknown) => {
        setConfirmingDelete(false);
        setActionError(problemMessage(t, error));
      },
    },
  });
  const isToggling = archive.isPending || restore.isPending;

  const rows: [string, ReactNode][] = [
    [f.number, <UserContent>{plot.number}</UserContent>],
    [f.street, plot.street ? <UserContent>{plot.street}</UserContent> : f.none],
    [f.kind, t.plot.kind[plot.kind]],
  ];
  if (plot.status !== undefined) {
    rows.push([f.status, <StatusDot key="status" status={plot.status} label={plotStatusLabel(t, plot)} />]);
  }
  // the server sends the details of an owned plot to its owner, so show whatever arrived
  if (canView || isOwner) {
    rows.push(
      [
        f.area,
        plot.areaM2 === undefined
          ? f.none
          : format(t.plot.areaValue, {
              sotky: formatSotkyWithUnit(plot.areaM2, locale, t.units.sotka),
              m2: formatM2(plot.areaM2, locale),
            }),
      ],
      [f.cadastralNumber, plot.cadastralNumber ? <UserContent>{plot.cadastralNumber}</UserContent> : f.none],
      [f.building, plot.hasBuilding ? t.plot.hasBuilding : t.plot.noBuilding],
    );
    if (canView) rows.push([f.notes, plot.notes ? <UserContent>{plot.notes}</UserContent> : f.none]);
  }

  return (
    <>
      <PageHeader
        title={cardTitleNode(t, plot)}
        badge={plot.archived && <Badge tone="neutral">{t.plots.archived}</Badge>}
        actions={
          canEdit && (
            <>
              <Button onClick={() => void navigate(`/plots/${plot.id}/edit`)}>{t.plot.edit}</Button>
              <Button
                variant="secondary"
                disabled={isToggling}
                onClick={() => {
                  setActionError(undefined);
                  (plot.archived ? restore : archive).mutate({ id: plot.id });
                }}
              >
                {plot.archived ? t.plot.restore : t.plot.archive}
              </Button>
              {!plot.archived && (
                <Button
                  variant="danger"
                  onClick={() => {
                    setActionError(undefined);
                    setConfirmingDelete(true);
                  }}
                >
                  {t.plot.delete}
                </Button>
              )}
            </>
          )
        }
      />
      {actionError && <Notice tone="danger">{actionError}</Notice>}
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
      {(canView || isOwner) &&
        (plot.kind === 'COMMON' ? (
          <Panel title={t.plot.ownersTitle}>
            <p className="text-ink-muted">{t.ownership.commonLand}</p>
          </Panel>
        ) : (
          <OwnersPanel plotId={plot.id} kind={plot.kind} archived={plot.archived} />
        ))}
      {plot.kind !== 'COMMON' && <PlotTariffsPanel plotId={plot.id} />}
      {confirmingDelete && (
        <ConfirmDialog
          title={t.plot.deleteTitle}
          message={t.plot.deleteText}
          confirmLabel={t.plot.delete}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: plot.id })}
          onCancel={() => setConfirmingDelete(false)}
        />
      )}
    </>
  );
}

/** m² with the locale's grouping and decimal separator; the text is split, never turned into a float. */
function formatM2(value: string | number, locale: string): string {
  const [integer = '0', decimals] = String(value).split('.');
  const grouped = new Intl.NumberFormat(locale).format(BigInt(integer));
  const separator =
    new Intl.NumberFormat(locale).formatToParts(1.1).find((p) => p.type === 'decimal')?.value ?? '.';
  return decimals ? grouped + separator + decimals : grouped;
}
