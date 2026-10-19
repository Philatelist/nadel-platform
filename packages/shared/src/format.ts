/** Fills `{name}` placeholders in a message template. Unknown placeholders are left as written. */
export function format(template: string, vars: Readonly<Record<string, string | number>>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => {
    const value = vars[key];
    return value === undefined ? match : String(value);
  });
}

/** Formats a date-only value ("2015-06-01") with a four-digit year (dd.MM.yyyy in uk). UTC keeps the calendar day. */
export function formatDay(value: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(value));
}
