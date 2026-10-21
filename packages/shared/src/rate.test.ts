import { describe, expect, it } from 'vitest';
import { formatRate, formatRateWithCurrency, normalizeRateInput } from './money';

describe('formatRate', () => {
  it('shows significant decimals, at least two', () => {
    expect(formatRate('50.0000')).toBe('50,00');
    expect(formatRate('4.3200')).toBe('4,32');
    expect(formatRate('1.4532')).toBe('1,4532');
    expect(formatRate('0.3500')).toBe('0,35');
    expect(formatRate('50.0000', 'en-US')).toBe('50.00');
    expect(formatRate('4.3200', 'en')).toBe('4.32');
    expect(formatRate('1.4532', 'en')).toBe('1.4532');
    expect(formatRate('0.3500', 'en')).toBe('0.35');
  });
  it('handles integers, tiny and zero rates', () => {
    expect(formatRate('4')).toBe('4,00');
    expect(formatRate('4', 'en-US')).toBe('4.00');
    expect(formatRate('0.0001', 'en')).toBe('0.0001');
    expect(formatRate('0.0000')).toBe('0,00');
  });
  it('keeps every digit of a long value (never through Number)', () => {
    expect(formatRate('33.3333')).toBe('33,3333');
    expect(formatRate('12345678901234567890.1234')).toBe('12345678901234567890,1234');
  });
  it('refuses anything that is not a plain rate', () => {
    for (const bad of ['', '-1', '4,32', '4.32001', '1e3', ' 4', '.5', '4.']) {
      expect(() => formatRate(bad)).toThrow();
    }
  });
});

describe('normalizeRateInput', () => {
  it('accepts a comma and trims', () => {
    expect(normalizeRateInput(' 4,32 ')).toBe('4.32');
    expect(normalizeRateInput('4.3200')).toBe('4.3200');
    expect(normalizeRateInput('0')).toBe('0');
  });
  it('rejects negatives, text, five decimals and double separators', () => {
    for (const bad of ['', '-1', 'abc', '4.32001', '4,3,2', '4..2', '12345678901', '1e3', '.5']) {
      expect(normalizeRateInput(bad)).toBeUndefined();
    }
  });
});

describe('formatRateWithCurrency', () => {
  it('puts the currency sign by locale', () => {
    expect(formatRateWithCurrency('4.3200', 'uk')).toBe('4,32\u00a0₴');
    expect(formatRateWithCurrency('4.3200', 'en')).toBe('₴4.32');
    expect(formatRateWithCurrency('1.4532', 'en')).toBe('₴1.4532');
  });
});
