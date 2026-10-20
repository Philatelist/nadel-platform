import { ApiError } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { problemCode, problemMessage } from './problem';

/** User-facing text for a failed import upload; HTTP 413 always means the size limit. */
export function importErrorMessage(t: Messages, error: unknown): string {
  const code = error instanceof ApiError && error.status === 413 ? 'FILE_TOO_LARGE' : problemCode(error);
  const known: Readonly<Record<string, string>> = t.importError;
  return (code !== undefined ? known[code] : undefined) ?? problemMessage(t, error);
}
