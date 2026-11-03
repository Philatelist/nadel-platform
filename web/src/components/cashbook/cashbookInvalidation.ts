import {
  getGetBankReconciliationQueryKey,
  getListCashCountsQueryKey,
  getListExpensesQueryKey,
  getListMoneyAccountsQueryKey,
} from '@nadel/api-client';
import type { QueryClient } from '@tanstack/react-query';

const PREFIXES = [
  getListMoneyAccountsQueryKey()[0],
  getListExpensesQueryKey()[0],
  getListCashCountsQueryKey()[0],
  getGetBankReconciliationQueryKey('')[0].replace(/\/$/, ''),
];

/** Accounts, their journals, the cash counts and the expenses change together: refetch all of them. */
export function invalidateCashbook(queryClient: QueryClient) {
  return queryClient.invalidateQueries({
    predicate: (query) =>
      typeof query.queryKey[0] === 'string' &&
      PREFIXES.some((prefix) => (query.queryKey[0] as string).startsWith(prefix)),
  });
}
