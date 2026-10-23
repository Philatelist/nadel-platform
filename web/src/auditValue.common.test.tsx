import { messages } from '@nadel/shared';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { auditRecordLabel, auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>) =>
  render(<MemoryRouter>{node}</MemoryRouter>).container.textContent;

describe('auditValue for common consumers', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('formats the range, count and total of a common consumers run', () => {
    expect(text(auditValue('COMMON_CONSUMER_RUN', 'from', '2026-10-01', uk, 'uk'))).toBe('01.10.2026');
    expect(text(auditValue('COMMON_CONSUMER_RUN', 'billingMonth', '2026-10', en, 'en'))).toBe('Oct 2026');
    expect(text(auditValue('COMMON_CONSUMER_RUN', 'count', '4', en, 'en'))).toBe('4');
    expect(text(auditValue('COMMON_CONSUMER_RUN', 'total', '4320.00', uk, 'uk'))).toMatch(/^4\s?320,00\s₴$/);
  });

  it('shows the consumer names and the reason as user content', () => {
    const consumers = render(
      <>{auditValue('COMMON_CONSUMER_RUN', 'consumers', 'Well pump, Gate', en, 'en')}</>,
    );
    expect(consumers.container.querySelector('[data-user-content]')?.textContent).toBe('Well pump, Gate');
    const reason = render(<>{auditValue('COMMON_CONSUMER_RUN', 'reason', 'Wrong meter', en, 'en')}</>);
    expect(reason.container.querySelector('[data-user-content]')?.textContent).toBe('Wrong meter');
  });

  it('shows the split by its text and an absent split as the default', () => {
    expect(text(auditValue('METER', 'split', 'SERVICE_USERS', en, 'en'))).toBe('Among service users');
    expect(text(auditValue('METER', 'split', 'EQUAL_PER_PLOT', uk, 'uk'))).toBe('Порівну на ділянку');
    expect(text(auditValue('METER', 'split', undefined, en, 'en'))).toBe('By default');
    expect(text(auditValue('METER', 'split', undefined, uk, 'uk'))).toBe('За замовчуванням');
  });

  it('shows the service plots as the stored list of numbers', () => {
    const { container } = render(<>{auditValue('METER', 'servicePlots', '1, 2, 10', en, 'en')}</>);
    expect(container.textContent).toBe('1, 2, 10');
  });

  it('labels a run by its range', () => {
    const label = render(<>{auditRecordLabel('COMMON_CONSUMER_RUN', '2026-10-01/2026-10-15', en, 'en')}</>);
    expect(label.container.textContent).toBe('1 Oct 2026 – 15 Oct 2026');
  });
});
