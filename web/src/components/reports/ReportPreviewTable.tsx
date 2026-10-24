import type { ReactNode } from 'react';
import type { ChargeTypeRef, ReportCell, ReportColumn, ReportPreview, ReportSheet } from '@nadel/api-client';
import {
  format,
  formatDay,
  formatMeterValue,
  formatMonth,
  formatMoney,
  formatPercent,
  formatRate,
  type Messages,
} from '@nadel/shared';
import { chargeTypeName } from '../../chargeTypeName';
import { useLocale, useT } from '../../i18n';
import { reportTexts } from '../../reportTexts';
import { ChargeTypeName } from '../ChargeTypeName';
import { UserContent } from '../UserContent';

const NUMERIC_TYPES: ReadonlySet<string> = new Set(['MONEY', 'QUANTITY', 'PERCENT', 'INTEGER', 'DECIMAL']);
const NAMING_TYPES: ReadonlySet<string> = new Set(['TEXT', 'CODE', 'CHARGE_TYPE']);

/** The catalog texts of a CODE column, by report code and column key; a report without an entry shows the billing status texts for `status`. */
function codeLabels(t: Messages, report: string, key: string): Readonly<Record<string, string>> | undefined {
  const lookup: Readonly<Record<string, Readonly<Record<string, string>>>> = {
    'plot-balances/status': t.reports.filters.options.statuses,
    'debtors/status': t.reports.filters.options.statuses,
    'corrections/kind': t.reports.filters.options.correctionKinds,
    'corrections/source': t.reports.corrections.sources,
    'corrections/cause': t.recalculation.causeKind,
    '*/status': t.billing.status,
  };
  return lookup[`${report}/${key}`] ?? lookup[`*/${key}`];
}

interface CellContext {
  report: string;
  t: Messages;
  locale: string;
  chargeTypes: readonly ChargeTypeRef[];
}

/** A value as the column type says. Text only: the server has already counted everything. */
function formatValue(
  column: ReportColumn,
  value: string,
  { report, t, locale, chargeTypes }: CellContext,
): ReactNode {
  try {
    switch (column.type) {
      case 'MONEY':
        return formatMoney(value, locale);
      case 'QUANTITY':
        return formatMeterValue(value, locale);
      case 'PERCENT':
        return formatPercent(value, locale);
      case 'DECIMAL':
        // a rate or an area: exact, its significant decimals but at least two
        return formatRate(value, locale);
      case 'DATE':
        return formatDay(value, locale);
      case 'MONTH':
        return formatMonth(value, locale);
      case 'BOOLEAN':
        return value === 'true' ? t.reports.yes : t.reports.no;
      case 'CHARGE_TYPE': {
        const type = chargeTypes.find((candidate) => candidate.id === value);
        return type ? <ChargeTypeName type={type} /> : value;
      }
      case 'CODE': {
        return codeLabels(t, report, column.key)?.[value] ?? value;
      }
      case 'TEXT':
        return <UserContent>{value}</UserContent>;
      case 'INTEGER':
        return value;
    }
  } catch {
    return value;
  }
}

function Cell({
  column,
  cell,
  label,
  context,
}: {
  column: ReportColumn;
  cell: ReportCell | undefined;
  /** Names a subtotal or totals line when its cell has nothing of its own. */
  label: string | undefined;
  context: CellContext;
}) {
  if (cell?.value !== undefined) return <>{formatValue(column, cell.value, context)}</>;
  if (cell?.reason !== undefined) {
    const reasons: Readonly<Record<string, string>> = context.t.reports.reasons;
    return <span title={reasons[cell.reason] ?? cell.reason}>{context.t.reports.unknown}</span>;
  }
  return <>{label}</>;
}

/** The index of the cell that names a subtotal or totals line: the first empty text, code or charge type cell. */
function labelIndex(columns: readonly ReportColumn[], cells: readonly ReportCell[]): number {
  return columns.findIndex(
    (column, index) =>
      NAMING_TYPES.has(column.type) &&
      cells[index]?.value === undefined &&
      cells[index]?.reason === undefined,
  );
}

