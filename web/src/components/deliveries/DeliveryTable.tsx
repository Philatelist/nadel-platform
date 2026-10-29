import type { DeliveryRow, DeliveryStatus } from '@nadel/api-client';
import { formatDateTime } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { Badge, Button, DataTable, type BadgeTone, type Column } from '../ui';

const TONES: Readonly<Record<DeliveryStatus, BadgeTone>> = {
  QUEUED: 'info',
  SENT: 'success',
  FAILED: 'warning',
  TO_PRINT: 'info',
  PRINTED: 'neutral',
};

/** Only a sent or failed e-mail or Telegram delivery can be sent once more (the server decides again). */
export function canRetry(row: Pick<DeliveryRow, 'status' | 'channel'>): boolean {
  return (row.status === 'SENT' || row.status === 'FAILED') && row.channel !== 'PAPER';
}

/** A page of deliveries; `onRetry` set shows «Send again» on the rows that can be retried. */
export function DeliveryTable({
  rows,
  onRetry,
  retryingId,
}: {
  rows: readonly DeliveryRow[];
  onRetry?: (row: DeliveryRow) => void;
  retryingId?: string;
}) {
  const t = useT();
  const { locale } = useLocale();
  const d = t.deliveries;
  const columns: Column<DeliveryRow>[] = [
    {
      key: 'plot',
      header: d.columns.plot,
      cell: (r) => (r.plotNumber === undefined ? '—' : <UserContent>{r.plotNumber}</UserContent>),
    },
    {
      key: 'member',
      header: d.columns.member,
      cell: (r) => (r.memberName === undefined ? '—' : <UserContent>{r.memberName}</UserContent>),
    },
    { key: 'channel', header: d.columns.channel, cell: (r) => t.channel[r.channel] },
    {
      key: 'status',
      header: d.columns.status,
      cell: (r) => <Badge tone={TONES[r.status]}>{d.status[r.status]}</Badge>,
    },
    {
      key: 'reason',
      header: d.columns.reason,
      cell: (r) => (
        <div className="flex flex-col gap-0.5">
          {r.failureCode !== undefined && <span>{d.reason[r.failureCode]}</span>}
          {r.note !== undefined && <span className="text-sm text-ink-muted">{d.note[r.note]}</span>}
        </div>
      ),
    },
    { key: 'attempts', header: d.columns.attempts, numeric: true, cell: (r) => r.attempts },
    {
      key: 'sentAt',
      header: d.columns.sentAt,
      cell: (r) => (r.sentAt === undefined ? '—' : formatDateTime(r.sentAt, locale)),
    },
  ];
  if (onRetry !== undefined) {
    columns.push({
      key: 'actions',
      header: d.columns.actions,
      cell: (r) =>
        canRetry(r) ? (
          <Button variant="secondary" disabled={retryingId === r.id} onClick={() => onRetry(r)}>
            {d.retry}
          </Button>
        ) : null,
    });
  }
  return (
    <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} caption={d.title} emptyMessage={d.empty} />
  );
}
