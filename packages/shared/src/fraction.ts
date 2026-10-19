/** An exact fraction of integers; never floating point. Shares of a plot are fractions of 1. */
export interface Fraction {
  readonly numerator: bigint;
  readonly denominator: bigint;
}

/** The largest denominator accepted for a share typed by a person. */
export const MAX_SHARE_DENOMINATOR = 1000n;

const SHARE_PATTERN = /^(\d{1,4})(?:\s*\/\s*(\d{1,4}))?$/;

function gcd(a: bigint, b: bigint): bigint {
  let x = a < 0n ? -a : a;
  let y = b < 0n ? -b : b;
  while (y !== 0n) [x, y] = [y, x % y];
  return x;
}

/** The fraction in lowest terms. */
export function reduceFraction(f: Fraction): Fraction {
  const divisor = gcd(f.numerator, f.denominator);
  return divisor <= 1n ? f : { numerator: f.numerator / divisor, denominator: f.denominator / divisor };
}

/**
 * Parses a share typed as "n/d" or a bare integer ("1" is 1/1). A share needs 1 ≤ n ≤ d ≤ 1000: zero, negative,
 * decimal and out-of-range input yields null. The result is in lowest terms.
 */
export function parseFraction(text: string): Fraction | null {
  const match = SHARE_PATTERN.exec(text.trim());
  if (!match) return null;
  const numerator = BigInt(match[1] ?? '');
  const denominator = BigInt(match[2] ?? '1');
  if (numerator < 1n || denominator > MAX_SHARE_DENOMINATOR || numerator > denominator) return null;
  return reduceFraction({ numerator, denominator });
}

/** Exact sum, in lowest terms. The sum may exceed 1; the caller decides whether that is acceptable. */
export function addFractions(fractions: readonly Fraction[]): Fraction {
  let sum: Fraction = { numerator: 0n, denominator: 1n };
  for (const f of fractions) {
    sum = reduceFraction({
      numerator: sum.numerator * f.denominator + f.numerator * sum.denominator,
      denominator: sum.denominator * f.denominator,
    });
  }
  return sum;
}

/** True when the fraction is a whole number (1, 2, ...). */
export function isWhole(f: Fraction): boolean {
  return reduceFraction(f).denominator === 1n;
}

/** True when the fraction equals exactly 1. */
export function isOne(f: Fraction): boolean {
  const r = reduceFraction(f);
  return r.numerator === 1n && r.denominator === 1n;
}

/** Lowest terms as "1/2"; a whole number as "1". */
export function formatFraction(f: Fraction): string {
  const r = reduceFraction(f);
  return r.denominator === 1n ? String(r.numerator) : `${r.numerator}/${r.denominator}`;
}

/** Formats a share string such as "2/4" in lowest terms, always as a fraction ("1/1" when whole); other text is returned as it is. */
export function formatShare(text: string): string {
  const f = parseFraction(text);
  if (!f) return text;
  const r = reduceFraction(f);
  return `${r.numerator}/${r.denominator}`;
}
