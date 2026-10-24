import type { Charge } from '@nadel/api-client';
import { format, formatSignedMoney, formatMonth, type Messages } from '@nadel/shared';

/** What a ledger entry is called: a regular charge by its source, a reversal, an opening balance or an adjustment by their kind. */
export function chargeEntryLabel(t: Messages, charge: Pick<Charge, 'kind' | 'source'>): string {
  return charge.kind === 'REGULAR' ? t.charges.source[charge.source] : t.charges.kind[charge.kind];
}

/** An adjustment names the charge it corrects; every other entry has no `adjusts`. */
export function isAdjustment(charge: Pick<Charge, 'kind' | 'adjusts'>): boolean {
  return charge.kind === 'ADJUSTMENT';
}

/** "Adjustment for <month of the original>: <amount>"; the reason follows as user content, not in this text. */
export function adjustmentLead(
  t: Messages,
  charge: Pick<Charge, 'amount' | 'adjusts' | 'billingMonth'>,
  locale: string,
): string {
  return format(t.charges.adjustment.line, {
    month: formatMonth(charge.adjusts?.billingMonth ?? charge.billingMonth, locale),
    amount: formatSignedMoney(charge.amount, locale),
  });
}
