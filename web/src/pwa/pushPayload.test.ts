import { describe, expect, it } from 'vitest';
import { buildPushView, monthName, safeUrl } from './pushPayload';

const msg = (o: object) => JSON.stringify({ v: 1, ...o });

describe('buildPushView', () => {
  it('names the month of a new invoice in the chosen language', () => {
    const uk = buildPushView(
      msg({ code: 'INVOICES_ISSUED', params: { month: '2026-11' }, url: '/invoices', lang: 'uk' }),
    );
    expect(uk.title).toBe('Наділ');
    expect(uk.body).toBe(`Новий рахунок за ${monthName('2026-11', 'uk')}`);
    expect(uk.body).toContain('листопад');
    const en = buildPushView(msg({ code: 'INVOICES_ISSUED', params: { month: '2026-11' }, lang: 'en' }));
    expect(en.body).toBe('New invoice for November');
    expect(en.tag).toBe('INVOICES_ISSUED');
  });

  it.each([
    ['PAYMENT_RECORDED', 'Платіж зараховано'],
    ['READING_APPROVED', 'Показ лічильника прийнято'],
    ['READING_REJECTED', 'Показ лічильника відхилено'],
    ['ANNOUNCEMENT', 'Нове оголошення від правління'],
    ['READINGS_TO_APPROVE', 'Нові покази на перевірку'],
  ])('shows %s', (code, text) => {
    expect(buildPushView(msg({ code, lang: 'uk' })).body).toBe(text);
  });

  it('falls back to a generic notification for an unknown code or broken data', () => {
    expect(buildPushView(msg({ code: 'NEW_THING', lang: 'uk' })).body).toBe('Наділ');
    expect(buildPushView('not json').body).toBe('Наділ');
    expect(buildPushView(undefined).url).toBe('/');
  });
});

describe('safeUrl', () => {
  it('accepts only paths of this app', () => {
    expect(safeUrl('/invoices?x=1')).toBe('/invoices?x=1');
    expect(safeUrl('//evil.example')).toBe('/');
    expect(safeUrl('https://evil.example')).toBe('/');
    expect(safeUrl('/\\evil.example')).toBe('/');
    expect(safeUrl('invoices')).toBe('/');
    expect(safeUrl(42)).toBe('/');
  });
});
