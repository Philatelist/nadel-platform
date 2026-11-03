import type { DeliveryMethod, DeliveryStatus, DeliverySummaryCount } from '@nadel/api-client';
import { useT } from '../../i18n';
import { DataTable, type Column } from '../ui';

const CHANNELS: readonly DeliveryMethod[] = ['TELEGRAM', 'EMAIL', 'PAPER'];

function countOf(
  summary: readonly DeliverySummaryCount[],
  channel: DeliveryMethod,
  status: DeliveryStatus,
): number {
  return summary
    .filter((s) => s.channel === channel && s.status === status)
    .reduce((sum, s) => sum + s.count, 0);
}

/** Deliveries per channel and status (the server's counts, only shown): sent, failed, to print / printed. */
export function DeliveryCounts({ summary }: { summary: readonly DeliverySummaryCount[] }) {
  const t = useT();
  const c = t.deliveries.counts;
  const columns: Column<DeliveryMethod>[] = [
    { key: 'channel', header: c.channel, cell: (ch) => t.channel[ch] },
    { key: 'queued', header: c.queued, numeric: true, cell: (ch) => countOf(summary, ch, 'QUEUED') },
    { key: 'sent', header: c.sent, numeric: true, cell: (ch) => countOf(summary, ch, 'SENT') },
    { key: 'failed', header: c.failed, numeric: true, cell: (ch) => countOf(summary, ch, 'FAILED') },
    {
      key: 'toPrint',
      header: c.toPrint,
      numeric: true,
      cell: (ch) =>
        ch === 'PAPER' ? `${countOf(summary, ch, 'TO_PRINT')} / ${countOf(summary, ch, 'PRINTED')}` : '—',
    },
  ];
  return (
    <DataTable
      columns={columns}
      rows={CHANNELS}
      rowKey={(ch) => ch}
      caption={c.title}
      emptyMessage={c.title}
    />
  );
}
