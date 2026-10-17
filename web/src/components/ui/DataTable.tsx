import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  numeric?: boolean;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  emptyMessage,
}: {
  columns: readonly Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  caption: string;
  emptyMessage: string;
}) {
  const align = (numeric?: boolean): string => (numeric ? 'text-right font-mono' : 'text-left');
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-[15px]">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                className={`px-3 py-2 text-sm font-semibold text-ink-muted ${align(c.numeric)}`}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-3 py-6 text-center text-ink-muted">
                {emptyMessage}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)} className="border-b border-line-subtle last:border-b-0">
                {columns.map((c) => (
                  <td key={c.key} className={`px-3 py-3 ${align(c.numeric)}`}>
                    {c.cell(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
