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
const PART_FIELD = /^split\.parts\[(\d+)\]\.(plotId|amount)$/;
const PART_LINE_FIELD = /^split\.parts\[(\d+)\]\.lines\[(\d+)\]\.(chargeId|amount)$/;
const SPLIT_FIELD = /^split(\.|$)/;

interface FieldItem {
  field: string;
  code: string;
}

function fieldItems(error: unknown): FieldItem[] {
  if (!(error instanceof ApiError)) return [];
  const body = error.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return [];
  }
  const items: FieldItem[] = [];
  for (const item of body.errors as unknown[]) {
    if (typeof item !== 'object' || item === null) continue;
    const { field, code } = item as { field?: unknown; code?: unknown };
    if (typeof field === 'string' && typeof code === 'string') items.push({ field, code });
  }
  return items;
}

function lineText(t: Messages, field: string, code: string): string {
  const known: Readonly<Record<string, string>> = { ...t.problem, ...t.validation };
  return field.endsWith('chargeId') && code === 'NOT_FOUND'
    ? t.payments.problem.lineNotFound
    : field.endsWith('chargeId') && code === 'DUPLICATE'
      ? t.payments.problem.lineDuplicate
      : (known[code] ?? t.problem.UNKNOWN);
}

/** The chargeId and amount problems of the request's lines, by the index of the line in the request. */
export function lineProblems(t: Messages, error: unknown): Readonly<Record<number, string>> {
  const result: Record<number, string> = {};
  for (const { field, code } of fieldItems(error)) {
    const match = LINE_FIELD.exec(field);
    if (match !== null) result[Number(match[1])] = lineText(t, field, code);
  }
  return result;
}

/** The problems of the parts of a split request, by the index of the part in the request. */
export interface PartProblems {
  amount: Readonly<Record<number, string>>;
  plot: Readonly<Record<number, string>>;
  /** By part index, then by the index of the line in the part. */
  lines: Readonly<Record<number, Readonly<Record<number, string>>>>;
  /** The payer's own problem (`split.payerMemberId`). */
  payer?: string;
  /** `split` as a whole is invalid. */
  split?: string;
}

export function partProblems(t: Messages, error: unknown): PartProblems {
  const amount: Record<number, string> = {};
  const plot: Record<number, string> = {};
  const lines: Record<number, Record<number, string>> = {};
  let payer: string | undefined;
  let split: string | undefined;
  for (const { field, code } of fieldItems(error)) {
    const part = PART_FIELD.exec(field);
    const partLine = PART_LINE_FIELD.exec(field);
    if (part !== null) {
      if (part[2] === 'amount') amount[Number(part[1])] = t.payments.problem.partAmountInvalid;
      else {
        plot[Number(part[1])] =
          code === 'DUPLICATE' ? t.payments.problem.partPlotDuplicate : t.payments.problem.partPlotNotFound;
      }
    } else if (partLine !== null) {
      const index = Number(partLine[1]);
      (lines[index] ??= {})[Number(partLine[2])] = lineText(t, field, code);
    } else if (field === 'split.payerMemberId') {
      payer = t.payments.problem.splitPayerNotFound;
    } else if (field === 'split') {
      split = t.payments.problem.splitInvalid;
    }
  }
  return { amount, plot, lines, payer, split };
}

/** What to show for a failed preview or save, split by where it goes. */
export interface PaymentErrors {
  /** Form-level text (a problem that is not about one field); undefined when the fields say it all. */
  notice?: string;
  fields: FieldErrors;
  lines: Readonly<Record<number, string>>;
  parts: PartProblems;
}

export function paymentErrors(t: Messages, error: unknown): PaymentErrors {
  const noParts: PartProblems = { amount: {}, plot: {}, lines: {} };
  if (error === undefined || error === null) return { fields: {}, lines: {}, parts: noParts };
  const lines = lineProblems(t, error);
  const parts = partProblems(t, error);
  const all = problemFieldErrors(t, error);
  const fields: Record<string, string> = {};
  for (const [key, text] of Object.entries(all)) {
    if (!LINE_FIELD.test(key) && !SPLIT_FIELD.test(key)) fields[key] = text;
  }
  const code = problemCode(error);
  const hasPartDetail =
    Object.keys(parts.amount).length > 0 ||
    Object.keys(parts.plot).length > 0 ||
    Object.keys(parts.lines).length > 0 ||
    parts.payer !== undefined ||
    parts.split !== undefined;
  const hasDetail = Object.keys(fields).length > 0 || Object.keys(lines).length > 0 || hasPartDetail;
  const notice =
    code === 'VALIDATION_FAILED' && hasDetail
      ? (fields.lines ?? parts.split)
      : code === 'ALLOCATION_EXCEEDS_CHARGE' && (Object.keys(lines).length > 0 || hasPartDetail)
        ? undefined
        : paymentProblemMessage(t, error);
  return { notice, fields, lines, parts };
}
