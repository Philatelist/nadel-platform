import { useListMyInvoices, type InvoiceRow } from '@nadel/api-client';
import { useT } from '../../i18n';
import { format } from '@nadel/shared';
import { QueryBoundary } from '../QueryBoundary';
import { InvoiceItem } from './PlotInvoices';

/** The rows of the newest month, one per plot, are "current"; the server sorts newest month first. */
export function splitLatest(rows: readonly InvoiceRow[]): { current: InvoiceRow[]; older: InvoiceRow[] } {
  const newest = rows[0]?.month;
  return {
    current: rows.filter((r) => r.month === newest),
    older: rows.filter((r) => r.month !== newest),
  };
}

/** "Invoices" on the account page: the member's own invoices, the newest month highlighted; no certificates. */
export function MyInvoices() {
  const t = useT();
  const i = t.invoices;
  const invoices = useListMyInvoices();
  return (
    <section aria-labelledby="my-invoices" className="flex flex-col gap-3">
      <h2 id="my-invoices" className="font-serif text-lg font-semibold text-ink">
        {i.myTitle}
      </h2>
      <QueryBoundary query={invoices} isEmpty={(rows) => rows.length === 0} emptyMessage={i.myEmpty}>
        {(rows) => {
          const { current, older } = splitLatest(rows);
          return (
            <>
              <ul
                aria-label={i.latest}
                className="divide-y divide-line-subtle rounded-control border border-accent bg-surface px-4"
              >
                {current.map((row) => (
                  <InvoiceItem
                    key={row.id}
                    row={row}
                    plotLabel={format(t.plot.cardTitlePlot, { number: row.plotNumber })}
                  />
                ))}
              </ul>
              {older.length > 0 && (
                <details>
                  <summary className="cursor-pointer text-ink-muted">{i.myOlder}</summary>
                  <ul aria-label={i.myOlder} className="divide-y divide-line-subtle">
                    {older.map((row) => (
                      <InvoiceItem
                        key={row.id}
                        row={row}
                        plotLabel={format(t.plot.cardTitlePlot, { number: row.plotNumber })}
                      />
                    ))}
                  </ul>
                </details>
              )}
            </>
          );
        }}
      </QueryBoundary>
    </section>
  );
}
