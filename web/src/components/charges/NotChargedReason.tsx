import type { NotChargedItem } from '@nadel/api-client';
import { formatDay } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { billingMonthLabel } from './billingMonth';

/** The reason of a queue item and, when tariffs are involved, links to them. */
export function NotChargedReason({ item }: { item: NotChargedItem }) {
  const t = useT();
  const n = t.billing.notCharged;
  return (
    <div className="flex flex-col gap-1">
      <span>{n.reason[item.reason]}</span>
      {item.tariffs.length > 0 && (
        <span className="text-sm text-ink-muted">
          {n.tariffs}:{' '}
          {item.tariffs.map((tariff, index) => (
            <span key={tariff.id}>
              {index > 0 && ', '}
              <Link to={`/tariffs/${tariff.id}`} className="text-accent underline">
                <UserContent>{tariff.name}</UserContent>
              </Link>
            </span>
          ))}
        </span>
      )}
    </div>
  );
}

/** The month of a tariff item, or the days an item is about; empty when the server gives neither. */
export function NotChargedPeriod({ item }: { item: NotChargedItem }) {
  const t = useT();
  const { locale } = useLocale();
  if (item.month) return <>{billingMonthLabel(item.month, t)}</>;
  if (item.from && item.to) return <>{`${formatDay(item.from, locale)} – ${formatDay(item.to, locale)}`}</>;
  return <>{t.billing.notCharged.noPlot}</>;
}

/** The plot of an item as a link to the plot card. */
export function NotChargedPlot({ item }: { item: NotChargedItem }) {
  const t = useT();
  if (!item.plotId && !item.meterId) return <>{t.billing.notCharged.noPlot}</>;
  return (
    <div className="flex flex-col gap-1">
      {item.plotId && (
        <Link to={`/plots/${item.plotId}`} className="text-accent underline">
          <UserContent>{item.plotNumber ?? item.plotId}</UserContent>
        </Link>
      )}
      {item.meterId && (
        <Link to={`/meters/${item.meterId}`} className="text-sm text-accent underline">
          {item.kind === 'LOSSES_NODE' ? t.billing.notCharged.node : t.billing.notCharged.meter}
        </Link>
      )}
    </div>
  );
}
