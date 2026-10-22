/** Fills `{name}` placeholders in a message template. Unknown placeholders are left as written. */
export function format(template: string, vars: Readonly<Record<string, string | number>>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => {
    const value = vars[key];
    return value === undefined ? match : String(value);
  });
}

const MONTHS_EN = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
] as const;
const DAY_RE = /^(\d{4})-(\d{2})-(\d{2})/;
const TIME_ZONE = 'Europe/Kyiv';

function isEnglish(locale: string): boolean {
  return locale.toLowerCase().startsWith('en');
}

/**
 * Formats a date-only value ("2026-10-21") from its text, so no time zone can shift the day:
 * uk "21.10.2026", en "21 Oct 2026".
 */
export function formatDay(value: string, locale: string): string {
  const match = DAY_RE.exec(value);
  if (!match) throw new Error(`Not an ISO day: ${value}`);
  const [, year = '', month = '01', day = '01'] = match;
  if (isEnglish(locale)) return `${Number(day)} ${MONTHS_EN[Number(month) - 1] ?? month} ${year}`;
  return `${day}.${month}.${year}`;
}

/** Formats an instant in Europe/Kyiv time: uk "21.10.2026, 14:05", en "21 Oct 2026, 14:05". */
export function formatDateTime(iso: string, locale: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(iso));
  const part = (type: string): string => parts.find((p) => p.type === type)?.value ?? '';
  const day = `${part('year')}-${part('month')}-${part('day')}`;
  return `${formatDay(day, locale)}, ${part('hour')}:${part('minute')}`;
}

/** A date-only value without the year, from its text: uk "23.09", en "23 Sep". */
export function formatDayMonth(value: string, locale: string): string {
  const match = DAY_RE.exec(value);
  if (!match) throw new Error(`Not an ISO day: ${value}`);
  const [, , month = '01', day = '01'] = match;
  if (isEnglish(locale)) return `${Number(day)} ${MONTHS_EN[Number(month) - 1] ?? month}`;
  return `${day}.${month}`;
}

/** A billing month ("2026-10") from its text: uk "10.2026", en "Oct 2026". */
export function formatMonth(value: string, locale: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(value);
  if (!match) throw new Error(`Not a month: ${value}`);
  const [, year = '', month = '01'] = match;
  return isEnglish(locale) ? `${MONTHS_EN[Number(month) - 1] ?? month} ${year}` : `${month}.${year}`;
}
