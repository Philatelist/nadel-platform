import type { QueryClient } from '@tanstack/react-query';

/** Two resume signals (visibility and focus fire together) within this window count as one. */
export const RESUME_THROTTLE_MS = 2_000;

/**
 * iOS resumes an installed app exactly as it was and nothing reloads, so the screen would show old data. When the page
 * becomes visible again (visibilitychange, focus, pageshow from the back/forward cache) or the service worker says the
 * app was opened from a push (`NADEL_REFRESH`), the queries of the current screen are refetched. Call once at start-up.
 */
export function refreshOnResume(queryClient: QueryClient, now: () => number = Date.now): () => void {
  let last = Number.NEGATIVE_INFINITY;
  const refresh = (force = false): void => {
    const at = now();
    if (!force && at - last < RESUME_THROTTLE_MS) return;
    last = at;
    void queryClient.invalidateQueries({ refetchType: 'active' });
  };
  const onVisibility = (): void => {
    if (document.visibilityState === 'visible') refresh();
  };
  const onFocus = (): void => refresh();
  const onPageShow = (event: PageTransitionEvent): void => {
    if (event.persisted) refresh();
  };
  const onMessage = (event: MessageEvent): void => {
    const data: unknown = event.data;
    if (typeof data === 'object' && data !== null && (data as { type?: unknown }).type === 'NADEL_REFRESH')
      refresh(true);
  };
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('focus', onFocus);
  window.addEventListener('pageshow', onPageShow);
  navigator.serviceWorker?.addEventListener('message', onMessage);
  return () => {
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('focus', onFocus);
    window.removeEventListener('pageshow', onPageShow);
    navigator.serviceWorker?.removeEventListener('message', onMessage);
  };
}
