import { onlineManager, type QueryClient } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

/** Browser connectivity as TanStack Query sees it (the manager listens to `online`/`offline`). */
export function useOnline(): boolean {
  return useSyncExternalStore(
    (listener) => onlineManager.subscribe(listener),
    () => onlineManager.isOnline(),
    () => true,
  );
}

/** When the connection comes back, active queries refetch without a restart. Call once at start-up. */
export function refetchOnReconnect(queryClient: QueryClient): () => void {
  return onlineManager.subscribe((online) => {
    if (online) void queryClient.refetchQueries({ type: 'active' });
  });
}
