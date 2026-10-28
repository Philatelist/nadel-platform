import type { ChargeTypeTotal } from '@nadel/api-client';
import { useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Money } from '../ui';

/** Server totals per charge type; with `showTotal` the grand total follows. */
export function MonthTotals({
  totals,
  total,
  received,
}: {
  totals: readonly ChargeTypeTotal[];
  total?: string;
  /** The payments booked in the month, from the server. */
  received?: string;
}) {
  const t = useT();
  const receivedRow = received !== undefined && (
    <div className="flex justify-between gap-4 pt-1 font-semibold">
      <dt>{t.billing.received}</dt>
      <dd>
        <Money amount={received} />
      </dd>
    </div>
  );
  if (totals.length === 0) {
    return (
      <div className="flex flex-col gap-1">
        <span className="text-ink-muted">{t.billing.noTotals}</span>
        {received !== undefined && <dl className="flex flex-col gap-1">{receivedRow}</dl>}
      </div>
    );
  }
  return (
    <dl className="flex flex-col gap-1">
      {totals.map((row) => (
        <div key={row.chargeTypeId} className="flex justify-between gap-4">
          <dt>
            <ChargeTypeName
              type={{ code: row.chargeTypeCode, name: row.name, nameCustomized: row.nameCustomized }}
            />
          </dt>
          <dd>
            <Money amount={row.amount} />
          </dd>
        </div>
      ))}
      {total !== undefined && (
        <div className="flex justify-between gap-4 border-t border-line pt-1 font-semibold">
          <dt>{t.billing.total}</dt>
          <dd>
            <Money amount={total} />
          </dd>
        </div>
      )}
      {receivedRow}
    </dl>
  );
}
