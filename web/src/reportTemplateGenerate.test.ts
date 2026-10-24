import { describe, expect, it } from 'vitest';
import { generatedFileName, previousMonthPeriod, validatePeriod } from './reportTemplateGenerate';

describe('previousMonthPeriod', () => {
  it('is the whole previous calendar month', () => {
    expect(previousMonthPeriod(new Date('2026-10-24T09:00:00Z'))).toEqual({
      from: '2026-09-01',
      to: '2026-09-30',
    });
  });
  it('crosses the year and knows a leap February', () => {
    expect(previousMonthPeriod(new Date('2026-01-10T09:00:00Z'))).toEqual({
      from: '2025-12-01',
      to: '2025-12-31',
    });
    expect(previousMonthPeriod(new Date('2024-03-10T09:00:00Z'))).toEqual({
      from: '2024-02-01',
      to: '2024-02-29',
    });
  });
  it('counts the day in Europe/Kyiv', () => {
    // 22:30 UTC on 30 September is already 1 October in Kyiv.
    expect(previousMonthPeriod(new Date('2026-09-30T22:30:00Z'))).toEqual({
      from: '2026-09-01',
      to: '2026-09-30',
    });
  });
});

describe('validatePeriod', () => {
  it('accepts a month, one day and a period one day short of a year', () => {
    expect(validatePeriod({ from: '2026-09-01', to: '2026-09-30' })).toBeUndefined();
    expect(validatePeriod({ from: '2026-09-01', to: '2026-09-01' })).toBeUndefined();
    expect(validatePeriod({ from: '2026-01-01', to: '2026-12-31' })).toBeUndefined();
  });
  it('refuses a missing day, an end before the start and a year or more', () => {
    expect(validatePeriod({ from: '', to: '2026-09-30' })).toBe('dateRequired');
    expect(validatePeriod({ from: '2026-09-01', to: '2026-08-31' })).toBe('toBeforeFrom');
    expect(validatePeriod({ from: '2026-01-01', to: '2027-01-01' })).toBe('tooLong');
  });
});

describe('generatedFileName', () => {
  it('slugs a Latin name and appends the period', () => {
    expect(generatedFileName('Water form (2026)', { from: '2026-09-01', to: '2026-09-30' })).toBe(
      'Water-form-2026_2026-09-01_2026-09-30.xlsx',
    );
  });
  it('falls back to report when the name has nothing ASCII', () => {
    expect(generatedFileName('Податковий звіт', { from: '2026-01-01', to: '2026-03-31' })).toBe(
      'report_2026-01-01_2026-03-31.xlsx',
    );
  });
});
