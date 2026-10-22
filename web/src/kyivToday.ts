/** Today's calendar date in Europe/Kyiv as "YYYY-MM-DD" (the cooperative's day, as the server counts it). */
export function kyivToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Kyiv',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

/** The calendar date `days` before today in Europe/Kyiv, as "YYYY-MM-DD" (date arithmetic on the day, never on money). */
export function kyivDaysAgo(days: number, now: Date = new Date()): string {
  const [year = 0, month = 1, day = 1] = kyivToday(now).split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day - days)).toISOString().slice(0, 10);
}
