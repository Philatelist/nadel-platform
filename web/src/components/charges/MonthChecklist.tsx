import type { BillingMonthChecklist } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';

/** What to look at before a month is closed; every value is the server's. */
export function MonthChecklist({ checklist }: { checklist: BillingMonthChecklist }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.billing.checklist;
  return (
    <ul className="flex flex-col gap-3 text-[15px]">
      <li>{checklist.runDone ? c.runDone : c.runNotDone}</li>
      <li>
        {checklist.notChargedCount > 0 ? (
          <Link to="/charges/not-charged" className="text-accent underline">
            {format(c.notChargedCount, { count: checklist.notChargedCount })}
          </Link>
        ) : (
          format(c.notChargedCount, { count: checklist.notChargedCount })
        )}
      </li>
      <li>
        <p className="font-semibold">{checklist.staleMeters.length === 0 ? c.noStale : c.stale}</p>
        {checklist.staleMeters.length > 0 && (
          <ul className="mt-1 flex flex-col gap-1">
            {checklist.staleMeters.map((meter) => (
              <li key={meter.meterId} className="flex flex-wrap gap-x-2">
                <Link to={`/plots/${meter.plotId}`} className="text-accent underline">
                  <UserContent>{meter.plotNumber}</UserContent>
                </Link>
                <span>{t.meters.resource[meter.resource]}</span>
                <span className="text-ink-muted">
                  {format(c.lastReading, { date: formatDay(meter.lastReadingOn, locale) })}
                </span>
              </li>
            ))}
          </ul>
        )}
      </li>
    </ul>
  );
}
