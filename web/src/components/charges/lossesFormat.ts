import { formatMeterValue } from '@nadel/shared';

/**
 * A server kWh string for display. Losses can be negative ("-50.000"): the sign is kept as a minus and the rest
 * is formatted by the shared formatter. Text only, no arithmetic; an unexpected text is shown as it is.
 */
export function formatKwh(value: string | undefined, locale: string): string {
  if (value === undefined) return '—';
  const negative = value.startsWith('-');
  try {
    const text = formatMeterValue(negative ? value.slice(1) : value, locale);
    return negative ? `−${text}` : text;
  } catch {
    return value;
  }
}
