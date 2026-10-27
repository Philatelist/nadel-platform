import { kyivToday } from './kyivToday';

export interface GeneratePeriod {
  from: string;
  to: string;
}

export type PeriodError = 'dateRequired' | 'toBeforeFrom' | 'tooLong';

/** The previous calendar month (Europe/Kyiv) as the default period of a generated file. */
export function previousMonthPeriod(now: Date = new Date()): GeneratePeriod {
  const [year = 0, month = 1] = kyivToday(now).split('-').map(Number);
  const first = new Date(Date.UTC(year, month - 2, 1));
  const last = new Date(Date.UTC(year, month - 1, 0));
  return { from: first.toISOString().slice(0, 10), to: last.toISOString().slice(0, 10) };
}

function sameDayNextYear(day: string): string {
  const [year = 0, month = 1, date = 1] = day.split('-').map(Number);
  return new Date(Date.UTC(year + 1, month - 1, date)).toISOString().slice(0, 10);
}

const dayPattern = /^\d{4}-\d{2}-\d{2}$/;

/** Advisory check, the server decides: both days set, `to` not before `from`, shorter than one year. */
export function validatePeriod({ from, to }: GeneratePeriod): PeriodError | undefined {
  if (!dayPattern.test(from) || !dayPattern.test(to)) return 'dateRequired';
  if (to < from) return 'toBeforeFrom';
  if (to >= sameDayNextYear(from)) return 'tooLong';
  return undefined;
}

/** The download name `<ascii-name>_<from>_<to>.xlsx`; a name with no Latin letters or digits becomes `report`. */
export function generatedFileName(templateName: string, { from, to }: GeneratePeriod): string {
  const slug = templateName
    .normalize('NFKD')
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return `${slug === '' ? 'report' : slug}_${from}_${to}.xlsx`;
}
