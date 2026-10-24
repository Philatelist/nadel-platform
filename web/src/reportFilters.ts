import {
  ReportFilterParam,
  type GetReportParams,
  type ReportFilter,
  type ReportFilterValue,
} from '@nadel/api-client';

/** Filter values by query parameter; every value is text, a list for the parameters that take several. */
export type FilterValues = Partial<Record<ReportFilterParam, string[]>>;

/** How a query parameter is entered. The one table that maps the contract's parameters to controls. */
export type ParamControl =
  | { kind: 'month' | 'date' | 'text' | 'integer' | 'amount' }
  | { kind: 'select' | 'multi'; options: readonly string[] };

export const PARAM_CONTROLS: Readonly<Record<ReportFilterParam, ParamControl>> = {
  fromMonth: { kind: 'month' },
  toMonth: { kind: 'month' },
  from: { kind: 'date' },
  to: { kind: 'date' },
  asOf: { kind: 'date' },
  year: { kind: 'integer' },
  chargeTypeIds: { kind: 'multi', options: [] },
  street: { kind: 'text' },
  statuses: { kind: 'multi', options: ['RED', 'YELLOW', 'GREEN', 'GREY'] },
  minDebt: { kind: 'amount' },
  olderThanDays: { kind: 'integer' },
  resource: { kind: 'select', options: ['ELECTRICITY', 'WATER'] },
  meterKinds: { kind: 'multi', options: ['PLOT', 'SUBSTATION', 'QUARTER', 'COMMON_CONSUMER'] },
  waterSource: { kind: 'select', options: ['COOPERATIVE_SUPPLY', 'METER'] },
  correctionKinds: { kind: 'multi', options: ['REVERSAL', 'ADJUSTMENT'] },
};

const NUMERIC_PARAMS: ReadonlySet<string> = new Set([
  ReportFilterParam.year,
  ReportFilterParam.olderThanDays,
]);

function isMulti(param: ReportFilterParam): boolean {
  return PARAM_CONTROLS[param].kind === 'multi';
}

/** The query parameters of a report, in the order of its filters. */
export function reportParams(filters: readonly ReportFilter[]): ReportFilterParam[] {
  return filters.flatMap((filter) => filter.params);
}

/** The values the user has chosen, from the URL; parameters of other reports are ignored. */
export function valuesFromSearch(search: URLSearchParams, filters: readonly ReportFilter[]): FilterValues {
  const values: FilterValues = {};
  for (const param of reportParams(filters)) {
    const all = search.getAll(param).filter((value) => value !== '');
    if (all.length > 0) values[param] = isMulti(param) ? all : all.slice(0, 1);
  }
  return values;
}

/** The defaults the server states for a report's filters. */
export function defaultValues(filters: readonly ReportFilter[]): FilterValues {
  const values: FilterValues = {};
  const defaults: ReportFilterValue[] = filters.flatMap((filter) => filter.defaults);
  for (const { param, value } of defaults) values[param] = [...(values[param] ?? []), value];
  return values;
}

/** What the report runs with: the chosen value, else the default. */
export function effectiveValues(chosen: FilterValues, defaults: FilterValues): FilterValues {
  return { ...defaults, ...chosen };
}

/** The URL search string for the chosen values (empty values are left out). */
export function toSearch(values: FilterValues): URLSearchParams {
  const search = new URLSearchParams();
  for (const [param, list] of Object.entries(values)) {
    for (const value of list ?? []) if (value !== '') search.append(param, value);
  }
  return search;
}

/** The query of the generated hooks for the chosen values; what is left out takes the server default. */
export function toRequestParams(values: FilterValues): GetReportParams {
  const params: Record<string, string | number | string[]> = {};
  for (const [param, list] of Object.entries(values)) {
    if (list === undefined || list.length === 0) continue;
    const first = list[0] ?? '';
    if (isMulti(param as ReportFilterParam)) params[param] = list;
    else params[param] = NUMERIC_PARAMS.has(param) && Number.isFinite(Number(first)) ? Number(first) : first;
  }
  return params as GetReportParams;
}

function lastDayOf(month: string): string | undefined {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (!match) return undefined;
  const day = new Date(Date.UTC(Number(match[1]), Number(match[2]), 0)).getUTCDate();
  return `${month}-${String(day).padStart(2, '0')}`;
}

/** The first and last day of the period the filters name, as ISO days, or undefined when they name none. */
function periodDays(values: FilterValues): { from: string; to: string } | undefined {
  const one = (param: ReportFilterParam): string | undefined => values[param]?.[0];
  const fromMonth = one('fromMonth');
  const toMonth = one('toMonth');
  if (fromMonth !== undefined && toMonth !== undefined) {
    const to = lastDayOf(toMonth);
    return to === undefined ? undefined : { from: `${fromMonth}-01`, to };
  }
  const from = one('from');
  const to = one('to');
  if (from !== undefined && to !== undefined) return { from, to };
  const year = one('year');
  if (year !== undefined) return { from: `${year}-01-01`, to: `${year}-12-31` };
  const asOf = one('asOf');
  return asOf === undefined ? undefined : { from: asOf, to: asOf };
}

/** The download name `<code>_<from>_<to>.xlsx` (the code alone when the filters name no period). */
export function reportFileName(code: string, values: FilterValues): string {
  const period = periodDays(values);
  return period === undefined ? `${code}.xlsx` : `${code}_${period.from}_${period.to}.xlsx`;
}
