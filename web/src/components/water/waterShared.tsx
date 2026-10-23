import {
  getGetPlotWaterConnectionsQueryKey,
  getListNotChargedQueryKey,
  getListWaterSourcesQueryKey,
  type WaterSourceRef,
} from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import type { QueryClient } from '@tanstack/react-query';
import { useT } from '../../i18n';
import { problemCode, problemMessage } from '../../problem';
import { UserContent } from '../UserContent';

/** The cooperative supply has no name of its own, a meter is shown by the name the cooperative gave it. */
export function WaterSourceName({ source }: { source: Pick<WaterSourceRef, 'kind' | 'name'> }) {
  const t = useT();
  if (source.kind === 'COOPERATIVE_SUPPLY' || source.name === undefined)
    return <>{t.water.cooperativeSupply}</>;
  return <UserContent>{source.name}</UserContent>;
}

/** Text for a failed water change; an archived plot gets its own wording (the generic one speaks of deleting). */
export function waterProblemMessage(t: Messages, error: unknown): string {
  return problemCode(error) === 'PLOT_ARCHIVED' ? t.water.plotArchived : problemMessage(t, error);
}

/** After a water change the plot cards, the sources and the "not charged" queue all show something new. */
export async function invalidateAfterWaterChange(queryClient: QueryClient, plotId?: string): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: getListWaterSourcesQueryKey() }),
    queryClient.invalidateQueries({ queryKey: getListNotChargedQueryKey() }),
    plotId === undefined
      ? queryClient.invalidateQueries({
          predicate: (query) => {
            const head = query.queryKey[0];
            return typeof head === 'string' && head.startsWith('/api/v1/plots');
          },
        })
      : queryClient.invalidateQueries({ queryKey: getGetPlotWaterConnectionsQueryKey(plotId) }),
  ]);
}
