import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import type { Locale } from '@nadel/shared';
import type { HelpBundle } from '../../help/types';
import { useLocale } from '../i18n';

/** The static content is a lazy chunk per language; the dynamic imports are written out so Vite splits them. */
export function loadHelpBundle(locale: Locale): Promise<HelpBundle> {
  return locale === 'en'
    ? import('virtual:help/en').then((m) => m.default)
    : import('virtual:help/uk').then((m) => m.default);
}

/** The compiled help of the current language, loaded once per language and cached for the session. */
export function useHelpBundle(): UseQueryResult<HelpBundle> {
  const { locale } = useLocale();
  return useQuery({
    queryKey: ['help', 'bundle', locale],
    queryFn: () => loadHelpBundle(locale),
    staleTime: Infinity,
    // A bundled chunk (precached by the service worker), not a server call: load it offline too, so the help opens
    // without a connection instead of waiting for one.
    networkMode: 'always',
  });
}
