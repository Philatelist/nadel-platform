import type { PlotTariffItem, PlotTariffs } from '@nadel/api-client';
import { format, formatRate } from '@nadel/shared';
import { chargeTypeName } from '../../chargeTypeName';
import { useLocale, useT } from '../../i18n';
import { Money } from '../ui';

function Amount({ item }: { item: PlotTariffItem }) {
  const t = useT();
  const e = t.tariffs.expected;
  switch (item.status) {
    case 'AMOUNT':
      return item.amount === undefined ? null : <Money amount={item.amount} />;
    case 'BY_READINGS':
      return <span className="text-ink-muted">{e.byReadings}</span>;
    case 'EXCLUDED':
      return (
        <span className="text-ink-muted">
          {e.notCharged}
          {item.reason ? ` — ${item.reason}` : ''}
        </span>
      );
  }
}

/** The tariffs of a plot for a month with the server's amounts and total; nothing is computed here. */
export function TariffAmountRows({ data }: { data: PlotTariffs }) {
  const t = useT();
  const { locale } = useLocale();
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col divide-y divide-line">
        {data.items.map((item) => (
          <li
            key={item.tariffId}
            className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2"
          >
            <div className="min-w-0">
              <div className="font-medium">
                {chargeTypeName(
                  {
                    code: item.chargeTypeCode,
                    name: item.chargeTypeName,
                    nameCustomized: item.chargeTypeNameCustomized,
                  },
                  t,
                )}
              </div>
              <div className="text-sm text-ink-muted">{item.tariffName}</div>
              <div className="text-sm text-ink-muted">
                {format(t.tariffs.expected.rate, {
                  rate: formatRate(item.rate, locale),
                  basis: t.tariffs.basis[item.basis],
                  frequency: t.tariffs.frequency[item.frequency],
                })}
              </div>
            </div>
            <div className="text-right">
              <Amount item={item} />
            </div>
          </li>
        ))}
      </ul>
      <div className="flex items-baseline justify-between gap-4 border-t border-line-strong pt-2 font-semibold">
        <span>{t.tariffs.expected.total}</span>
        <Money amount={data.total} />
      </div>
    </div>
  );
}
