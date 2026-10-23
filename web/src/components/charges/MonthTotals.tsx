import type { ChargeTypeTotal } from '@nadel/api-client';
import { useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Money } from '../ui';

/** Server totals per charge type; with `showTotal` the grand total follows. */
export function MonthTotals({ totals, total }: { totals: readonly ChargeTypeTotal[]; total?: string }) {
  const t = useT();
  if (totals.length === 0) return <span className="text-ink-muted">{t.billing.noTotals}</span>;
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
    </dl>
  );
}
