import { useEffect } from 'react';
import { useT } from './i18n';
import { useOptionalMe } from './me';

/** Sets the tab title to "<page> — <cooperative>", or "<page> — <product>" outside a cooperative. */
export function useDocumentTitle(page?: string): void {
  const t = useT();
  const brand = useOptionalMe()?.tenant?.name ?? t.app.name;
  useEffect(() => {
    document.title = [page, brand].filter(Boolean).join(' — ');
  }, [page, brand]);
}
