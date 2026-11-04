import type { Messages } from '@nadel/shared';
import { ApiError } from '@nadel/api-client';
import { problemCode, problemMessage } from './problem';

/** The codes of the field errors of a problem body (`errors[{field, code}]`). */
function fieldCodes(error: unknown): string[] {
  if (!(error instanceof ApiError)) return [];
  const body: unknown = error.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return [];
  }
  return body.errors.flatMap((item: unknown) =>
    typeof item === 'object' && item !== null && 'code' in item && typeof item.code === 'string'
      ? [item.code]
      : [],
  );
}

/** User-facing text for a failed report call; server text is never shown. */
export function reportErrorMessage(t: Messages, error: unknown): string {
  const known: Readonly<Record<string, string>> = t.reports.errors;
  const code = problemCode(error);
  const own = code !== undefined ? known[code] : undefined;
  if (own !== undefined) return own;
  const fromField = fieldCodes(error)
    .map((fieldCode) => known[fieldCode])
    .find((text) => text !== undefined);
  return fromField ?? problemMessage(t, error);
}
