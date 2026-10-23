import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { preview } from './recalcFixtures';
import { RecalculationPreview } from './RecalculationPreview';

function renderPreview(over: Partial<React.ComponentProps<typeof RecalculationPreview>> = {}) {
  const props = {
    preview: preview(),
    reason: '',
    onReasonChange: vi.fn(),
    booking: false,
    onBook: vi.fn(),
    onBack: vi.fn(),
    ...over,
  };
  renderWithApp(<RecalculationPreview {...props} />);
  return props;
}

describe('RecalculationPreview', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('groups the lines by plot, then month, and shows the totals as the server sent them', () => {
    renderPreview({ reason: 'Fixed' });
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual(expect.arrayContaining(['Plot 12', 'Plot 14']));
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Sep 2026',
      'Oct 2026',
      'Sep 2026',
    ]);
    expect(screen.getByText('Plots with changes: 2')).toBeInTheDocument();
    const totals = screen.getByText('Difference by charge type').closest('section') as HTMLElement;
    expect(totals).toHaveTextContent('−₴10.00');
    expect(totals).toHaveTextContent('−₴7.00');
    expect(within(totals).getByText('Total').closest('div')).toHaveTextContent('−₴17.00');
  });

  it('shows the booked amount with the adjustments so far, the expected amount and the reasons', () => {
    renderPreview();
    const tables = screen.getAllByRole('table');
    const last = tables[tables.length - 1] as HTMLElement;
    expect(last).toHaveTextContent('₴100.00');
    expect(last).toHaveTextContent('+ −₴3.00');
    expect(last).toHaveTextContent('₴90.00');
    expect(tables[0]).toHaveTextContent('Adjustment');
    expect(tables[0]).toHaveTextContent('A tariff version changed');
  });

  it('says there are no changes and offers no booking when the plan has no lines', () => {
    renderPreview({
      preview: preview({ lines: [], totals: [], total: '0.00', plotCount: 0 }),
      reason: 'Some reason',
    });
    expect(screen.getByText('No changes')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book the recalculation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back' })).toBeEnabled();
  });

  it('requires a reason of at most 500 characters before booking', () => {
    const { rerender } = renderWithApp(
      <RecalculationPreview
        preview={preview()}
        reason="  "
        onReasonChange={vi.fn()}
        booking={false}
        onBook={vi.fn()}
        onBack={vi.fn()}
      />,
    );
    expect(screen.getByTestId('reason-counter')).toHaveTextContent('0 / 500');
    rerender(
      <RecalculationPreview
        preview={preview()}
        reason={'x'.repeat(501)}
        onReasonChange={vi.fn()}
        booking={false}
        onBook={vi.fn()}
        onBack={vi.fn()}
      />,
    );
    expect(screen.getByText('The reason is too long (at most 500 characters)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Book the recalculation' })).toBeDisabled();
  });

  it('shows "Enter a reason" instead of booking when the reason is empty', async () => {
    const props = renderPreview({ reason: '  ' });
    expect(screen.queryByText('Enter a reason')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(screen.getByText('Enter a reason')).toBeInTheDocument();
    expect(props.onBook).not.toHaveBeenCalled();
  });

  it('books and reports typing through the callbacks', async () => {
    const props = renderPreview({ reason: 'Fixed the rate' });
    await userEvent.type(screen.getByRole('textbox'), 'a');
    expect(props.onReasonChange).toHaveBeenCalledWith('Fixed the ratea');
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(props.onBook).toHaveBeenCalledTimes(1);
  });
});
