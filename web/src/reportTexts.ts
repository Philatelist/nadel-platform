import type { Messages } from '@nadel/shared';

/** The texts of one report in the catalog, found by its code (`reports.<code>.*`). */
export interface ReportTexts {
  title: string;
  description: string;
  sheets: Readonly<Record<string, string>>;
  columns: Readonly<Record<string, string>>;
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null;
}

function strings(value: unknown): Readonly<Record<string, string>> {
  if (!isRecord(value)) return {};
  return Object.fromEntries(Object.entries(value).filter(([, v]) => typeof v === 'string')) as Record<
    string,
    string
  >;
}

/** The texts of a report, or undefined for a code the catalog has no title for (the server returns codes only). */
export function reportTexts(t: Messages, code: string): ReportTexts | undefined {
  const reports: Readonly<Record<string, unknown>> = t.reports;
  const entry = reports[code];
  if (!isRecord(entry) || typeof entry.title !== 'string') return undefined;
  return {
    title: entry.title,
    description: typeof entry.description === 'string' ? entry.description : '',
    sheets: strings(entry.sheets),
    columns: strings(entry.columns),
  };
}
