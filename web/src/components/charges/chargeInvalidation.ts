import {
  getGetPlotAccountQueryKey,
  getGetPlotMapQueryKey,
  getGetPlotQueryKey,
  getListPlotsQueryKey,
} from '@nadel/api-client';
import type { QueryClient } from '@tanstack/react-query';

/** After a reversal or an opening balance the plot's account, card, list and map all show new numbers. */
export async function invalidateAfterCharge(queryClient: QueryClient, plotId: string): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: getGetPlotAccountQueryKey(plotId) }),
    queryClient.invalidateQueries({ queryKey: getGetPlotQueryKey(plotId) }),
    queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() }),
    queryClient.invalidateQueries({ queryKey: getGetPlotMapQueryKey() }),
  ]);
}
