import { describe, expect, it } from 'vitest';
import { formatDay } from './format';

describe('formatDay', () => {
  it('uses a four-digit year', () => {
    expect(formatDay('2025-06-01', 'uk')).toBe('01.06.2025');
  });
});
