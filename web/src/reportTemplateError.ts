import { ApiError } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { problemCode, problemMessage } from './problem';

function fieldErrorCode(error: unknown, field: string): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const body = error.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return undefined;
  }
  for (const item of body.errors as unknown[]) {
    if (
      typeof item === 'object' &&
      item !== null &&
      'field' in item &&
      item.field === field &&
      'code' in item &&
      typeof item.code === 'string'
    ) {
      return item.code;
    }
  }
  return undefined;
}

/** The refusal that belongs under the name field: the name is taken by another active template. */
export function reportTemplateNameError(t: Messages, error: unknown): string | undefined {
  return problemCode(error) === 'TEMPLATE_NAME_TAKEN'
    ? t.reportTemplates.errors.TEMPLATE_NAME_TAKEN
    : undefined;
}

/** User-facing text for a failed template call (`generate` for filling a template); HTTP 413 means the size limit. Server text is never shown. */
export function reportTemplateErrorMessage(
  t: Messages,
  error: unknown,
  context: 'upload' | 'generate' = 'upload',
): string {
  const texts = t.reportTemplates;
  if (error instanceof ApiError && error.status === 413) return texts.errors.FILE_TOO_LARGE;
  const code = problemCode(error);
  if (code === 'TEMPLATE_INVALID') {
    const reason = fieldErrorCode(error, 'file');
    const invalid: Readonly<Record<string, string>> = texts.invalid;
    return (reason !== undefined ? invalid[reason] : undefined) ?? texts.errors.TEMPLATE_INVALID;
  }
  if (context === 'generate' && code === 'TEMPLATE_ARCHIVED') return texts.generate.archived;
  // REPORT_TOO_LARGE and REPORT_FILTER_INVALID of a generation have the texts of the reports.
  const known: Readonly<Record<string, string>> = { ...t.reports.errors, ...texts.errors };
  return (code !== undefined ? known[code] : undefined) ?? problemMessage(t, error);
}
