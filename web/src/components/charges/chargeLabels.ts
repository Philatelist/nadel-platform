import type { Charge } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';

/** What a ledger entry is called: a regular charge by its source, a reversal and an opening balance by their kind. */
export function chargeEntryLabel(t: Messages, charge: Pick<Charge, 'kind' | 'source'>): string {
  return charge.kind === 'REGULAR' ? t.charges.source[charge.source] : t.charges.kind[charge.kind];
}
