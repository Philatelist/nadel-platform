import type { Messages } from '@nadel/shared';
import { problemCode, problemMessage } from './problem';

/** User-facing text for a failed report call; server text is never shown. */
export function reportErrorMessage(t: Messages, error: unknown): string {
  const code = problemCode(error);
  const known: Readonly<Record<string, string>> = t.reports.errors;
  return (code !== undefined ? known[code] : undefined) ?? problemMessage(t, error);
}
