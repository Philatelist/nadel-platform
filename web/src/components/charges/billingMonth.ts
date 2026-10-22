import { format, type Messages } from '@nadel/shared';
import type { QueryClient } from '@tanstack/react-query';
import { getGetBillingMonthQueryKey, getListBillingMonthsQueryKey } from '@nadel/api-client';

/** "2026-10" as "October 2026" / "Жовтень 2026"; an unexpected text is returned as it is. */
export function billingMonthLabel(month: string, t: Messages): string {
  const [year, number] = month.split('-');
  const names: Readonly<Record<string, string | undefined>> = t.billing.monthNames;
  const name = number === undefined ? undefined : names[number];
  return name === undefined ? month : `${name} ${year ?? ''}`.trim();
}

export function billingCloseTitle(month: string, t: Messages): string {
  return format(t.billing.close.title, { month: billingMonthLabel(month, t) });
}

/** After a month closes: the month list and page, and every plot account (the booking month of late entries moved). */
export async function invalidateAfterClose(queryClient: QueryClient, month: string): Promise<void> {
  const plots = (key: readonly unknown[]) => typeof key[0] === 'string' && key[0].startsWith('/api/v1/plots');
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: getListBillingMonthsQueryKey() }),
    queryClient.invalidateQueries({ queryKey: getGetBillingMonthQueryKey(month) }),
    queryClient.invalidateQueries({ predicate: (query) => plots(query.queryKey) }),
  ]);
}
