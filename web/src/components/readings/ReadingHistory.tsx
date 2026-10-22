import {
  getGetMeterQueryKey,
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  useDeleteReading,
  useListMeterReadings,
  type Meter,
  type MeterReading,
  type ReadingStatus,
} from '@nadel/api-client';
import { format, formatDay, formatMeterValue } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { resourceUnit } from '../../meters';
import { problemMessage } from '../../problem';
import { ConfirmDialog } from '../ConfirmDialog';
import { QueryBoundary } from '../QueryBoundary';
import { formatUser } from '../UserContent';
import { Badge, Button, DataTable, Notice, type BadgeTone, type Column } from '../ui';
import { CorrectReadingDialog } from './CorrectReadingDialog';

const STATUS_TONE: Readonly<Record<ReadingStatus, BadgeTone>> = {
  APPROVED: 'success',
  PENDING: 'info',
  REJECTED: 'warning',
  SUPERSEDED: 'neutral',
};

/** The readings of a meter with the interval each counted one closes; correct and delete where the server allows. */
export function ReadingHistory({ meter }: { meter: Meter }) {
  const t = useT();
  const { locale } = useLocale();
  const h = t.readings.history;
  const queryClient = useQueryClient();
  const query = useListMeterReadings(meter.id);
  const unit = resourceUnit(t, meter.resource);
  const [dialog, setDialog] = useState<{ kind: 'correct' | 'delete'; reading: MeterReading }>();
  const [actionError, setActionError] = useState<string>();

  const remove = useDeleteReading({
    mutation: {
      onSuccess: async () => {
        setDialog(undefined);
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getGetMeterQueryKey(meter.id) }),
          queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
        ]);
      },
      onError: (error: unknown) => {
        setDialog(undefined);
        setActionError(problemMessage(t, error));
      },
    },
  });

  function open(kind: 'correct' | 'delete', reading: MeterReading) {
    setActionError(undefined);
    setDialog({ kind, reading });
  }

  const columns: Column<MeterReading>[] = [
    { key: 'date', header: h.columns.date, cell: (r) => formatDay(r.takenOn, locale) },
    {
      key: 'value',
      header: h.columns.value,
      numeric: true,
      cell: (r) => `${formatMeterValue(r.value, locale)} ${unit}`,
    },
    {
      key: 'status',
      header: h.columns.status,
      cell: (r) => (
        <div className="flex flex-col items-start gap-1">
          <Badge tone={STATUS_TONE[r.status]}>{h.status[r.status]}</Badge>
          {r.rejectReason && (
            <span className="text-sm text-ink-muted">
              {formatUser(h.rejectReason, { reason: r.rejectReason })}
            </span>
          )}
        </div>
      ),
    },
    { key: 'source', header: h.columns.source, cell: (r) => h.source[r.source] },
    {
      key: 'interval',
      header: h.columns.interval,
      cell: (r) =>
        r.interval
          ? format(h.interval, {
              consumption: formatMeterValue(r.interval.consumption, locale),
              unit,
              days: r.interval.days,
              from: formatDay(r.interval.from, locale),
              to: formatDay(r.interval.to, locale),
            })
          : t.meters.fields.none,
    },
    {
      key: 'actions',
      header: h.columns.actions,
      cell: (r) =>
        r.changeable ? (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => open('correct', r)}>
              {h.correct}
            </Button>
            <Button variant="danger" onClick={() => open('delete', r)}>
              {h.delete}
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <>
      {actionError && <Notice tone="danger">{actionError}</Notice>}
      <QueryBoundary query={query} isEmpty={(data) => data.readings.length === 0} emptyMessage={h.empty}>
        {(data) => (
          <DataTable
            columns={columns}
            rows={data.readings}
            rowKey={(r) => r.id}
            caption={t.meters.historyTitle}
            emptyMessage={h.empty}
          />
        )}
      </QueryBoundary>
      {dialog?.kind === 'correct' && (
        <CorrectReadingDialog
          meter={meter}
          reading={dialog.reading}
          onSaved={() => setDialog(undefined)}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          title={h.deleteTitle}
          message={h.deleteText}
          confirmLabel={h.delete}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: dialog.reading.id })}
          onCancel={() => setDialog(undefined)}
        />
      )}
    </>
  );
}
