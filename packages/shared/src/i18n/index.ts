import { en, type Messages } from './en';
import { uk } from './uk';

export type Locale = 'uk' | 'en';
export const DEFAULT_LOCALE: Locale = 'uk';
export const LOCALES: readonly Locale[] = ['uk', 'en'];

const catalogs: Record<Locale, Messages> = { uk, en };

export function messages(locale: Locale = DEFAULT_LOCALE): Messages {
  return catalogs[locale];
}

/** Picks the first supported locale from the user's preferences, falling back to Ukrainian. */
export function pickLocale(candidates: readonly string[]): Locale {
  for (const c of candidates) {
    const short = c.toLowerCase().slice(0, 2);
    if ((LOCALES as readonly string[]).includes(short)) return short as Locale;
  }
  return DEFAULT_LOCALE;
}

export type { Messages };
