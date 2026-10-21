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

describe('meter problems', () => {
  it('has texts for the meter and reading codes in both languages', () => {
    for (const locale of ['uk', 'en'] as const) {
      const t = messages(locale);
      for (const code of [
        'METER_ACTIVE_EXISTS',
        'METER_COMMON_LAND',
        'METER_HAS_READINGS',
        'METER_REMOVED',
        'PLOT_HAS_METER',
        'READING_DATE_IN_FUTURE',
        'READING_DATE_OUTSIDE_METER',
        'READING_OUT_OF_ORDER',
        'METER_VALUE_INVALID',
        'UNKNOWN_PLOT',
      ]) {
        expect(t.problem[code as keyof typeof t.problem], code).not.toBe(t.problem.UNKNOWN);
      }
    }
    expect(messages('uk').problem.PLOT_HAS_METER).toBe('Спершу зніміть лічильник ділянки');
  });

  it('maps field codes of a meter to their texts', () => {
    const error = new ApiError(409, {
      code: 'READING_OUT_OF_ORDER',
      errors: [
        { field: 'finalReading', code: 'READING_OUT_OF_ORDER' },
        { field: 'plotId', code: 'PLOT_ARCHIVED' },
        { field: 'kind', code: 'PLOT_HAS_METER' },
      ],
    });
    const result = problemFieldErrors(messages('en'), error);
    expect(result.finalReading).toBe(
      'The reading is lower than the previous one or higher than the next one',
    );
    expect(result.plotId).toBe('The plot is archived');
    expect(result.kind).toBe('Remove the plot’s meter first');
  });
});
