import { describe, expect, it } from 'vitest';
import { formatRate, normalizeRateInput } from './money';

describe('formatRate', () => {
  it('pads to four decimals with the locale separator', () => {
    expect(formatRate('4.3200')).toBe('4,3200');
    expect(formatRate('4.32')).toBe('4,3200');
    expect(formatRate('4', 'en-US')).toBe('4.0000');
    expect(formatRate('0.0001', 'en')).toBe('0.0001');
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
