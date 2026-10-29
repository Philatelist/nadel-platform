import { DeliveryKind, DeliveryMethod, DeliveryStatus } from '@nadel/api-client';
import { useT } from '../../i18n';
import { MonthField, Select } from '../ui';

export const DELIVERY_KINDS = Object.values(DeliveryKind);
export const DELIVERY_STATUSES = Object.values(DeliveryStatus);
export const DELIVERY_CHANNELS = Object.values(DeliveryMethod);

export interface DeliveryFilterValues {
  month: string;
  kind?: DeliveryKind;
  status?: DeliveryStatus;
  channel?: DeliveryMethod;
}

/** The month picker and the kind, status and channel selects; `onChange` gets the changed field only. */
export function DeliveryFilters({
  values,
  onChange,
}: {
  values: DeliveryFilterValues;
  onChange: (patch: Partial<Record<keyof DeliveryFilterValues, string>>) => void;
}) {
  const t = useT();
  const f = t.deliveries.filters;
  return (
    <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
      <MonthField label={f.month} value={values.month} onChange={(month) => onChange({ month })} />
      <Select label={f.kind} value={values.kind ?? ''} onChange={(e) => onChange({ kind: e.target.value })}>
        <option value="">{f.all}</option>
        {DELIVERY_KINDS.map((k) => (
          <option key={k} value={k}>
            {t.deliveries.kind[k]}
          </option>
        ))}
      </Select>
      <Select
        label={f.status}
        value={values.status ?? ''}
        onChange={(e) => onChange({ status: e.target.value })}
      >
        <option value="">{f.all}</option>
        {DELIVERY_STATUSES.map((s) => (
          <option key={s} value={s}>
            {t.deliveries.status[s]}
          </option>
        ))}
      </Select>
      <Select
        label={f.channel}
        value={values.channel ?? ''}
        onChange={(e) => onChange({ channel: e.target.value })}
      >
        <option value="">{f.all}</option>
        {DELIVERY_CHANNELS.map((c) => (
          <option key={c} value={c}>
            {t.channel[c]}
          </option>
        ))}
      </Select>
    </div>
  );
}
