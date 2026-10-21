import { describe, expect, it } from 'vitest';
import { en } from './i18n/en';
import { uk } from './i18n/uk';
import { formatMeterValue, parseMeterValue } from './meter';

describe('parseMeterValue', () => {
  it.each([
    ['1250.5', '1250.5'],
    ['1 250,500', '1250.5'],
    ['007', '7'],
    ['0,125', '0.125'],
    ['12.', '12'],
    ['0', '0'],
  ])('parses %s as %s', (input, expected) => {
    expect(parseMeterValue(input)).toBe(expected);
  });

  it.each(['', ' ', '-1', '1.2345', 'abc', '1..2', '.5', '1e3'])('rejects %j', (input) => {
    expect(parseMeterValue(input)).toBeUndefined();
  });

  it('keeps exactness where a JS number would not', () => {
    expect(parseMeterValue('99999999999.999')).toBe('99999999999.999');
  });
});

describe('formatMeterValue', () => {
  it('formats with the locale separators and keeps every decimal', () => {
    expect(formatMeterValue('1234567.500', 'uk')).toBe('1 234 567,500');
    expect(formatMeterValue('1234567.500', 'en')).toBe('1,234,567.500');
    expect(formatMeterValue('12', 'uk')).toBe('12');
    expect(formatMeterValue('0.125', 'en')).toBe('0.125');
  });

  it('rejects text that is not a meter value', () => {
    expect(() => formatMeterValue('-1', 'en')).toThrow();
    expect(() => formatMeterValue('1.2345', 'en')).toThrow();
  });
});

describe('meter unit labels', () => {
  it('come from the catalogs', () => {
    expect(uk.units.kwh).toBe('кВт·год');
    expect(uk.units.m3).toBe('м³');
    expect(en.units.kwh).toBe('kWh');
    expect(en.units.m3).toBe('m³');
  });
});
