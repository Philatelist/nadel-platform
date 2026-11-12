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

describe('buildPushView for every code', () => {
  const cases = [
    ['INVOICES_ISSUED', '/account#my-invoices', 'Новий рахунок за листопад', 'New invoice for November'],
    ['PAYMENT_RECORDED', '/account#my-charges', 'Платіж зараховано', 'Payment recorded'],
    ['READING_APPROVED', '/account#my-meters', 'Показ лічильника прийнято', 'Meter reading accepted'],
    ['READING_REJECTED', '/account#my-meters', 'Показ лічильника відхилено', 'Meter reading rejected'],
    ['ANNOUNCEMENT', '/account', 'Нове оголошення від правління', 'New announcement from the board'],
    ['READINGS_TO_APPROVE', '/readings/approvals', 'Нові покази на перевірку', 'New readings to review'],
  ] as const;

  it.each(cases)('%s in uk and en', (code, url, uk, en) => {
    const params = { month: '2026-11' };
    const u = buildPushView(msg({ code, params, url, lang: 'uk' }));
    expect(u).toMatchObject({ title: 'Наділ', body: uk, url, tag: code });
    const e = buildPushView(msg({ code, params, url, lang: 'en' }));
    expect(e).toMatchObject({ title: 'Nadel', body: en, url, tag: code });
  });

  it('passes the url through safeUrl', () => {
    expect(buildPushView(msg({ code: 'ANNOUNCEMENT', url: 'https://evil.example' })).url).toBe('/');
  });

  it('never leaks extra params into the text', () => {
    const view = buildPushView(
      msg({
        code: 'PAYMENT_RECORDED',
        params: { amount: '1250.50', plot: '17', name: 'Іваненко' },
        lang: 'uk',
      }),
    );
    expect(view.body).toBe('Платіж зараховано');
    expect(JSON.stringify(view)).not.toMatch(/1250|Іваненко|17/);
  });

  it('formats the month in both languages', () => {
    expect(monthName('2026-03', 'uk')).toBe('березень');
    expect(monthName('2026-03', 'en')).toBe('March');
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
