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
