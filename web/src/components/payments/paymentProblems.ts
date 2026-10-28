import { ApiError } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';

/** Text for a payment problem: the payment wording for a plot that takes no payment and for a stale preview. */
export function paymentProblemMessage(t: Messages, error: unknown): string {
  const code = problemCode(error);
  if (code === 'PLOT_COMMON_LAND') return t.payments.problem.PLOT_COMMON_LAND;
  if (code === 'PLOT_ARCHIVED') return t.payments.problem.PLOT_ARCHIVED;
  if (code === 'PLOT_NOT_FOUND') return t.payments.problem.PLOT_NOT_FOUND;
  if (code === 'PREVIEW_OUTDATED') return t.payments.proposal.outdated;
  return problemMessage(t, error);
}

const LINE_FIELD = /^lines\[(\d+)\]\.(chargeId|amount)$/;

/** The chargeId and amount problems of the request's lines, by the index of the line in the request. */
export function lineProblems(t: Messages, error: unknown): Readonly<Record<number, string>> {
  if (!(error instanceof ApiError)) return {};
  const body = error.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return {};
  }
  const known: Readonly<Record<string, string>> = { ...t.problem, ...t.validation };
  const result: Record<number, string> = {};
  for (const item of body.errors as unknown[]) {
    if (typeof item !== 'object' || item === null) continue;
    const { field, code } = item as { field?: unknown; code?: unknown };
    if (typeof field !== 'string' || typeof code !== 'string') continue;
    const match = LINE_FIELD.exec(field);
    if (match === null) continue;
    const text =
      field.endsWith('chargeId') && code === 'NOT_FOUND'
        ? t.payments.problem.lineNotFound
        : field.endsWith('chargeId') && code === 'DUPLICATE'
          ? t.payments.problem.lineDuplicate
          : (known[code] ?? t.problem.UNKNOWN);
    result[Number(match[1])] = text;
  }
  return result;
}

/** What to show for a failed preview or save, split by where it goes. */
export interface PaymentErrors {
  /** Form-level text (a problem that is not about one field); undefined when the fields say it all. */
  notice?: string;
  fields: FieldErrors;
  lines: Readonly<Record<number, string>>;
}

export function paymentErrors(t: Messages, error: unknown): PaymentErrors {
  if (error === undefined || error === null) return { fields: {}, lines: {} };
  const lines = lineProblems(t, error);
  const all = problemFieldErrors(t, error);
  const fields: Record<string, string> = {};
  for (const [key, text] of Object.entries(all)) {
    if (!LINE_FIELD.test(key)) fields[key] = text;
  }
  const code = problemCode(error);
  const hasDetail = Object.keys(fields).length > 0 || Object.keys(lines).length > 0;
  const notice =
    code === 'VALIDATION_FAILED' && hasDetail
      ? fields.lines
      : code === 'ALLOCATION_EXCEEDS_CHARGE' && Object.keys(lines).length > 0
        ? undefined
        : paymentProblemMessage(t, error);
  return { notice, fields, lines };
}
