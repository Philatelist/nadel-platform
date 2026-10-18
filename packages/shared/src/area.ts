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
