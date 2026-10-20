/**
 * Money comes from the server as a string such as "1250.50". Clients only display it
 * and never do arithmetic with amounts (CLAUDE.md, "Money" section).
 */
export type MoneyString = string;

const MONEY_RE = /^-?\d+\.\d{2}$/;

export function isMoney(value: string): value is MoneyString {
  return MONEY_RE.test(value);
}

/** "1250.50" → "1 250,50 ₴" (non-breaking spaces, Ukrainian format). */
export function formatMoney(value: MoneyString, locale: string = 'uk-UA'): string {
  if (!isMoney(value)) {
    throw new Error(`Invalid money amount: ${value}`);
  }
  const negative = value.startsWith('-');
  const [int = '0', frac = '00'] = (negative ? value.slice(1) : value).split('.');
  const group = locale.startsWith('en') ? ',' : ' ';
  const decimal = locale.startsWith('en') ? '.' : ',';
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, group);
  return `${negative ? '−' : ''}${grouped}${decimal}${frac} ₴`;
}

export function isZeroMoney(value: MoneyString): boolean {
  return /^-?0+\.00$/.test(value);
}

/**
 * A tariff rate is not money: it keeps up to four decimals and is never rounded. Like money it travels as a string
 * and is only displayed, never computed with.
 */
export type RateString = string;

const RATE_RE = /^\d+(\.\d{1,4})?$/;
const RATE_INPUT_RE = /^\d{1,10}(\.\d{1,4})?$/;

/** "4.32" → "4,3200" (uk) or "4.3200" (en). Strings only, so no digit is lost; no grouping. */
export function formatRate(rate: RateString, locale: string = 'uk-UA'): string {
  if (!RATE_RE.test(rate)) {
    throw new Error(`Invalid rate: ${rate}`);
  }
  const [int = '0', frac = ''] = rate.split('.');
  return `${int}${locale.startsWith('en') ? '.' : ','}${frac.padEnd(4, '0')}`;
}

/**
 * Normalises typed rate text: trims, accepts a comma as the decimal separator (string replace) and checks
 * a non-negative number with at most 10 integer and 4 decimal digits. Returns the dot form, or undefined.
 */
export function normalizeRateInput(text: string): RateString | undefined {
  const value = text.trim().replace(',', '.');
  return RATE_INPUT_RE.test(value) ? value : undefined;
}
