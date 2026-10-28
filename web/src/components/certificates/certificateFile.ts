import { ApiError } from '@nadel/api-client';
import type { RequisiteField } from '@nadel/api-client';
import { problemCode } from '../../problem';

/** `certificate_D-2026-1.pdf` for the number `Д-2026-1` (the Cyrillic letter is transliterated for the file name). */
export function certificateFileName(number: string): string {
  return `certificate_${number.replace('Д', 'D')}.pdf`;
}

/** Today's date (`YYYY-MM-DD`) in the cooperative's time zone, Europe/Kyiv. */
export function kyivToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Kyiv',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

/** The requisites a `REQUISITES_MISSING` problem names; empty for any other error. */
export function missingRequisitesOf(error: unknown): RequisiteField[] {
  if (problemCode(error) !== 'REQUISITES_MISSING' || !(error instanceof ApiError)) return [];
  const body = error.body as { missing?: unknown };
  if (!Array.isArray(body.missing)) return [];
  return body.missing.filter((m): m is RequisiteField => m === 'IBAN' || m === 'EDRPOU');
}
