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
    expect(parseMeterValue(input, 'uk')).toBe(expected);
  });

  it.each(['', ' ', '-1', '1.2345', 'abc', '1..2', '.5', '1e3'])('rejects %j', (input) => {
    expect(parseMeterValue(input, 'uk')).toBeUndefined();
  });

  it('keeps exactness where a JS number would not', () => {
    expect(parseMeterValue('99999999999.999', 'uk')).toBe('99999999999.999');
  });
});

describe('parseMeterValue in en', () => {
  it.each([
    ['8,205', '8205'],
    ['8,205.5', '8205.5'],
    ['1,234,567.5', '1234567.5'],
    ['8205.500', '8205.5'],
    ['0.125', '0.125'],
  ])('parses %s as %s', (input, expected) => {
    expect(parseMeterValue(input, 'en')).toBe(expected);
  });

  it.each(['8,20', '1,2345', '1,,234', ',5', '8,205,', '1.2345', '-1', ''])('rejects %j', (input) => {
    expect(parseMeterValue(input, 'en')).toBeUndefined();
  });
});

describe('formatMeterValue', () => {
  it('formats with the locale separators and trims trailing zeros', () => {
    expect(formatMeterValue('1234567.500', 'uk')).toBe('1 234 567,5');
    expect(formatMeterValue('1234567.500', 'en')).toBe('1,234,567.5');
    expect(formatMeterValue('8205.000', 'uk')).toBe('8\u00a0205');
    expect(formatMeterValue('1250.500', 'uk')).toBe('1\u00a0250,5');
    expect(formatMeterValue('1250.500', 'en')).toBe('1,250.5');
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
