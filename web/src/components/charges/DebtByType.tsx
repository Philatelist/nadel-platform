import type { PlotAccount } from '@nadel/api-client';
import { useT } from '../../i18n';
import { ChargeTypeName } from '../ChargeTypeName';
import { Button, Money } from '../ui';

/** The debt per charge type and the total, exactly as the server states them; nothing is summed here. */
export function DebtByType({
  account,
  onAllocatePrepayment,
}: {
  account: Pick<PlotAccount, 'debts' | 'total' | 'prepayment'>;
  /** Given only when the caller may allocate and the cooperative does not allocate automatically. */
  onAllocatePrepayment?: () => void;
}) {
  const t = useT();
  const c = t.charges;
  // The server reports "0.00" for no prepayment; any other digit means there is some.
  const hasPrepayment = /[1-9]/.test(account.prepayment);
  const prepayment = hasPrepayment && (
    <div className="flex flex-wrap items-baseline justify-between gap-3 py-1 font-medium">
      <span>{c.prepayment}</span>
      <span className="flex flex-wrap items-center gap-3">
        <Money amount={account.prepayment} />
        {onAllocatePrepayment && (
          <Button variant="secondary" onClick={onAllocatePrepayment}>
            {c.allocatePrepayment}
          </Button>
        )}
      </span>
    </div>
  );
  if (account.debts.length === 0) {
    return (
      <>
        <p className="text-ink-muted">{c.noDebt}</p>
        {prepayment}
      </>
    );
  }
  return (
    <section aria-label={c.debtTitle} className="flex flex-col gap-2">
      <h3 className="text-sm font-semibold text-ink-muted">{c.debtTitle}</h3>
      <ul className="divide-y divide-line-subtle">
        {account.debts.map((d) => (
          <li key={d.chargeTypeId} className="flex items-baseline justify-between gap-3 py-2">
            <span>
              <ChargeTypeName
                type={{ code: d.chargeTypeCode, name: d.name, nameCustomized: d.nameCustomized }}
              />
            </span>
            <Money amount={d.debt} tone="auto" />
          </li>
        ))}
      </ul>
      <div className="flex items-baseline justify-between gap-3 border-t border-line pt-2 font-semibold">
        <span>{c.debtTotal}</span>
        <Money amount={account.total} tone="auto" />
      </div>
      {prepayment}
    </section>
  );
}
