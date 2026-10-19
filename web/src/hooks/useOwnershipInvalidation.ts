import {
  getGetPlotMapQueryKey,
  getGetPlotQueryKey,
  getListPlotOwnershipsQueryKey,
  getListPlotsQueryKey,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';

/** After an ownership change the owners, the plot (status), the plots lists and the map are stale. */
export function useOwnershipInvalidation(plotId: string): () => Promise<unknown> {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getListPlotOwnershipsQueryKey(plotId) }),
      queryClient.invalidateQueries({ queryKey: getGetPlotQueryKey(plotId) }),
      queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetPlotMapQueryKey() }),
    ]);
}
