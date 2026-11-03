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
