import { useGetCommonConsumerRun } from '@nadel/api-client';
import { QueryBoundary } from '../QueryBoundary';
import { CommonConsumersTable, CommonConsumerWarnings } from './CommonConsumers';

/** The consumers, warnings and per-plot charges of one booked common consumers run. */
export function CommonConsumerRunDetailsView({ id }: { id: string }) {
  const query = useGetCommonConsumerRun(id);
  return (
    <QueryBoundary query={query}>
      {(run) => (
        <div className="flex flex-col gap-4">
          <CommonConsumerWarnings consumers={run.consumers} />
          <CommonConsumersTable consumers={run.consumers} />
        </div>
      )}
    </QueryBoundary>
  );
}
