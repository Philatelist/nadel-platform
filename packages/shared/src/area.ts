const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

/**
 * Converts an area in m² to sotkas (1 sotka = 100 m²) as a plain decimal string, by moving the decimal point in
 * the text: no floating-point arithmetic, trailing zeros trimmed ("600.00" -> "6", "650.5" -> "6.505").
 * A number is rendered with at most two decimals first (the API's precision for area).
 */
export function m2ToSotky(m2: string | number): string {
  const text = typeof m2 === 'number' ? m2.toFixed(2) : m2.trim();
  const match = DECIMAL.exec(text);
  if (!match) throw new Error(`Not a decimal area: ${text}`);
  const [, sign = '', whole = '0', fraction = ''] = match;
  const padded = whole.padStart(2, '0');
  const integer = padded.slice(0, -2).replace(/^0+(?=\d)/, '') || '0';
  const decimals = (padded.slice(-2) + fraction).replace(/0+$/, '');
  const result = decimals ? `${integer}.${decimals}` : integer;
  return result === '0' ? result : sign + result;
}

/** Area shown in sotkas with the locale's grouping and decimal separator; the input is an m² decimal (string preferred). */
export function formatSotky(areaM2: string | number, locale: string = 'uk-UA'): string {
  const [integer = '0', decimals] = m2ToSotky(areaM2).split('.');
  const negative = integer.startsWith('-');
  const digits = negative ? integer.slice(1) : integer;
  const nf = new Intl.NumberFormat(locale);
  const separator =
    new Intl.NumberFormat(locale).formatToParts(1.1).find((p) => p.type === 'decimal')?.value ?? '.';
  const grouped = nf.format(BigInt(digits));
  return `${negative ? '-' : ''}${grouped}${decimals ? separator + decimals : ''}`;
}

export type AreaUnit = 'SOTKA' | 'M2';

const INPUT = /^(\d+)(?:[.,](\d*))?$/;

/**
 * Parses what a person typed (a "," or "." separator) in the given unit into square metres as a plain decimal
 * string, by moving the decimal point in the text: no floating point. Sotkas are multiplied by 100. Returns
 * `undefined` for empty, invalid or negative input and when the result would need more than two decimals of m².
 */
export function toSquareMetres(value: string, unit: AreaUnit): string | undefined {
  const match = INPUT.exec(value.trim());
  if (!match) return undefined;
  const whole = match[1] ?? '';
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  const shift = unit === 'SOTKA' ? 2 : 0;
  if (fraction.length > 2 + shift) return undefined;
  const decimals = fraction.padEnd(shift, '0');
  const integer = (whole + decimals.slice(0, shift)).replace(/^0+(?=\d)/, '');
  const rest = decimals.slice(shift);
  return rest ? `${integer}.${rest}` : integer;
}

/** "1 sotka", "6 sotkas", "6.5 sotkas" / "1 сотка", "2 сотки", "5 соток", "21 сотка", "6,5 сотки". */
export function formatSotkyWithUnit(areaM2: string | number, locale: string = 'uk-UA'): string {
  const amount = m2ToSotky(areaM2);
  const hasFraction = amount.includes('.');
  const category = hasFraction ? 'other' : new Intl.PluralRules(locale).select(Number(amount));
  const isUk = locale.toLowerCase().startsWith('uk');
  const unit = isUk
    ? ({ one: 'сотка', few: 'сотки', many: 'соток', other: 'сотки' } as Record<string, string>)[category]
    : category === 'one'
      ? 'sotka'
      : 'sotkas';
  return `${formatSotky(areaM2, locale)} ${unit ?? ''}`.trim();
}
