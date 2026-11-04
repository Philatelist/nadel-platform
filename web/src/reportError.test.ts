import { ApiError } from '@nadel/api-client';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { reportErrorMessage } from './reportError';

describe('reportErrorMessage', () => {
  const t = messages('en');

  it('names a missing opening balance', () => {
    expect(reportErrorMessage(t, new ApiError(409, { code: 'OPENING_BALANCE_MISSING' }))).toBe(
      t.reports.errors.OPENING_BALANCE_MISSING,
    );
  });

  it('names a period before the opening balance from the field error', () => {
    const error = new ApiError(400, {
      code: 'VALIDATION_FAILED',
      errors: [{ field: 'from', code: 'BEFORE_OPENING_BALANCE' }],
    });
    expect(reportErrorMessage(t, error)).toBe(t.reports.errors.BEFORE_OPENING_BALANCE);
  });
});
