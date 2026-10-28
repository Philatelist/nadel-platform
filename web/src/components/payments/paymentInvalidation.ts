import type { QueryClient } from '@tanstack/react-query';

/**
 * After a payment is saved: the payments list and cards, every plot query (account, card, list, status, map), the
 * billing months (their totals) and the member's own account all show new numbers.
 */
export async function invalidateAfterPayment(queryClient: QueryClient): Promise<void> {
  const startsWith = (prefix: string) => (query: { queryKey: readonly unknown[] }) =>
    typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith(prefix);
  await Promise.all([
    queryClient.invalidateQueries({ predicate: startsWith('/api/v1/payments') }),
    queryClient.invalidateQueries({ predicate: startsWith('/api/v1/plots') }),
    queryClient.invalidateQueries({ predicate: startsWith('/api/v1/billing-months') }),
    queryClient.invalidateQueries({ predicate: startsWith('/api/v1/me') }),
  ]);
}