function cellClass(column: ReportColumn): string {
  return `px-3 py-2 ${NUMERIC_TYPES.has(column.type) ? 'text-right font-mono tabular-nums' : 'text-left whitespace-pre-line'}`;
}

function columnHeader(
  column: ReportColumn,
  texts: Readonly<Record<string, string>>,
  chargeTypes: readonly ChargeTypeRef[],
  t: Messages,
): ReactNode {
  if (column.chargeTypeId !== undefined) {
    const type = chargeTypes.find((candidate) => candidate.id === column.chargeTypeId);
    if (type) return chargeTypeName(type, t);
  }
  return texts[column.key] ?? column.key;
}

function SheetTable({ code, sheet, preview }: { code: string; sheet: ReportSheet; preview: ReportPreview }) {
  const t = useT();
  const { locale } = useLocale();
  const texts = reportTexts(t, code);
  const context: CellContext = { report: code, t, locale, chargeTypes: preview.chargeTypes };
  const heading = texts?.sheets[sheet.key] ?? sheet.key;
  const totals: Readonly<Record<string, string>> = t.reports.totals;
  const subtotal = t.reports.rows.SUBTOTAL;

  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-[17px] font-semibold text-ink">{heading}</h2>
      {sheet.rows.length === 0 ? (
        <p className="text-ink-muted">{t.reports.noRows}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-[15px]">
            <caption className="sr-only">{heading}</caption>
            <thead>
              <tr className="border-b border-line">
                {sheet.columns.map((column, index) => (
                  <th
                    key={`${column.key}-${index}`}
                    scope="col"
                    className={`${cellClass(column)} text-sm font-semibold text-ink-muted`}
                  >
                    {columnHeader(column, texts?.columns ?? {}, preview.chargeTypes, t)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sheet.rows.map((row, rowIndex) => {
                const isSubtotal = row.kind === 'SUBTOTAL';
                const at = isSubtotal ? labelIndex(sheet.columns, row.cells) : -1;
                return (
                  <tr
                    key={rowIndex}
                    className={`border-b border-line-subtle last:border-b-0 ${isSubtotal ? 'bg-accent-soft font-semibold' : ''}`}
                  >
                    {sheet.columns.map((column, index) => (
                      <td
                        key={`${column.key}-${index}`}
                        className={cellClass(column)}
                        style={
                          index === 0 && row.level > 0
                            ? { paddingLeft: `${0.75 + row.level * 1.25}rem` }
                            : undefined
                        }
                      >
                        <Cell
                          column={column}
                          cell={row.cells[index]}
                          label={index === at ? subtotal : undefined}
                          context={context}
                        />
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
            {sheet.totals.length > 0 && (
              <tfoot>
                {sheet.totals.map((total) => {
                  const at = labelIndex(sheet.columns, total.cells);
                  return (
                    <tr key={total.key} className="border-t-2 border-line-strong font-semibold">
                      {sheet.columns.map((column, index) => (
                        <td key={`${column.key}-${index}`} className={cellClass(column)}>
                          <Cell
                            column={column}
                            cell={total.cells[index]}
                            label={index === at ? (totals[total.key] ?? total.key) : undefined}
                            context={context}
                          />
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tfoot>
            )}
          </table>
        </div>
      )}
      {sheet.truncated && (
        <p role="note" className="text-sm text-ink-muted">
          {format(t.reports.rowCount, { shown: sheet.rows.length, total: sheet.rowCount })}
        </p>
      )}
    </section>
  );
}

/** The preview of a report: its header notes, then one table per sheet (columns by key, text from the catalog). */
export function ReportPreviewTable({ preview }: { preview: ReportPreview }) {
  const t = useT();
  const notes: Readonly<Record<string, string>> = t.reports.notes;
  return (
    <div className="flex flex-col gap-5">
      {preview.headerNotes.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm text-ink-muted">
          {preview.headerNotes.map((note, index) => {
            const text = notes[note.code];
            return text === undefined ? null : (
              <li key={`${note.code}-${index}`}>{format(text, note.params)}</li>
            );
          })}
        </ul>
      )}
      {preview.sheets.map((sheet) => (
        <SheetTable key={sheet.key} code={preview.code} sheet={sheet} preview={preview} />
      ))}
    </div>
  );
}
