import { messages } from '@nadel/shared';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { auditRecordLabel, auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>) =>
  render(<MemoryRouter>{node}</MemoryRouter>).container.textContent;

describe('auditValue for recalculations', () => {
  const uk = messages('uk');
  const en = messages('en');

  it('shows the cause from the catalog', () => {
    expect(text(auditValue('RECALCULATION', 'cause', 'MANUAL', uk, 'uk'))).toBe('Перерахунок вручну');
    expect(text(auditValue('RECALCULATION', 'cause', 'MANUAL', en, 'en'))).toBe('Manual recalculation');
  });

  it('formats the months, the total and the count', () => {
    expect(text(auditValue('RECALCULATION', 'from', '2026-09', en, 'en'))).toBe('2026-09');
    expect(text(auditValue('RECALCULATION', 'total', '-17.00', uk, 'uk'))).toMatch(/^−17,00\s₴$/);
    expect(text(auditValue('RECALCULATION', 'count', '3', en, 'en'))).toBe('3');
  });

  it('names base charge types from the catalog and shows plots and the reason as user content', () => {
    expect(text(auditValue('RECALCULATION', 'chargeTypes', 'base:MEMBERSHIP, Water pump', en, 'en'))).toBe(
      `${en.chargeTypes.defaultNames.MEMBERSHIP}, Water pump`,
    );
    const plots = render(<>{auditValue('RECALCULATION', 'plots', '12, 14', uk, 'uk')}</>);
    expect(plots.container.querySelector('[data-user-content]')?.textContent).toBe('12, 14');
  });

  it('labels the record by its months', () => {
    expect(text(auditRecordLabel('RECALCULATION', '2026-09/2026-10', en, 'en'))).toBe('Sep 2026 – Oct 2026');
    expect(text(auditRecordLabel('RECALCULATION', '2026-10/2026-10', en, 'en'))).toBe('Oct 2026');
  });
});
