import type { PaymentLineInput } from '@nadel/api-client';
import { isZeroMoney, normalizeMoneyInput } from '@nadel/shared';

/** The typed amounts per charge once the treasurer has changed the proposal; null while the proposal stands. */
export type Edits = Readonly<Record<string, string>> | null;

export interface Lines {
  /** Absent while the server's proposal stands. */
  lines: PaymentLineInput[] | undefined;
  /** Charge ids in the order of `lines` as they were sent. */
  ids: string[];
  /** Typed texts that are not an amount, by charge id. */
  invalid: Set<string>;
}

export function linesOf(edits: Edits): Lines {
  if (edits === null) return { lines: undefined, ids: [], invalid: new Set() };
  const lines: PaymentLineInput[] = [];
  const ids: string[] = [];
  const invalid = new Set<string>();
  for (const [chargeId, text] of Object.entries(edits)) {
    const amount = normalizeMoneyInput(text);
    if (amount === undefined) invalid.add(chargeId);
    else if (!isZeroMoney(amount)) {
      lines.push({ chargeId, amount });
      ids.push(chargeId);
    }
  }
  return { lines, ids, invalid };
}
