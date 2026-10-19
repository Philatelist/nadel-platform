/** Today's calendar date in Europe/Kyiv as "YYYY-MM-DD" (the cooperative's day, as the server counts it). */
export function kyivToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Kyiv',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}
