import type { BankMonthStatus } from '@nadel/api-client';
import type { BadgeTone } from '../ui';

/** The badge tone of a bank month's reconciliation status. */
export const STATUS_TONE: Record<BankMonthStatus, BadgeTone> = {
  NOT_RECONCILED: 'neutral',
  RECONCILED: 'success',
  NEEDS_RECHECK: 'warning',
};
