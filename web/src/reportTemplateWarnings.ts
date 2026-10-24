import { ReportTemplateWarningCode, type ReportTemplateWarning } from '@nadel/api-client';

/** What a generated file reports about itself: how many warnings it has and the first ones in detail. */
export interface GenerationWarnings {
  count: number;
  details: ReportTemplateWarning[];
}

const knownCodes: ReadonlySet<string> = new Set(Object.values(ReportTemplateWarningCode));

/** Decodes base64url JSON (UTF-8, padding optional); anything malformed gives undefined. */
function decodeDetail(value: string): unknown {
  try {
    const base64 = value.trim().replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
    const binary = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='));
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    return undefined;
  }
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function toWarning(item: unknown): ReportTemplateWarning | undefined {
  if (typeof item !== 'object' || item === null) return undefined;
  const raw = item as Record<string, unknown>;
  const code = text(raw.code);
  if (code === undefined || !knownCodes.has(code)) return undefined;
  const sheet = text(raw.sheet);
  const cell = text(raw.cell);
  const placeholder = text(raw.placeholder);
  const reason = text(raw.reason) as ReportTemplateWarning['reason'];
  return {
    code: code as ReportTemplateWarning['code'],
    ...(sheet === undefined ? {} : { sheet }),
    ...(cell === undefined ? {} : { cell }),
    ...(placeholder === undefined ? {} : { placeholder }),
    ...(reason === undefined ? {} : { reason }),
  };
}

/**
 * Reads `X-Report-Warnings` (the count) and `X-Report-Warnings-Detail` (base64url JSON of the first warnings).
 * Absent or malformed headers never fail: a bad detail gives an empty list while the count stays.
 */
export function parseGenerationWarnings(headers: Headers | undefined): GenerationWarnings {
  const parsed = Number.parseInt(headers?.get('X-Report-Warnings') ?? '', 10);
  const detail = headers?.get('X-Report-Warnings-Detail');
  const decoded = detail ? decodeDetail(detail) : undefined;
  const details = Array.isArray(decoded) ? decoded.flatMap((item) => toWarning(item) ?? []) : [];
  const count = Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
  return { count: Math.max(count, details.length), details };
}
