import { formatDay, type Messages } from '@nadel/shared';
import { billingMonthLabel } from '../charges/billingMonth';

/** The service period of a charge: the month alone when it lies in one month, otherwise the two days. */
export function servicePeriodLabel(from: string, to: string, t: Messages, locale: string): string {
  return from.slice(0, 7) === to.slice(0, 7)
    ? billingMonthLabel(from.slice(0, 7), t)
    : `${formatDay(from, locale)}–${formatDay(to, locale)}`;
}
