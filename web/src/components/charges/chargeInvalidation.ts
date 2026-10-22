import {
  getGetPlotAccountQueryKey,
  getGetPlotMapQueryKey,
  getGetPlotQueryKey,
  getListNotChargedQueryKey,
  getListPlotsQueryKey,
} from '@nadel/api-client';
import type { QueryClient } from '@tanstack/react-query';
import { invalidateAfterClose } from './billingMonth';

/** After a reversal or an opening balance the plot's account, card, list and map all show new numbers. */
export async function invalidateAfterCharge(queryClient: QueryClient, plotId: string): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: getGetPlotAccountQueryKey(plotId) }),
    queryClient.invalidateQueries({ queryKey: getGetPlotQueryKey(plotId) }),
    queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() }),
    queryClient.invalidateQueries({ queryKey: getGetPlotMapQueryKey() }),
  ]);
}

/** After a month run or a retry: the month and its check list, the month list, the queue and every plot account. */
export async function invalidateAfterRun(queryClient: QueryClient, month: string): Promise<void> {
  await Promise.all([
    invalidateAfterClose(queryClient, month),
    queryClient.invalidateQueries({ queryKey: getListNotChargedQueryKey() }),
  ]);
}
