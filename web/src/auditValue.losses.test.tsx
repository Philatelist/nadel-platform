import { messages } from '@nadel/shared';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { auditRecordLabel, auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>) =>
  render(<MemoryRouter>{node}</MemoryRouter>).container.textContent;

describe('auditValue for losses runs', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('formats the range, month, count, node count and the signed total', () => {
    expect(text(auditValue('LOSSES_RUN', 'from', '2026-10-01', uk, 'uk'))).toBe('01.10.2026');
    expect(text(auditValue('LOSSES_RUN', 'to', '2026-10-15', en, 'en'))).toBe('15 Oct 2026');
    expect(text(auditValue('LOSSES_RUN', 'billingMonth', '2026-10', en, 'en'))).toBe('Oct 2026');
    expect(text(auditValue('LOSSES_RUN', 'nodes', '3', uk, 'uk'))).toBe('3');
    expect(text(auditValue('LOSSES_RUN', 'count', '12', en, 'en'))).toBe('12');
    expect(text(auditValue('LOSSES_RUN', 'total', '432.00', uk, 'uk'))).toMatch(/^432,00\s₴$/);
    expect(text(auditValue('LOSSES_RUN', 'total', '-432.00', en, 'en'))).toMatch(/^−₴432\.00$/);
  });

  it('shows the reversal reason as user content', () => {
    const { container } = render(<>{auditValue('LOSSES_RUN', 'reason', 'Wrong meter', en, 'en')}</>);
    expect(container.querySelector('[data-user-content]')?.textContent).toBe('Wrong meter');
  });

  it('shows the values of a retried losses node: kind, reason, days, outcome and a link to the run', () => {
    expect(text(auditValue('METER', 'kind', 'LOSSES_NODE', en, 'en'))).toBe('Losses of a meter tree node');
    expect(text(auditValue('METER', 'reason', 'UNKNOWN_NODE_VALUE', en, 'en'))).toBe(
      'A meter value of the period is unknown',
    );
    expect(text(auditValue('METER', 'outcome', 'RESOLVED', uk, 'uk'))).toBe('Нараховано');
    const run = render(<MemoryRouter>{auditValue('METER', 'runId', 'r9', en, 'en')}</MemoryRouter>).getByRole(
      'link',
      { name: 'Open the run' },
    );
    expect(run).toHaveAttribute('href', '/charges/losses?run=r9');
  });

  it('labels a run record by its range in the locale', () => {
    expect(text(auditRecordLabel('LOSSES_RUN', '2026-10-01/2026-10-15', uk, 'uk'))).toBe(
      '01.10.2026 – 15.10.2026',
    );
    expect(text(auditRecordLabel('LOSSES_RUN', '2026-10-01/2026-10-15', en, 'en'))).toBe(
      '1 Oct 2026 – 15 Oct 2026',
    );
    expect(text(auditRecordLabel('LOSSES_RUN', 'odd', en, 'en'))).toBe('odd');
  });

  it('has texts for the actions and the entity type in both languages', () => {
    expect(uk.audit.action.LOSSES_RUN_BOOKED).toBeTruthy();
    expect(en.audit.action.LOSSES_RUN_REVERSED).toBe('Losses run reversed');
    expect(en.audit.entityType.LOSSES_RUN).toBe('Losses run');
    expect(uk.audit.entityType.LOSSES_RUN).toBe('Розрахунок втрат');
  });
});
