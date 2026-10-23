import { render } from '@testing-library/react';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>) => render(<>{node}</>).container.textContent;

describe('auditValue for water connections', () => {
  it('translates the cooperative supply token and shows a meter name as user content', () => {
    expect(text(auditValue('PLOT', 'source', 'base:COOPERATIVE_SUPPLY', messages('uk'), 'uk'))).toBe(
      'Водопровід кооперативу',
    );
    expect(text(auditValue('PLOT', 'source', 'base:COOPERATIVE_SUPPLY', messages('en'), 'en'))).toBe(
      'Cooperative water supply',
    );
    const meter = render(<>{auditValue('PLOT', 'source', 'Колодязь 1', messages('uk'), 'uk')}</>);
    expect(meter.container.querySelector('[data-user-content]')?.textContent).toBe('Колодязь 1');
  });

  it('formats the first and last connected day', () => {
    expect(text(auditValue('PLOT', 'from', '2026-05-01', messages('en'), 'en'))).toBe('1 May 2026');
    expect(text(auditValue('PLOT', 'until', '2026-09-30', messages('en'), 'en'))).toBe('30 Sep 2026');
  });
});
