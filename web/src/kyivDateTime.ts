const KYIV = 'Europe/Kyiv';

function wallParts(date: Date): Record<string, number> {
  const out: Record<string, number> = {};
  for (const part of new Intl.DateTimeFormat('en-GB', {
    timeZone: KYIV,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date)) {
    if (part.type !== 'literal') out[part.type] = Number(part.value);
  }
  return out;
}

/** The current Kyiv wall time as the value of a `datetime-local` input ("YYYY-MM-DDTHH:mm"). */
export function kyivNowLocal(now: Date = new Date()): string {
  const p = wallParts(now);
  const two = (n: number | undefined) => String(n ?? 0).padStart(2, '0');
  return `${String(p.year).padStart(4, '0')}-${two(p.month)}-${two(p.day)}T${two(p.hour)}:${two(p.minute)}`;
}

/** The instant (ISO 8601, UTC) of a Kyiv wall time "YYYY-MM-DDTHH:mm"; undefined while the value is incomplete. */
export function kyivLocalToInstant(local: string): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local);
  if (match === null) return undefined;
  const [year, month, day, hour, minute] = match.slice(1).map(Number) as [
    number,
    number,
    number,
    number,
    number,
  ];
  const wanted = Date.UTC(year, month - 1, day, hour, minute);
  // The wall time of a first guess differs from the wanted one by the zone offset; shift by that difference.
  let guess = wanted;
  for (let i = 0; i < 2; i++) {
    const p = wallParts(new Date(guess));
    const shown = Date.UTC(p.year ?? 0, (p.month ?? 1) - 1, p.day ?? 1, p.hour ?? 0, p.minute ?? 0);
    guess += wanted - shown;
  }
  return new Date(guess).toISOString();
}
