import {
  ApiError,
  getListMetersQueryKey,
  getListPlotMetersQueryKey,
  useDeleteMeter,
  useGetMeter,
  type Meter,
} from '@nadel/api-client';
import { formatDay, formatMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { EditMeterDialog } from '../components/meters/EditMeterDialog';
import { RemoveMeterDialog } from '../components/meters/RemoveMeterDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent, formatUser } from '../components/UserContent';
import { Badge, Button, Notice, PageHeader, Panel } from '../components/ui';
import { useCanManageMeters } from '../hooks/useCanManageMeters';
import { useLocale, useT } from '../i18n';
import { meterLabel, resourceUnit } from '../meters';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';
import { NotFoundPage } from './NotFoundPage';

export function MeterPage() {
  const t = useT();
  const { id = '' } = useParams();
  const meter = useGetMeter(id);
  useDocumentTitle(meter.data ? meterLabel(meter.data) : t.meters.title);

  if (meter.error instanceof ApiError && meter.error.status === 404) return <NotFoundPage />;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/meters" className="text-ink-muted hover:text-ink">
        {t.meters.back}
      </Link>
      <QueryBoundary query={meter}>{(data) => <MeterView key={data.id} meter={data} />}</QueryBoundary>
    </div>
  );
}

function MeterView({ meter }: { meter: Meter }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.meters;
  const f = m.fields;
  const canManage = useCanManageMeters();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<'edit' | 'remove' | 'delete'>();
  const [actionError, setActionError] = useState<string>();
  const unit = resourceUnit(t, meter.resource);
  const value = (v: string): string => `${formatMeterValue(v, locale)} ${unit}`;

  const remove = useDeleteMeter({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() });
        if (meter.plot) {
          await queryClient.invalidateQueries({ queryKey: getListPlotMetersQueryKey(meter.plot.id) });
        }
        void navigate('/meters');
      },
      onError: (error: unknown) => {
        setDialog(undefined);
        setActionError(problemMessage(t, error));
      },
    },
  });

  const rows: [string, ReactNode][] = [
    [f.kind, m.kind[meter.kind]],
    [f.resource, m.resource[meter.resource]],
  ];
  if (meter.plot) {
    rows.push([
      f.plot,
      <Link key="plot" to={`/plots/${meter.plot.id}`} className="text-accent underline">
        <UserContent>{meter.plot.number}</UserContent>
      </Link>,
    ]);
  }
  rows.push([f.serial, <UserContent>{meter.serial}</UserContent>]);
  if (meter.name) rows.push([f.name, <UserContent>{meter.name}</UserContent>]);
  rows.push(
    [f.installedOn, formatDay(meter.installedOn, locale)],
    [f.initialReading, value(meter.initialReading)],
  );
  if (meter.removedOn) rows.push([f.removedOn, formatDay(meter.removedOn, locale)]);
  if (meter.finalReading) rows.push([f.finalReading, value(meter.finalReading)]);
  rows.push([
    f.lastReading,
    `${value(meter.lastReading.value)}, ${formatDay(meter.lastReading.takenOn, locale)}`,
  ]);
  if (meter.note) rows.push([f.note, <UserContent>{meter.note}</UserContent>]);

  return (
    <>
      <PageHeader
        title={formatUser(m.cardTitle, { label: meterLabel(meter) })}
        badge={
          <Badge tone={meter.active ? 'success' : 'neutral'}>{meter.active ? m.active : m.removed}</Badge>
        }
        actions={
          canManage && (
            <>
              <Button
                onClick={() => {
                  setActionError(undefined);
                  setDialog('edit');
                }}
              >
                {m.edit}
              </Button>
              {meter.active && (
                <Button
                  variant="secondary"
                  onClick={() => {
                    setActionError(undefined);
                    setDialog('remove');
                  }}
                >
                  {m.remove}
                </Button>
              )}
              {meter.deletable && (
                <Button
                  variant="danger"
                  onClick={() => {
                    setActionError(undefined);
                    setDialog('delete');
                  }}
                >
                  {m.delete}
                </Button>
              )}
            </>
          )
        }
      />
      {actionError && <Notice tone="danger">{actionError}</Notice>}
      <Panel>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-[minmax(10rem,max-content)_1fr]">
          {rows.map(([label, content]) => (
            <div key={label} className="contents">
              <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">{label}</dt>
              <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{content}</dd>
            </div>
          ))}
        </dl>
      </Panel>
      <Panel title={m.historyTitle}>
        <p className="text-ink-muted">{m.historyPlaceholder}</p>
      </Panel>
      {dialog === 'edit' && (
        <EditMeterDialog
          meter={meter}
          onSaved={() => setDialog(undefined)}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog === 'remove' && (
        <RemoveMeterDialog
          meter={meter}
          onRemoved={() => setDialog(undefined)}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog === 'delete' && (
        <ConfirmDialog
          title={m.deleteTitle}
          message={m.deleteText}
          confirmLabel={m.delete}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: meter.id })}
          onCancel={() => setDialog(undefined)}
        />
      )}
    </>
  );
}
