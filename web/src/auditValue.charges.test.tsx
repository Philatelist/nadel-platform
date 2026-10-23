import { render } from '@testing-library/react';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>) => render(<>{node}</>).container.textContent;

describe('auditValue for charges', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('formats the amount, month and days from their codes', () => {
    expect(text(auditValue('CHARGE', 'amount', '-25.00', uk, 'uk'))).toBe('−25,00 ₴');
    expect(text(auditValue('CHARGE', 'billingMonth', '2026-10', uk, 'uk'))).toBe('10.2026');
    expect(text(auditValue('CHARGE', 'billingMonth', '2026-10', en, 'en'))).toBe('Oct 2026');
    expect(text(auditValue('CHARGE', 'serviceFrom', '2026-09-23', uk, 'uk'))).toBe('23.09.2026');
    expect(text(auditValue('CHARGE', 'date', '2026-10-01', en, 'en'))).toBe('1 Oct 2026');
  });

  it('formats the from and to days of a retried interval', () => {
    expect(text(auditValue('BILLING_MONTH', 'from', '2026-09-23', uk, 'uk'))).toBe('23.09.2026');
    expect(text(auditValue('BILLING_MONTH', 'to', '2026-10-04', en, 'en'))).toBe('4 Oct 2026');
  });

  it('shows the charge type by its base token and the reason as user content', () => {
    expect(text(auditValue('CHARGE', 'chargeType', 'base:COMMON_CONSUMERS', uk, 'uk'))).toBe(
      'Спільні споживачі',
    );
    const reason = render(<>{auditValue('CHARGE', 'reason', 'Помилка', uk, 'uk')}</>);
    expect(reason.container.querySelector('[data-user-content]')?.textContent).toBe('Помилка');
  });
});

describe('auditValue for billing months and settings', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('shows the billing month status by its catalog label', () => {
    expect(text(auditValue('BILLING_MONTH', 'status', 'OPEN', uk, 'uk'))).toBe('Відкритий');
    expect(text(auditValue('BILLING_MONTH', 'status', 'CLOSED', uk, 'uk'))).toBe('Закритий');
    expect(text(auditValue('BILLING_MONTH', 'status', 'CLOSED', en, 'en'))).toBe('Closed');
  });

  it('shows the losses and split settings by catalog labels', () => {
    expect(text(auditValue('SETTINGS', 'lossesMode', 'FIXED_PERCENT', uk, 'uk'))).toBe('Фіксований відсоток');
    expect(text(auditValue('SETTINGS', 'lossesMode', 'PROPORTIONAL', en, 'en'))).toBe('Proportional');
    expect(text(auditValue('SETTINGS', 'commonConsumerDefaultSplit', 'EQUAL_PER_PLOT', uk, 'uk'))).toBe(
      'Порівну на ділянку',
    );
    expect(text(auditValue('SETTINGS', 'commonConsumerDefaultSplit', 'NOT_CHARGED', en, 'en'))).toBe(
      'Not charged',
    );
    expect(text(auditValue('SETTINGS', 'lossesPercent', '5.5', en, 'en'))).toBe('5.5 %');
  });
});
