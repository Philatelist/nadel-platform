import { ApiError } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';

/** The machine-readable `code` of a Problem Details body, if the error carries one. */
export function problemCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const body = error.body;
  if (typeof body === 'object' && body !== null && 'code' in body && typeof body.code === 'string') {
    return body.code;
  }
  return undefined;
}

/** User-facing text for an API error by its code. Server-supplied text is never shown. */
export function problemMessage(t: Messages, error: unknown): string {
  const code = problemCode(error);
  const known: Readonly<Record<string, string>> = t.problem;
  return (code !== undefined ? known[code] : undefined) ?? t.problem.UNKNOWN;
}

/** Field errors keyed by form field: `lastName`, `email`, `phones[1]`, ... */
export type FieldErrors = Readonly<Record<string, string>>;

/**
 * Wire field path to form key: `phones[1].x` and `phones[1]` both become `phones[1]`; `owners[2].share` keeps
 * its row and property (`owners[2].share`, `owners[2].memberId`); `coOwnerShares[0].share` likewise; `owners`,
 * `share`, `reason` and the dates stay as they are.
 */
export function fieldKey(field: string): string {
  const phone = /^phones\[(\d+)\]/.exec(field);
  if (phone) return `phones[${phone[1]}]`;
  const owner = /^owners\[(\d+)\]\.(memberId|share)$/.exec(field);
  if (owner) return `owners[${owner[1]}].${owner[2]}`;
  const coOwner = /^coOwnerShares\[(\d+)\]\.(ownershipId|share)$/.exec(field);
  return coOwner ? `coOwnerShares[${coOwner[1]}].${coOwner[2]}` : field;
}

function isFieldError(value: unknown): value is { field: string; code: string } {
  return (
    typeof value === 'object' &&
    value !== null &&
    'field' in value &&
    typeof value.field === 'string' &&
    'code' in value &&
    typeof value.code === 'string'
  );
}

/**
 * Maps the `errors[]` of a Problem body to messages by field. The text depends on the field error `code`
 * (and on the field, for codes like `CHANNEL_CONTACT_MISSING`); server text is never shown.
 */
export function problemFieldErrors(t: Messages, error: unknown): FieldErrors {
  if (!(error instanceof ApiError)) return {};
  const body = error.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return {};
  }
  const validation: Readonly<Record<string, string>> = t.validation;
  const problems: Readonly<Record<string, string>> = t.problem;
  const result: Record<string, string> = {};
  for (const item of body.errors as unknown[]) {
    if (!isFieldError(item)) continue;
    const key = fieldKey(item.field);
    result[key] =
      validation[`${item.code}_${key}`] ?? validation[item.code] ?? problems[item.code] ?? t.problem.UNKNOWN;
  }
  return result;
}
