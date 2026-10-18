import { describe, expect, it } from 'vitest';
import { formatSotky, formatSotkyWithUnit, m2ToSotky, toSquareMetres } from './area';

describe('m2ToSotky', () => {
  it.each([
    ['600', '6'],
    ['600.00', '6'],
    ['650.5', '6.505'],
    ['650.50', '6.505'],
    ['612.34', '6.1234'],
    ['5', '0.05'],
    ['0.00', '0'],
    ['0', '0'],
    ['100000', '1000'],
    ['0.01', '0.0001'],
  ])('converts %s m² to %s sotkas', (m2, sotky) => {
    expect(m2ToSotky(m2)).toBe(sotky);
  });

  it('accepts numbers without float noise', () => {
    expect(m2ToSotky(600)).toBe('6');
    expect(m2ToSotky(1234.1)).toBe('12.341');
    expect(m2ToSotky(0.07 * 100)).toBe('0.07');
  });

  it('rejects non-decimal input', () => {
    expect(() => m2ToSotky('abc')).toThrow();
  });
});

describe('formatSotky', () => {
  it('uses the locale decimal separator', () => {
    expect(formatSotky('650.50', 'uk-UA')).toBe('6,505');
    expect(formatSotky('650.50', 'en-US')).toBe('6.505');
  });
  it('groups thousands and keeps exact digits', () => {
    expect(formatSotky('123456789012.34', 'en-US')).toBe('1,234,567,890.1234');
  });
  it('shows whole sotkas without decimals', () => {
    expect(formatSotky(600, 'en-US')).toBe('6');
  });
});

describe('toSquareMetres', () => {
  it.each([
    ['6.5', 'SOTKA', '650'],
    ['6,5', 'SOTKA', '650'],
    ['6', 'SOTKA', '600'],
    ['0.01', 'SOTKA', '1'],
    ['0.015', 'SOTKA', '1.5'],
    ['600', 'M2', '600'],
    ['600,25', 'M2', '600.25'],
    ['0', 'M2', '0'],
    [' 007 ', 'M2', '7'],
  ] as const)('converts %s %s to %s m²', (value, unit, m2) => {
    expect(toSquareMetres(value, unit)).toBe(m2);
  });

  it.each([
    ['abc', 'SOTKA'],
    ['', 'SOTKA'],
    ['-1', 'M2'],
    ['1.2.3', 'M2'],
    ['0.00001', 'SOTKA'],
    ['1.234', 'M2'],
  ] as const)('rejects %j in %s', (value, unit) => {
    expect(toSquareMetres(value, unit)).toBeUndefined();
  });
});

describe('formatSotkyWithUnit', () => {
  it.each([
    ['100', 'сотка'],
    ['200', 'сотки'],
    ['500', 'соток'],
    ['2100', 'сотка'],
    ['1100', 'соток'],
  ])('uk %s m²', (m2, unit) => {
    expect(formatSotkyWithUnit(m2, 'uk')).toMatch(new RegExp(`^\\d+ ${unit}$`));
  });
  it('uk fractions', () => {
    expect(formatSotkyWithUnit('650', 'uk')).toBe('6,5 сотки');
  });
  it('en', () => {
    expect(formatSotkyWithUnit('100', 'en')).toBe('1 sotka');
    expect(formatSotkyWithUnit('600', 'en')).toBe('6 sotkas');
    expect(formatSotkyWithUnit('650', 'en')).toBe('6.5 sotkas');
  });
});
