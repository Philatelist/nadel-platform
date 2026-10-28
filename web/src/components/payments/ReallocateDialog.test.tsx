import { ApiError, type PaymentDetails } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ReallocateDialog } from './ReallocateDialog';
import { exceedingProposalOf, proposalOf } from './allocationDialogs.fixtures';

const reallocatePayment = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  reallocatePayment: (...args: unknown[]) => reallocatePayment(...args),
}));

const payment = { id: 'pay1' } as PaymentDetails;
const onDone = vi.fn();
const wait = { timeout: 3000 };
const membershipField = { name: /Сума до зарахування: Членський внесок/ };

function open() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ReallocateDialog payment={payment} onDone={onDone} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('ReallocateDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    reallocatePayment.mockReset();
    onDone.mockReset();
    reallocatePayment.mockResolvedValue(proposalOf('300.00', '0.00', 'd1'));
  });

  it('shows the current allocations and the proposal', async () => {
    open();
    expect(await screen.findByRole('textbox', membershipField, wait)).toHaveValue('300.00');
    expect(screen.getByText('Зараз зараховано')).toBeInTheDocument();
    expect(screen.getByRole('listitem')).toHaveTextContent(/Членський внесок.*Січень 2026.*300,00/);
    expect(reallocatePayment).toHaveBeenCalledWith('pay1', {}, { preview: true });
  });

  it('previews again with explicit lines when a line is edited, then saves that preview', async () => {
    const user = userEvent.setup();
    open();
    const field = await screen.findByRole('textbox', membershipField, wait);
    reallocatePayment.mockResolvedValue(proposalOf('100.00', '200.00', 'd2'));
    await user.clear(field);
    await user.type(field, '100');
    await waitFor(
      () =>
        expect(reallocatePayment).toHaveBeenLastCalledWith(
          'pay1',
          { lines: [{ chargeId: 'c1', amount: '100.00' }] },
          { preview: true },
        ),
      wait,
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeEnabled(), wait);
    await user.click(screen.getByRole('button', { name: 'Перерозподілити' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(reallocatePayment).toHaveBeenLastCalledWith(
      'pay1',
      { lines: [{ chargeId: 'c1', amount: '100.00' }], digest: 'd2' },
      { preview: false },
    );
  });

  it('previews again at once when the digest is stale', async () => {
    const user = userEvent.setup();
    open();
    await screen.findByRole('textbox', membershipField, wait);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeEnabled());
    reallocatePayment.mockImplementation((_id: string, body: unknown, params: { preview: boolean }) =>
      params.preview
        ? Promise.resolve(proposalOf('250.00', '50.00', 'd3'))
        : Promise.reject(new ApiError(409, { code: 'PREVIEW_OUTDATED' })),
    );
    await user.click(screen.getByRole('button', { name: 'Перерозподілити' }));
    expect(
      await screen.findByText('Дані змінилися після перегляду. Перегляньте знову перед збереженням.'),
    ).toBeInTheDocument();
    expect(await screen.findByRole('textbox', membershipField)).toHaveValue('250.00');
    expect(onDone).not.toHaveBeenCalled();
  });

  it('keeps the table while an intermediate value exceeds the charge, shows the problem, and saves once corrected', async () => {
    const user = userEvent.setup();
    open();
    const field = await screen.findByRole('textbox', membershipField, wait);
    reallocatePayment.mockResolvedValue(exceedingProposalOf('999.00'));
    await user.clear(field);
    await user.type(field, '999');

    // the table is not replaced; the problem stands next to its line, the server's totals are shown, Save is off
    expect(await screen.findByText('Сума перевищує залишок за нарахуванням', {}, wait)).toBeInTheDocument();
    expect(screen.getByRole('textbox', membershipField)).toHaveValue('999');
    const totals = screen.getByRole('rowheader', { name: 'Розподілено' }).closest('tr');
    expect(totals).toHaveTextContent('999,00 ₴ з 300,00 ₴');
    expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeDisabled();
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);

    reallocatePayment.mockResolvedValue(proposalOf('100.00', '200.00', 'd2'));
    await user.clear(screen.getByRole('textbox', membershipField));
    await user.type(screen.getByRole('textbox', membershipField), '100');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeEnabled(), wait);
    expect(screen.queryByText('Сума перевищує залишок за нарахуванням')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Перерозподілити' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('shows an unparseable input as a problem of its line, not as an endless calculation', async () => {
    const user = userEvent.setup();
    open();
    const field = await screen.findByRole('textbox', membershipField, wait);
    await user.clear(field);
    await user.type(field, 'abc');

    expect(await screen.findByText('Вкажіть суму числом, наприклад 250,00', {}, wait)).toBeInTheDocument();
    expect(screen.getByRole('textbox', membershipField)).toHaveValue('abc');
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);
    expect(screen.queryByText('Розраховуємо розподіл…')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeDisabled();
    expect(reallocatePayment).toHaveBeenCalledTimes(1);
  });

  it('shows a server error above the table, never instead of it', async () => {
    const user = userEvent.setup();
    open();
    const field = await screen.findByRole('textbox', membershipField, wait);
    reallocatePayment.mockRejectedValue(new ApiError(500, { code: 'INTERNAL_ERROR' }));
    await user.clear(field);
    await user.type(field, '100');

    await waitFor(() => expect(reallocatePayment).toHaveBeenCalledTimes(2), wait);
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument(), wait);
    expect(screen.getByRole('textbox', membershipField)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeDisabled();
  });
});
