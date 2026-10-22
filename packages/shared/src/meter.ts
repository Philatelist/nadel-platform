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
