import { useListReadings } from '@nadel/api-client';
import { QueryBoundary } from '../components/QueryBoundary';
import { PendingReadingCard } from '../components/readings/PendingReadingCard';
import { PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

/** Readings that owners submitted and the board has not decided yet. */
export function ApprovalsPage() {
  const t = useT();
  const a = t.readings.approvals;
  useDocumentTitle(a.title);
  const query = useListReadings({ status: 'PENDING' });
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={a.title} />
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={a.empty}>
        {(items) => (
          <ul aria-label={a.listLabel} className="flex flex-col gap-3">
            {items.map((item) => (
              <PendingReadingCard key={item.reading.id} item={item} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </div>
  );
}
