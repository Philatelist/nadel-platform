/** A meter value is an exact non-negative decimal string with at most three decimals ("1250.5", "0.125"). */
export type MeterValueString = string;

const METER_INPUT = /^(\d{1,11})(?:[.,](\d{0,3}))?$/;
const METER_INPUT_EN = /^(\d{1,3}(?:,\d{3})+|\d{1,11})(?:\.(\d{0,3}))?$/;
const METER_VALUE = /^(\d+)(?:\.(\d{1,3}))?$/;
const NBSP = ' ';

/**
 * Parses what a person typed into a canonical decimal string. For `en`, "," is only a group separator (well-formed
 * thousands groups) and "." the decimal separator ("8,205.5" -> "8205.5"); otherwise spaces are group separators and
 * "," or "." the decimal ("1 250,5" -> "1250.5"). Works on the text only: no floating point. Returns `undefined` for
 * empty, negative or invalid input and for more than three decimals (a value is never rounded).
 */
export function parseMeterValue(input: string, locale: string): MeterValueString | undefined {
  const english = isEnglish(locale);
  const match = (english ? METER_INPUT_EN : METER_INPUT).exec(input.replace(/[\s\u00a0]/g, ''));
  if (!match) return undefined;
  const whole = (match[1] ?? '').replace(/,/g, '').replace(/^0+(?=\d)/, '');
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  return fraction ? `${whole}.${fraction}` : whole;
}

function isEnglish(locale: string): boolean {
  return locale.toLowerCase().startsWith('en');
}

/**
 * Formats a server meter value (always three decimals) for display with the locale's separators, trimming trailing
 * zeros: "1234.500" -> uk "1 234,5", en "1,234.5"; "8205.000" -> "8 205". Text only, no arithmetic.
 */
export function formatMeterValue(value: MeterValueString, locale: string): string {
  const match = METER_VALUE.exec(value);
  if (!match) throw new Error(`Invalid meter value: ${value}`);
  const whole = match[1] ?? '0';
  const english = isEnglish(locale);
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, english ? ',' : NBSP);
  return fraction ? `${grouped}${english ? '.' : ','}${fraction}` : grouped;
}

/**
 * Rounds a server meter value half-up to `decimals` places (0..3) on the decimal string, for display of calculated
 * (interpolated) consumption: electricity to whole kWh (0), water to 0.01 m3 (2). Text only, no floating point.
 * The result is canonical for `formatMeterValue` ("1234.567", 0 -> "1235"; "0.005", 2 -> "0.01").
 */
export function roundMeterValue(value: MeterValueString, decimals: number): MeterValueString {
  const match = METER_VALUE.exec(value);
  if (!match) throw new Error(`Invalid meter value: ${value}`);
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 3)
    throw new Error(`Invalid decimals: ${decimals}`);
  const whole = match[1] ?? '0';
  const fraction = (match[2] ?? '').padEnd(3, '0');
  const keep = fraction.slice(0, decimals);
  const roundUp = (fraction[decimals] ?? '0') >= '5';
  let digits = `${whole}${keep}`;
  if (roundUp) {
    const chars = digits.split('');
    let i = chars.length - 1;
    while (i >= 0 && chars[i] === '9') chars[i--] = '0';
    if (i < 0) chars.unshift('1');
    else chars[i] = String(Number(chars[i]) + 1);
    digits = chars.join('');
  }
  const padded = digits.padStart(decimals + 1, '0');
  const intPart = padded.slice(0, padded.length - decimals).replace(/^0+(?=\d)/, '');
  const fracPart = padded.slice(padded.length - decimals);
  return decimals === 0 ? intPart : `${intPart}.${fracPart}`;
}
