import { ApiError, type PaymentDetails } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ReversePaymentDialog } from './ReversePaymentDialog';

const reversePayment = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  reversePayment: (...args: unknown[]) => reversePayment(...args),
}));

const payment = { id: 'pay1' } as PaymentDetails;
const onDone = vi.fn();

function open() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ReversePaymentDialog payment={payment} onDone={onDone} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('ReversePaymentDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    reversePayment.mockReset();
    onDone.mockReset();
  });

  it('requires a reason and does not call the server without one', async () => {
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    expect(await screen.findByText('Вкажіть причину')).toBeInTheDocument();
    expect(reversePayment).not.toHaveBeenCalled();
  });

  it('reverses with the trimmed reason and reports done', async () => {
    reversePayment.mockResolvedValue({});
    const user = userEvent.setup();
    open();
    await user.type(screen.getByRole('textbox', { name: /Причина/ }), '  Помилка касира ');
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(reversePayment).toHaveBeenCalledWith('pay1', { reason: 'Помилка касира' });
  });

  it('says so when the payment has already been reversed', async () => {
    reversePayment.mockRejectedValue(new ApiError(409, { code: 'PAYMENT_ALREADY_REVERSED' }));
    const user = userEvent.setup();
    open();
    await user.type(screen.getByRole('textbox', { name: /Причина/ }), 'Помилка');
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    expect(await screen.findByText('Платіж уже сторновано')).toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
  });
});
