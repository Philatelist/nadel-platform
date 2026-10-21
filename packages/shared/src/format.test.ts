import { describe, expect, it } from 'vitest';
import { formatDateTime, formatDay } from './format';

describe('formatDay', () => {
  it('uk uses dd.MM.yyyy', () => {
    expect(formatDay('2026-10-21', 'uk')).toBe('21.10.2026');
    expect(formatDay('2025-06-01', 'uk')).toBe('01.06.2025');
  });
  it('en uses d Mon yyyy', () => {
    expect(formatDay('2026-10-21', 'en')).toBe('21 Oct 2026');
    expect(formatDay('2025-06-01', 'en')).toBe('1 Jun 2025');
    expect(formatDay('2026-09-03', 'en')).toBe('3 Sep 2026');
  });
});

describe('formatDateTime', () => {
  it('shows Europe/Kyiv time (summer UTC+3)', () => {
    expect(formatDateTime('2026-10-21T11:05:00Z', 'uk')).toBe('21.10.2026, 14:05');
    expect(formatDateTime('2026-10-21T11:05:00Z', 'en')).toBe('21 Oct 2026, 14:05');
  });
  it('uses the Kyiv day across midnight and winter time (UTC+2)', () => {
    expect(formatDateTime('2026-12-31T22:30:00Z', 'uk')).toBe('01.01.2027, 00:30');
  });
});
