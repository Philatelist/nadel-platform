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
