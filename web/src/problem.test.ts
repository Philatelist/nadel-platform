import { ApiError } from '@nadel/api-client';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { fieldKey, problemFieldErrors } from './problem';

describe('fieldKey for ownership changes', () => {
  it('keeps owner rows, their property, owners and effectiveFrom', () => {
    expect(fieldKey('owners[2].share')).toBe('owners[2].share');
    expect(fieldKey('owners[0].memberId')).toBe('owners[0].memberId');
    expect(fieldKey('owners')).toBe('owners');
    expect(fieldKey('effectiveFrom')).toBe('effectiveFrom');
  });

  it('maps server field errors to messages by key', () => {
    const error = new ApiError(409, {
      code: 'SHARES_NOT_ONE',
      errors: [
        { field: 'owners', code: 'SHARES_NOT_ONE' },
        { field: 'owners[1].memberId', code: 'DUPLICATE_OWNER' },
      ],
    });
    const result = problemFieldErrors(messages('en'), error);
    expect(result.owners).toBe('Shares must add up to 1');
    expect(result['owners[1].memberId']).toBe('This member is already chosen.');
  });
});
