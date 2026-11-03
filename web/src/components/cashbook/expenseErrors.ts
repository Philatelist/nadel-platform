import { ApiError } from '@nadel/api-client';
import { format, formatDay, formatMoney, type Messages } from '@nadel/shared';
import { problemCode, problemMessage } from '../../problem';

function bodyOf(error: unknown): Record<string, unknown> {
  return error instanceof ApiError && typeof error.body === 'object' && error.body !== null
    ? (error.body as Record<string, unknown>)
    : {};
}

/** User-facing text for a cash book error; `CASH_INSUFFICIENT` carries its balance and day from the problem. */
export function cashbookProblemMessage(t: Messages, locale: string, error: unknown): string {
  const body = bodyOf(error);
  if (
    problemCode(error) === 'CASH_INSUFFICIENT' &&
    typeof body.balance === 'string' &&
    typeof body.date === 'string'
  ) {
    return format(t.problem.CASH_INSUFFICIENT, {
      balance: formatMoney(body.balance, locale),
      date: formatDay(body.date, locale),
    });
  }
  return problemMessage(t, error);
}

/** The text for a refused file (`errors[]` field `files` or `files[i]`), else undefined. */
export function attachmentProblemMessage(t: Messages, error: unknown): string | undefined {
  const errors = bodyOf(error).errors;
  if (!Array.isArray(errors)) return undefined;
  const known: Readonly<Record<string, string>> = t.problem;
  for (const item of errors as unknown[]) {
    if (typeof item !== 'object' || item === null) continue;
    const { field, code } = item as { field?: unknown; code?: unknown };
    if (typeof field === 'string' && /^files(\[\d+\])?$/.test(field) && typeof code === 'string') {
      return known[code] ?? t.problem.UNKNOWN;
    }
  }
  return undefined;
}
