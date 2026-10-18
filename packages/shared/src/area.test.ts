import { describe, expect, it } from 'vitest';
import { formatSotky, m2ToSotky } from './area';

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
