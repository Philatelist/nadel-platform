import { ApiError } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { AllocatePrepaymentDialog } from './AllocatePrepaymentDialog';
import { proposalOf } from './allocationDialogs.fixtures';

const allocatePrepayment = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  allocatePrepayment: (...args: unknown[]) => allocatePrepayment(...args),
}));

const onDone = vi.fn();
const wait = { timeout: 3000 };

function open() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <AllocatePrepaymentDialog plotId="p9" onDone={onDone} />
    </QueryClientProvider>,
  );
}

describe('AllocatePrepaymentDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    allocatePrepayment.mockReset();
    onDone.mockReset();
  });

  it('shows the proposal and saves it with the digest', async () => {
    allocatePrepayment.mockResolvedValue(proposalOf('300.00', '0.00', 'd1'));
    const user = userEvent.setup();
    open();
    expect(
      await screen.findByRole('textbox', { name: /Сума до зарахування: Членський внесок/ }, wait),
    ).toHaveValue('300.00');
    expect(allocatePrepayment).toHaveBeenCalledWith('p9', {}, { preview: true });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Розподілити' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Розподілити' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(allocatePrepayment).toHaveBeenLastCalledWith('p9', { digest: 'd1' }, { preview: false });
  });

  it('says there is nothing to allocate', async () => {
    allocatePrepayment.mockRejectedValue(new ApiError(409, { code: 'NOTHING_TO_ALLOCATE' }));
    open();
    expect(await screen.findByText('Немає авансу для розподілу', undefined, wait)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Розподілити' })).toBeDisabled();
  });
});
