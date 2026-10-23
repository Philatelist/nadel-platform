import {
  useConnectWaterSourcePlots,
  useDisconnectWaterSourcePlots,
  useListWaterSources,
  type WaterSource,
  type WaterSourcePlot,
} from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useCan } from '../../me';
import { problemFieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { QueryBoundary } from '../QueryBoundary';
import { PlotPicker, type PickedPlot } from '../tariffs/PlotPicker';
import { UserContent } from '../UserContent';
import { Button, Notice, Panel, TextField } from '../ui';
import { WaterSourceName, invalidateAfterWaterChange, waterProblemMessage } from './waterShared';

type Dialog = { kind: 'connect' | 'disconnect'; source: WaterSource };

/** The water sources with their connected plots (PLOTS_VIEW); METERS_MANAGE connects and disconnects plots in bulk. */
export function WaterSourcesSection() {
  const t = useT();
  const w = t.water;
  const query = useListWaterSources();
  const canManage = useCan('METERS_MANAGE');
  const [dialog, setDialog] = useState<Dialog>();
  return (
    <Panel title={w.sourcesTitle}>
      <p className="mb-3 text-sm text-ink-muted">{w.sourcesText}</p>
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={w.sourcesEmpty}>
        {(data) => (
          <ul className="flex flex-col gap-4">
            {data.map((source) => (
              <li key={source.id} className="flex flex-col gap-2">
                <h3 className="font-medium">
                  <WaterSourceName source={source} />
                </h3>
                <SourcePlots plots={source.plots} />
                {canManage && (
                  <div className="flex flex-wrap gap-2">
                    <Button variant="secondary" onClick={() => setDialog({ kind: 'connect', source })}>
                      {w.connectPlots}
                    </Button>
                    <Button variant="secondary" onClick={() => setDialog({ kind: 'disconnect', source })}>
                      {w.disconnectPlots}
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </QueryBoundary>
      {dialog?.kind === 'connect' && (
        <ConnectPlotsDialog source={dialog.source} onClose={() => setDialog(undefined)} />
      )}
      {dialog?.kind === 'disconnect' && (
        <DisconnectPlotsDialog source={dialog.source} onClose={() => setDialog(undefined)} />
      )}
    </Panel>
  );
}

function SourcePlots({ plots }: { plots: WaterSourcePlot[] }) {
  const t = useT();
  const { locale } = useLocale();
  if (plots.length === 0) return <p className="text-sm text-ink-muted">{t.water.sourceNoPlots}</p>;
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
      {plots.map((p) => (
        <li key={p.connectionId}>
          <UserContent>{p.plotNumber}</UserContent>
          <span className="text-ink-muted">
            {': '}
            {p.until
              ? format(t.water.period, { from: formatDay(p.from, locale), until: formatDay(p.until, locale) })
              : format(t.water.periodOpen, { from: formatDay(p.from, locale) })}
          </span>
        </li>
      ))}
    </ul>
  );
}

interface BulkFormProps {
  title: string;
  dateLabel: string;
  dateField: 'from' | 'until';
  plots: readonly PickedPlot[];
  error: unknown;
  pending: boolean;
  date: string;
  onDate: (value: string) => void;
  onSubmit: () => void;
  onClose: () => void;
  onRemove?: (plot: PickedPlot) => void;
  picker?: ReactNode;
}

/** The dialog body shared by connect and disconnect: the date, the plots, the errors named after their plot. */
function BulkForm(props: BulkFormProps) {
  const t = useT();
  const w = t.water;
  const fieldErrors = problemFieldErrors(t, props.error);
  const plotErrors = props.plots.map((_, index) => fieldErrors[`plotIds[${index}]`]);
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;
  function submit(event: FormEvent) {
    event.preventDefault();
    props.onSubmit();
  }
  return (
    <Modal title={props.title} onClose={props.onClose} size="lg">
      <form className="mt-4 flex flex-col gap-4" noValidate onSubmit={submit}>
        <p className="text-sm text-ink-muted">{w.bulkNote}</p>
        <TextField
          label={props.dateLabel}
          type="date"
          value={props.date}
          error={fieldErrors[props.dateField]}
          onChange={(e) => props.onDate(e.target.value)}
        />
        {props.picker}
        <div className="flex flex-col gap-1">
          <h3 className="text-sm font-medium text-ink-muted">{w.chosenPlots}</h3>
          <ul className="flex flex-col gap-1">
            {props.plots.map((plot, index) => (
              <li key={plot.id} className="flex flex-wrap items-center gap-2">
                <UserContent>{plot.number}</UserContent>
                {props.onRemove && (
                  <Button
                    variant="secondary"
                    className="min-h-8 px-2 text-sm"
                    aria-label={format(w.removePlot, { plot: plot.number })}
                    onClick={() => props.onRemove?.(plot)}
                  >
                    ×
                  </Button>
                )}
                {plotErrors[index] && (
                  <span role="alert" className="text-sm text-danger">
                    {plotErrors[index]}
                  </span>
                )}
              </li>
            ))}
          </ul>
          {fieldErrors.plotIds && (
            <span role="alert" className="text-sm text-danger">
              {fieldErrors.plotIds}
            </span>
          )}
        </div>
        {props.error !== undefined && !hasFieldErrors && (
          <Notice tone="danger">{waterProblemMessage(t, props.error)}</Notice>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={props.onClose}>
            {w.cancel}
          </Button>
          <Button type="submit" disabled={props.pending}>
            {props.pending ? w.saving : w.apply}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function ConnectPlotsDialog({ source, onClose }: { source: WaterSource; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [plots, setPlots] = useState<PickedPlot[]>([]);
  const [from, setFrom] = useState(kyivToday());
  const [error, setError] = useState<unknown>();
  const connect = useConnectWaterSourcePlots();
  return (
    <BulkForm
      title={format(t.water.bulkConnectTitle, { source: sourceTitle(t.water.cooperativeSupply, source) })}
      dateLabel={t.water.from}
      dateField="from"
      plots={plots}
      error={error}
      pending={connect.isPending}
      date={from}
      onDate={setFrom}
      onClose={onClose}
      onRemove={(plot) => setPlots((current) => current.filter((p) => p.id !== plot.id))}
      picker={
        <PlotPicker
          label={t.water.pickPlot}
          excludeIds={plots.map((p) => p.id)}
          onPick={(plot) => setPlots((current) => [...current, plot])}
        />
      }
      onSubmit={() => {
        setError(undefined);
        connect.mutate(
          { sourceId: source.id, data: { plotIds: plots.map((p) => p.id), from } },
          {
            onSuccess: async () => {
              await invalidateAfterWaterChange(queryClient);
              onClose();
            },
            onError: setError,
          },
        );
      }}
    />
  );
}

function DisconnectPlotsDialog({ source, onClose }: { source: WaterSource; onClose: () => void }) {
  const t = useT();
  const w = t.water;
  const queryClient = useQueryClient();
  const connected = source.plots.filter((p) => p.until === undefined);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [until, setUntil] = useState(kyivToday());
  const [error, setError] = useState<unknown>();
  const disconnect = useDisconnectWaterSourcePlots();
  const picked: PickedPlot[] = connected
    .filter((p) => chosen.has(p.plotId))
    .map((p) => ({ id: p.plotId, number: p.plotNumber }));
  return (
    <BulkForm
      title={format(w.bulkDisconnectTitle, { source: sourceTitle(w.cooperativeSupply, source) })}
      dateLabel={w.until}
      dateField="until"
      plots={picked}
      error={error}
      pending={disconnect.isPending}
      date={until}
      onDate={setUntil}
      onClose={onClose}
      picker={
        connected.length === 0 ? (
          <p className="text-sm text-ink-muted">{w.nothingToDisconnect}</p>
        ) : (
          <fieldset className="flex flex-col gap-1">
            <legend className="text-sm font-medium text-ink-muted">{w.connectedPlots}</legend>
            {connected.map((p) => (
              <label key={p.connectionId} className="flex min-h-11 items-center gap-2">
                <input
                  type="checkbox"
                  checked={chosen.has(p.plotId)}
                  onChange={(e) =>
                    setChosen((current) => {
                      const next = new Set(current);
                      if (e.target.checked) next.add(p.plotId);
                      else next.delete(p.plotId);
                      return next;
                    })
                  }
                />
                <UserContent>{p.plotNumber}</UserContent>
              </label>
            ))}
          </fieldset>
        )
      }
      onSubmit={() => {
        setError(undefined);
        disconnect.mutate(
          { sourceId: source.id, data: { plotIds: picked.map((p) => p.id), until } },
          {
            onSuccess: async () => {
              await invalidateAfterWaterChange(queryClient);
              onClose();
            },
            onError: setError,
          },
        );
      }}
    />
  );
}

/** A plain-text title for the dialog: a meter's name is user content but a dialog title is text. */
function sourceTitle(cooperativeText: string, source: WaterSource): string {
  return source.kind === 'COOPERATIVE_SUPPLY' || source.name === undefined ? cooperativeText : source.name;
}
