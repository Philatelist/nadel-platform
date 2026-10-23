import { describe, expect, it } from 'vitest';
import { calculationLine, chargePeriodNote } from './charge';
import { messages } from './i18n';
import { normalizeMoneyInput } from './money';

const NBSP = '\u00a0';
const uk = messages('uk');
const en = messages('en');

describe('chargePeriodNote', () => {
  it('names the service period and the billing month when the period leaves the month', () => {
    const charge = { billingMonth: '2026-10', serviceFrom: '2026-09-23', serviceTo: '2026-10-04' };
    expect(chargePeriodNote(charge, uk, 'uk')).toBe('за 23.09.2026–04.10.2026, нараховано у жовтні 2026');
    expect(chargePeriodNote(charge, en, 'en')).toBe('for 23 Sep 2026–4 Oct 2026, charged in October 2026');
  });

  it('is absent when the whole service period is inside the billing month', () => {
    const charge = { billingMonth: '2026-10', serviceFrom: '2026-10-01', serviceTo: '2026-10-31' };
    expect(chargePeriodNote(charge, uk, 'uk')).toBeUndefined();
  });

  it('shows a single day once', () => {
    const charge = { billingMonth: '2026-10', serviceFrom: '2026-09-30', serviceTo: '2026-09-30' };
    expect(chargePeriodNote(charge, uk, 'uk')).toBe('за 30.09.2026, нараховано у жовтні 2026');
  });
});

describe('calculationLine', () => {
  const part = {
    quantity: '93.300',
    unit: 'KWH' as const,
    rate: '4.3200',
    days: 12,
    from: '2026-09-23',
    to: '2026-10-04',
  };

  it('composes the electricity example from server strings', () => {
    expect(calculationLine(part, '403.06', uk, 'uk')).toBe(
      `93,3 кВт·год × 4,32${NBSP}₴ = 403,06${NBSP}₴, 23.09–04.10 (12 днів)`,
    );
  });

  it('omits the amount for a part of several and handles English', () => {
    expect(calculationLine({ ...part, days: 1, to: '2026-09-23' }, undefined, en, 'en')).toBe(
      '93.3 kWh × ₴4.32, 23 Sep (1 day)',
    );
  });

  it('shows the rate alone when the part has no quantity', () => {
    const fixed = { rate: '50.0000', days: 31, from: '2026-10-01', to: '2026-10-31' };
    expect(calculationLine(fixed, '50.00', uk, 'uk')).toBe(
      `50,00${NBSP}₴ = 50,00${NBSP}₴, 01.10–31.10 (31 день)`,
    );
  });
});

describe('normalizeMoneyInput', () => {
  it('turns typed text into the API form', () => {
    expect(normalizeMoneyInput('1250,5')).toBe('1250.50');
    expect(normalizeMoneyInput(' 1 250.50 ')).toBe('1250.50');
    expect(normalizeMoneyInput('007')).toBe('7.00');
  });

  it('rejects text that is not an amount', () => {
    for (const bad of ['', 'abc', '-5', '1.234', '1,2,3']) expect(normalizeMoneyInput(bad)).toBeUndefined();
  });
});
