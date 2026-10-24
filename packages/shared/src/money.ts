/**
 * Money comes from the server as a string such as "1250.50". Clients only display it
 * and never do arithmetic with amounts (CLAUDE.md, "Money" section).
 */
export type MoneyString = string;

const MONEY_RE = /^-?\d+\.\d{2}$/;

export function isMoney(value: string): value is MoneyString {
  return MONEY_RE.test(value);
}

const NBSP = '\u00a0';

function isEnglish(locale: string): boolean {
  return locale.toLowerCase().startsWith('en');
}

/**
 * "1250.50" → uk "1 250,50 ₴" (no-break space as group separator and before the sign), en "₴1,250.50".
 * Negative amounts keep the minus sign U+2212 in front: uk "−350,00 ₴", en "−₴350.00". Strings only, no arithmetic.
 */
export function formatMoney(value: MoneyString, locale: string): string {
  if (!isMoney(value)) {
    throw new Error(`Invalid money amount: ${value}`);
  }
  const negative = value.startsWith('-');
  const [int = '0', frac = '00'] = (negative ? value.slice(1) : value).split('.');
  const sign = negative ? '\u2212' : '';
  if (isEnglish(locale)) {
    return `${sign}₴${int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${frac}`;
  }
  return `${sign}${int.replace(/\B(?=(\d{3})+(?!\d))/g, NBSP)},${frac}${NBSP}₴`;
}

/** Like `formatMoney`, but a positive amount gets a leading "+" (display only; zero and negative are unchanged). */
export function formatSignedMoney(value: MoneyString, locale: string): string {
  const text = formatMoney(value, locale);
  return !value.startsWith('-') && !isZeroMoney(value) ? `+${text}` : text;
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

/**
 * Displays a rate with its significant decimals but at least two: "50.0000" → "50,00", "4.3200" → "4,32",
 * "1.4532" → "1,4532" (uk) or "50.00", "4.32", "1.4532" (en). Strings only, so no digit is lost; no grouping.
 * Storage and API keep four decimals.
 */
export function formatRate(rate: RateString, locale: string): string {
  if (!RATE_RE.test(rate)) {
    throw new Error(`Invalid rate: ${rate}`);
  }
  const [int = '0', frac = ''] = rate.split('.');
  const trimmed = frac.replace(/0+$/, '').padEnd(2, '0');
  return `${int}${isEnglish(locale) ? '.' : ','}${trimmed}`;
}

/** A rate with the hryvnia sign: uk "4,32 ₴" (no-break space), en "₴4.32". Same significant-decimals rule as `formatRate`. */
export function formatRateWithCurrency(rate: RateString, locale: string): string {
  const text = formatRate(rate, locale);
  return isEnglish(locale) ? `₴${text}` : `${text}${NBSP}₴`;
}

/**
 * Normalises typed rate text: trims, accepts a comma as the decimal separator (string replace) and checks
 * a non-negative number with at most 10 integer and 4 decimal digits. Returns the dot form, or undefined.
 */
export function normalizeRateInput(text: string): RateString | undefined {
  const value = text.trim().replace(',', '.');
  return RATE_INPUT_RE.test(value) ? value : undefined;
}

const MONEY_INPUT_RE = /^\d{1,12}(\.\d{1,2})?$/;

/**
 * Normalises typed amount text into the API form "1250.50": trims, drops spaces, accepts a comma as the decimal
 * separator and pads to two decimals, all on the text (no arithmetic). Returns undefined for anything that is not a
 * non-negative amount with at most two decimals; whether it is greater than zero is the server's call.
 */
export function normalizeMoneyInput(text: string): MoneyString | undefined {
  const value = text.replace(/[\s ]/g, '').replace(',', '.');
  if (!MONEY_INPUT_RE.test(value)) return undefined;
  const [int = '0', frac = ''] = value.split('.');
  return `${int.replace(/^0+(?=\d)/, '')}.${frac.padEnd(2, '0')}`;
}
