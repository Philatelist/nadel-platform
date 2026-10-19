import { describe, expect, it } from 'vitest';
import {
  addFractions,
  formatFraction,
  formatShare,
  isOne,
  isWhole,
  parseFraction,
  type Fraction,
} from './fraction';

function parsed(text: string): Fraction {
  const f = parseFraction(text);
  if (!f) throw new Error(`not a share: ${text}`);
  return f;
}

describe('parseFraction', () => {
  it('reduces 2/4 to 1/2', () => {
    expect(formatFraction(parsed('2/4'))).toBe('1/2');
  });

  it('reads a bare integer 1 as the whole plot', () => {
    expect(parsed('1')).toEqual({ numerator: 1n, denominator: 1n });
    expect(formatFraction(parsed('1'))).toBe('1');
  });

  it('accepts spaces around the slash and the limit of 1000', () => {
    expect(formatFraction(parsed(' 1 / 3 '))).toBe('1/3');
    expect(formatFraction(parsed('1/1000'))).toBe('1/1000');
  });

  it.each(['0.5', '0/5', '-1/2', '1/-2', '1001/2000', '1/1001', '3/2', '2', 'a/b', '', '1/', '1/0', '1,5'])(
    'rejects %j',
    (text) => {
      expect(parseFraction(text)).toBeNull();
    },
  );
});

describe('addFractions', () => {
  it('adds 1/2 and 1/3 to 5/6', () => {
    expect(formatFraction(addFractions([parsed('1/2'), parsed('1/3')]))).toBe('5/6');
  });

  it('makes exactly 1 of three thirds, with no rounding error', () => {
    const total = addFractions([parsed('1/3'), parsed('1/3'), parsed('1/3')]);
    expect(isOne(total)).toBe(true);
    expect(formatFraction(total)).toBe('1');
  });

  it('is zero for no fractions', () => {
    expect(formatFraction(addFractions([]))).toBe('0');
    expect(isOne(addFractions([]))).toBe(false);
  });

  it('can exceed 1', () => {
    const total = addFractions([parsed('1/2'), parsed('2/3')]);
    expect(formatFraction(total)).toBe('7/6');
    expect(isWhole(total)).toBe(false);
  });
});

describe('isWhole / formatShare', () => {
  it('knows whole numbers', () => {
    expect(isWhole({ numerator: 4n, denominator: 2n })).toBe(true);
    expect(isWhole(parsed('1/2'))).toBe(false);
  });

  it('formats a server share in lowest terms and leaves other text alone', () => {
    expect(formatShare('2/4')).toBe('1/2');
    expect(formatShare('n/a')).toBe('n/a');
  });
});
