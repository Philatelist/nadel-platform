import { useListPlotOwnerships } from '@nadel/api-client';
import { useT } from '../../i18n';
import { QueryBoundary } from '../QueryBoundary';
import { Panel } from '../ui';
import { OwnershipLists } from './OwnershipLists';

/** Owners of a plot (not common land, which never has owners). Change owner / Correct arrive in later slices. */
export function OwnersPanel({ plotId }: { plotId: string }) {
  const t = useT();
  const ownerships = useListPlotOwnerships(plotId);
  return (
    <Panel title={t.plot.ownersTitle}>
      {/* Slice 3: the "Change owner" action goes here for people who may edit ownership. */}
      <QueryBoundary query={ownerships}>{(data) => <OwnershipLists ownerships={data} />}</QueryBoundary>
    </Panel>
  );
}
