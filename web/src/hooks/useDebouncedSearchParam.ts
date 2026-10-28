import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';

interface Options {
  /** Also drop the `page` parameter when the value changes (a new search starts at the first page). */
  resetPage?: boolean;
}

/**
 * Writes `value` (trimmed) to the URL search parameter `key` once it has stayed unchanged for `delayMs`, replacing the
 * history entry. The write is dropped when the page has unmounted or the browser has moved to another path in the
 * meantime, so a late timer can never drag the user back from the page they just opened. It does not navigate when the
 * URL already holds the value, so it never interrupts a navigation that is still loading.
 */
export function useDebouncedSearchParam(
  key: string,
  value: string,
  delayMs: number,
  { resetPage = false }: Options = {},
): void {
  const [params, setParams] = useSearchParams();
  const setParamsRef = useRef(setParams);
  const currentRef = useRef(params.get(key) ?? '');
  useEffect(() => {
    setParamsRef.current = setParams;
    currentRef.current = params.get(key) ?? '';
  }, [setParams, params, key]);
  const trimmed = value.trim();

  useEffect(() => {
    const startedAt = window.location.pathname;
    const id = window.setTimeout(() => {
      if (window.location.pathname !== startedAt) return;
      // Nothing to change: do not navigate at all. With lazy routes a click on a link is still pending (the URL
      // has not moved yet), and even a no-op navigation here would cancel it.
      if (currentRef.current === trimmed) return;
      setParamsRef.current(
        (prev) => {
          if ((prev.get(key) ?? '') === trimmed) return prev;
          const next = new URLSearchParams(prev);
          if (trimmed) next.set(key, trimmed);
          else next.delete(key);
          if (resetPage) next.delete('page');
          return next;
        },
        { replace: true },
      );
    }, delayMs);
    return () => window.clearTimeout(id);
  }, [key, trimmed, delayMs, resetPage]);
}
