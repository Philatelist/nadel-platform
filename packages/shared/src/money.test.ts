import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

// money.ts uses U+00A0 (no-break space) for grouping and before the currency sign, and U+2212 as the minus.
const NBSP = ' ';
const MINUS = '−';
const UAH = '₴';

describe('formatMoney', () => {
  it('formats zero', () => {
    expect(formatMoney('0.00')).toBe(`0,00${NBSP}${UAH}`);
  });
  it('groups thousands', () => {
    expect(formatMoney('1250.50')).toBe(`1${NBSP}250,50${NBSP}${UAH}`);
  });
  it('uses the minus sign for negatives', () => {
    expect(formatMoney('-350.00')).toBe(`${MINUS}350,00${NBSP}${UAH}`);
  });
  it('groups millions', () => {
    expect(formatMoney('1000000.00')).toBe(`1${NBSP}000${NBSP}000,00${NBSP}${UAH}`);
  });
});
