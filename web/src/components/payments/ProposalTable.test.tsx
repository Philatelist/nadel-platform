import { screen, within } from '@testing-library/react';
import type { PaymentPartProposal } from '@nadel/api-client';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ProposalTable } from './ProposalTable';

const type = { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false };

const part = {
  plotId: 'p7',
  plotNumber: '7',
  amount: '1000.00',
  debt: '600.00',
  lines: [
    {
      chargeId: 'c1',
      kind: 'REGULAR',
      chargeType: type,
      billingMonth: '2026-01',
      serviceFrom: '2026-01-01',
      serviceTo: '2026-01-28',
      accruedOn: '2026-01-01',
      open: '600.00',
      proposed: '600.00',
    },
  ],
  resultLines: [],
  allocatedTotal: '600.00',
  automaticTotal: '0.00',
  prepayment: '0.00',
} as PaymentPartProposal;

function renderTable() {
  return renderWithApp(
    <ProposalTable
      part={part}
      values={null}
      touched={new Set()}
      lineErrors={{}}
      amountError={{}}
      onEdit={vi.fn()}
      onAdd={vi.fn()}
    />,
  );
}

describe('ProposalTable layouts', () => {
  it('is one markup for both layouts: the allocation line turns into a card below the sm breakpoint', () => {
    renderTable();
    const input = screen.getByRole('textbox', { name: /^Amount to allocate:/ });
    expect(input).toHaveValue('600.00');
    // the input fills the card and is a fixed-width cell of the table from sm up
    expect(input.className).toContain('w-full');
    expect(input.className).toContain('sm:w-32');
    const row = input.closest('tr')!;
    expect(row.className).toContain('block');
    expect(row.className).toContain('sm:table-row');
    // each card line is captioned by its column title, without a second copy of the text in the DOM
    const labelled = Array.from(row.querySelectorAll('td')).map((td) => td.getAttribute('data-label'));
    expect(labelled).toHaveLength(4);
    expect(labelled.every((l) => l !== null && l !== '')).toBe(true);
  });

  it('keeps the total as a block of its own that may wrap on a phone', () => {
    renderTable();
    const total = screen.getByRole('rowheader', { name: /^Allocated/ });
    expect(within(total).getAllByText(/600/).length).toBeGreaterThan(0);
    expect(total.className).toContain('block');
    expect(total.className).toContain('sm:whitespace-nowrap');
    expect(total.className).not.toMatch(/(^|\s)whitespace-nowrap/);
  });
});
