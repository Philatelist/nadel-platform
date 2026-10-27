import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';

interface Options {
  /** Also drop the `page` parameter when the value changes (a new search starts at the first page). */
  resetPage?: boolean;
}

/**
 * Writes `value` (trimmed) to the URL search parameter `key` once it has stayed unchanged for `delayMs`, replacing the
 * history entry. The write is dropped when the page has unmounted or the browser has moved to another path in the
 * meantime, so a late timer can never drag the user back from the page they just opened.
 */
export function useDebouncedSearchParam(
  key: string,
  value: string,
  delayMs: number,
  { resetPage = false }: Options = {},
): void {
  const [, setParams] = useSearchParams();
  const setParamsRef = useRef(setParams);
  useEffect(() => {
    setParamsRef.current = setParams;
  }, [setParams]);
  const trimmed = value.trim();

  useEffect(() => {
    const startedAt = window.location.pathname;
    const id = window.setTimeout(() => {
      if (window.location.pathname !== startedAt) return;
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
