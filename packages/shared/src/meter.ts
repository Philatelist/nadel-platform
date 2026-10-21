/** A meter value is an exact non-negative decimal string with at most three decimals ("1250.5", "0.125"). */
export type MeterValueString = string;

const METER_INPUT = /^(\d{1,11})(?:[.,](\d{0,3}))?$/;
const METER_VALUE = /^(\d+)(?:\.(\d{1,3}))?$/;
const NBSP = ' ';

/**
 * Parses what a person typed (a "," or "." separator, spaces as group separators allowed) into a canonical decimal
 * string ("1 250,5" -> "1250.5"). Works on the text only: no floating point. Returns `undefined` for empty, negative
 * or invalid input and for more than three decimals (a value is never rounded).
 */
export function parseMeterValue(input: string): MeterValueString | undefined {
  const match = METER_INPUT.exec(input.replace(/[\s ]/g, ''));
  if (!match) return undefined;
  const whole = (match[1] ?? '').replace(/^0+(?=\d)/, '');
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  return fraction ? `${whole}.${fraction}` : whole;
}

/**
 * Formats a server meter value for display with the locale's separators, keeping every decimal the server sent:
 * "1234.500" -> uk "1 234,500", en "1,234.500". Text only, no arithmetic.
 */
export function formatMeterValue(value: MeterValueString, locale: string): string {
  const match = METER_VALUE.exec(value);
  if (!match) throw new Error(`Invalid meter value: ${value}`);
  const whole = match[1] ?? '0';
  const english = locale.toLowerCase().startsWith('en');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, english ? ',' : NBSP);
  return match[2] ? `${grouped}${english ? '.' : ','}${match[2]}` : grouped;
}

export type MeterUnit = 'KWH' | 'M3';
