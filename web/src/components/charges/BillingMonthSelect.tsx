import { useListBillingMonths } from '@nadel/api-client';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { MonthField, Select } from '../ui';
import { billingMonthLabel } from './billingMonth';

/**
 * A choice among the cooperative's billing months (open and closed, newest first, localized, with the month status)
 * and an empty option; the value is "YYYY-MM" or "". A value outside the list stays selectable. Callers without
 * `CHARGES_VIEW` cannot read the month list, so they get the plain month field.
 */
export function BillingMonthSelect({
  label,
  emptyLabel,
  value,
  onChange,
}: {
  label: string;
  emptyLabel: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const t = useT();
  const canList = useCan('CHARGES_VIEW');
  const months = useListBillingMonths({ query: { enabled: canList } });
  if (!canList) return <MonthField label={label} value={value} onChange={onChange} />;
  const listed = months.data ?? [];
  const unlisted = value !== '' && !listed.some((m) => m.month === value);
  return (
    <Select label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{emptyLabel}</option>
      {unlisted && <option value={value}>{billingMonthLabel(value, t)}</option>}
      {listed.map((m) => (
        <option key={m.month} value={m.month}>
          {billingMonthLabel(m.month, t)} · {t.billing.status[m.status]}
        </option>
      ))}
    </Select>
  );
}
