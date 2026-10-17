import type { Locale } from '@nadel/shared';

/** True when another role is already displayed under `name` (trimmed, case-insensitive). */
export function findNameClash<T>(
  name: string,
  others: readonly T[],
  display: (role: T) => string,
  locale: Locale,
): boolean {
  const wanted = name.trim().toLocaleLowerCase(locale);
  if (wanted === '') return false;
  return others.some((role) => display(role).trim().toLocaleLowerCase(locale) === wanted);
}
