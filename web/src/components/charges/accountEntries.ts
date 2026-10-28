import type { AccountPayment, AccountPrepaymentAllocation, Charge } from '@nadel/api-client';

export type AccountEntry =
  | { type: 'charge'; date: string; key: string; charge: Charge }
  | { type: 'payment'; date: string; key: string; payment: AccountPayment }
  | { type: 'placement'; date: string; key: string; placement: AccountPrepaymentAllocation };

/**
 * Charges (by accrual day), payments (by day received) and prepayment placements (by day placed) as one list, newest
 * first. Days are ISO strings, so they are ordered by comparing the strings; equal days keep the server order
 * (charges, then payments, then placements). Nothing is computed.
 */
export function mergeAccountEntries(
  charges: readonly Charge[],
  payments: readonly AccountPayment[],
  placements: readonly AccountPrepaymentAllocation[],
): AccountEntry[] {
  const entries: AccountEntry[] = [
    ...charges.map((charge): AccountEntry => ({
      type: 'charge',
      date: charge.accruedOn,
      key: `c${charge.id}`,
      charge,
    })),
    ...payments.map((payment): AccountEntry => ({
      type: 'payment',
      date: payment.receivedOn,
      key: `p${payment.id}`,
      payment,
    })),
    ...placements.map((placement, i): AccountEntry => ({
      type: 'placement',
      date: placement.allocatedOn,
      key: `a${placement.createdAt}-${i}`,
      placement,
    })),
  ];
  return entries.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
}
