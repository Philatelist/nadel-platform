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
