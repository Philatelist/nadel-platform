import { addFractions, format, formatFraction, isOne, type Fraction } from '@nadel/shared';
import { useT } from '../../i18n';

/** Live total of the typed shares; shown only while there is at least one owner row. */
export function ShareTotal({ shares }: { shares: readonly Fraction[] }) {
  const t = useT();
  const total = addFractions(shares);
  const ok = isOne(total);
  return (
    <p
      role="status"
      aria-live="polite"
      className={`text-sm font-medium ${ok ? 'text-success-ink' : 'text-danger'}`}
    >
      {ok ? t.ownership.totalOk : format(t.ownership.total, { total: formatFraction(total) })}
    </p>
  );
}
