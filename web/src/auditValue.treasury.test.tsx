import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditRecordLabel, auditValue } from './auditValue';

describe('audit of a money account', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('names the account by its kind code, not the raw code', () => {
    expect(auditRecordLabel('MONEY_ACCOUNT', 'CASH', uk, 'uk')).toBe('Каса');
    expect(auditRecordLabel('MONEY_ACCOUNT', 'BANK', en, 'en')).toBe('Bank account');
    expect(auditValue('MONEY_ACCOUNT', 'kind', 'BANK', uk, 'uk')).toBe('Банківський рахунок');
  });
});

describe('audit of treasury entries', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('labels the fields and renders their codes and amounts through the catalog', () => {
    expect(uk.audit.fields.counterparty).toBe('Контрагент');
    expect(en.audit.fields.closing).toBe('Closing balance');
    expect(auditValue('EXPENSE', 'account', 'BANK', uk, 'uk')).toBe('Банківський рахунок');
    expect(auditValue('MONEY_ACCOUNT', 'kind', 'CASH', en, 'en')).toBe('Cash');
    expect(auditValue('BANK_RECONCILIATION', 'status', 'NEEDS_RECHECK', uk, 'uk')).toBe(
      'Потребує повторної звірки',
    );
    expect(auditValue('CASH_COUNT', 'status', 'UNRESOLVED', uk, 'uk')).toBe('Неврегульована');
    expect(auditValue('BANK_RECONCILIATION', 'kind', 'EXPENSE', uk, 'uk')).toBe('Витрата');
    expect(String(auditValue('BANK_RECONCILIATION', 'closing', '130.00', uk, 'uk'))).toMatch(/130,00/);
  });
});

describe('audit of payments, invoices and announcements', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('labels every field the server writes for them', () => {
    for (const field of [
      'receivedOn',
      'receiptNumber',
      'bankReference',
      'lines',
      'due',
      'audience',
      'streets',
      'channels',
      'telegram',
      'paper',
    ] as const) {
      expect(uk.audit.fields[field], field).toBeTruthy();
      expect(en.audit.fields[field], field).toBeTruthy();
    }
    expect(uk.audit.fields.receivedOn).toBe('Дата надходження');
  });

  it('renders the day, the amount due, the audience and the channels through the catalog', () => {
    expect(auditValue('PAYMENT', 'receivedOn', '2026-11-05', en, 'en')).toBe('5 Nov 2026');
    expect(String(auditValue('INVOICE', 'due', '250.00', uk, 'uk'))).toMatch(/250,00/);
    expect(auditValue('ANNOUNCEMENT', 'audience', 'ALL', uk, 'uk')).toBe('Усі члени');
    expect(auditValue('ANNOUNCEMENT', 'channels', 'TELEGRAM, PAPER', uk, 'uk')).toBe('Telegram, Папір');
  });
});
