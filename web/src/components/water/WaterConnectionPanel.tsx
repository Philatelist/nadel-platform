import {
  ApiError,
  useConnectPlotWater,
  useEndPlotWaterConnection,
  useGetPlotWaterConnections,
  useListWaterSources,
  type WaterConnection,
} from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useCan } from '../../me';
import { problemFieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Panel, Select, TextField } from '../ui';
import { WaterSourceName, invalidateAfterWaterChange, waterProblemMessage } from './waterShared';

type Dialog = 'connect' | 'disconnect';

/** Where a plot takes its water from today and the history; METERS_MANAGE connects and disconnects it. */
export function WaterConnectionPanel({ plotId, archived = false }: { plotId: string; archived?: boolean }) {
  const t = useT();
  const { locale } = useLocale();
  const w = t.water;
  const query = useGetPlotWaterConnections(plotId);
  const canManage = useCan('METERS_MANAGE');
  const [dialog, setDialog] = useState<Dialog>();
  if (query.error instanceof ApiError && (query.error.status === 404 || query.error.status === 403)) {
    return null;
  }
  const period = (c: WaterConnection): string =>
    c.until
      ? format(w.period, { from: formatDay(c.from, locale), until: formatDay(c.until, locale) })
      : format(w.periodOpen, { from: formatDay(c.from, locale) });

  return (
    <Panel title={w.title}>
      <QueryBoundary query={query}>
        {(data) => {
          // the lasting connection (no end) decides the buttons: it can be ended, and while it exists a new one cannot start
          const lasting = data.connections.find((c) => c.until === undefined);
          return (
            <div className="flex flex-col gap-3">
              <p className="font-medium">
                {data.current && data.connected
                  ? formatWaterLine(w.connectedTo, data.current, locale)
                  : w.notConnected}
              </p>
              {canManage && !archived && (
                <div className="flex flex-wrap gap-2">
                  {!lasting && <Button onClick={() => setDialog('connect')}>{w.connect}</Button>}
                  {lasting && (
                    <Button variant="secondary" onClick={() => setDialog('disconnect')}>
                      {w.disconnect}
                    </Button>
                  )}
                </div>
              )}
              <h3 className="text-sm font-medium text-ink-muted">{w.historyTitle}</h3>
              {data.connections.length === 0 ? (
                <p className="text-ink-muted">{w.noHistory}</p>
              ) : (
                <ul className="divide-y divide-line-subtle">
                  {data.connections.map((c) => (
                    <li key={c.id} className="flex flex-wrap items-center gap-2 py-2">
                      <WaterSourceName source={c.source} />
                      <span className="text-ink-muted">{period(c)}</span>
                    </li>
                  ))}
                </ul>
              )}
              {dialog === 'connect' && <ConnectDialog plotId={plotId} onClose={() => setDialog(undefined)} />}
              {dialog === 'disconnect' && lasting && (
                <DisconnectDialog plotId={plotId} connection={lasting} onClose={() => setDialog(undefined)} />
              )}
            </div>
          );
        }}
      </QueryBoundary>
    </Panel>
  );
}

function formatWaterLine(template: string, connection: WaterConnection, locale: string) {
  return (
    <>
      {template.split(/(\{source\}|\{date\})/).map((part, index) => {
        if (part === '{source}') return <WaterSourceName key={index} source={connection.source} />;
        if (part === '{date}') return <span key={index}>{formatDay(connection.from, locale)}</span>;
        return <span key={index}>{part}</span>;
      })}
    </>
  );
}

function ConnectDialog({ plotId, onClose }: { plotId: string; onClose: () => void }) {
  const t = useT();
  const w = t.water;
  const queryClient = useQueryClient();
  const sources = useListWaterSources();
  const [sourceId, setSourceId] = useState('cooperative');
  const [from, setFrom] = useState(kyivToday());
  const [error, setError] = useState<unknown>();
  const connect = useConnectPlotWater();
  const fieldErrors = problemFieldErrors(t, error);

  function submit(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    connect.mutate(
      {
        id: plotId,
        data: { from, ...(sourceId === 'cooperative' ? {} : { sourceMeterId: sourceId }) },
      },
      {
        onSuccess: async () => {
          await invalidateAfterWaterChange(queryClient, plotId);
          onClose();
        },
        onError: setError,
      },
    );
  }

  return (
    <Modal title={w.connectTitle} onClose={onClose}>
      <form className="mt-4 flex flex-col gap-4" noValidate onSubmit={submit}>
        <Select label={w.source} value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
          {(sources.data ?? [{ id: 'cooperative', kind: 'COOPERATIVE_SUPPLY' as const, plots: [] }]).map(
            (source) => (
              <option key={source.id} value={source.id}>
                {source.kind === 'COOPERATIVE_SUPPLY' ? w.cooperativeSupply : source.name}
              </option>
            ),
          )}
        </Select>
        <TextField
          label={w.from}
          type="date"
          value={from}
          error={fieldErrors.from}
          onChange={(e) => setFrom(e.target.value)}
        />
        {error !== undefined && Object.keys(fieldErrors).length === 0 && (
          <Notice tone="danger">{waterProblemMessage(t, error)}</Notice>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {w.cancel}
          </Button>
          <Button type="submit" disabled={connect.isPending}>
            {connect.isPending ? w.saving : w.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function DisconnectDialog({
  plotId,
  connection,
  onClose,
}: {
  plotId: string;
  connection: WaterConnection;
  onClose: () => void;
}) {
  const t = useT();
  const w = t.water;
  const queryClient = useQueryClient();
  const [until, setUntil] = useState(kyivToday());
  const [error, setError] = useState<unknown>();
  const end = useEndPlotWaterConnection();
  const fieldErrors = problemFieldErrors(t, error);

  function submit(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    end.mutate(
      { id: plotId, connectionId: connection.id, data: { until } },
      {
        onSuccess: async () => {
          await invalidateAfterWaterChange(queryClient, plotId);
          onClose();
        },
        onError: setError,
      },
    );
  }

  return (
    <Modal title={w.disconnectTitle} onClose={onClose}>
      <form className="mt-4 flex flex-col gap-4" noValidate onSubmit={submit}>
        <TextField
          label={w.until}
          type="date"
          value={until}
          min={connection.from}
          error={fieldErrors.until}
          onChange={(e) => setUntil(e.target.value)}
        />
        {error !== undefined && Object.keys(fieldErrors).length === 0 && (
          <Notice tone="danger">{waterProblemMessage(t, error)}</Notice>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {w.cancel}
          </Button>
          <Button type="submit" disabled={end.isPending}>
            {end.isPending ? w.saving : w.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
