import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { SendInvoicesDialog } from './SendInvoicesDialog';

const sendMonthInvoices = vi.fn();
const navigate = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  sendMonthInvoices: (...args: unknown[]) => sendMonthInvoices(...args),
}));
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigate,
}));

function renderDialog() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <SendInvoicesDialog month="2026-10" onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('SendInvoicesDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    sendMonthInvoices.mockReset();
    navigate.mockReset();
    sendMonthInvoices.mockImplementation((_month: string, params: { preview: boolean }, body) =>
      Promise.resolve(
        params.preview
          ? {
              counts: body.includeNothingDue
                ? { telegram: 5, email: 12, paper: 9 }
                : { telegram: 3, email: 10, paper: 7 },
              queued: 0,
            }
          : { counts: { telegram: 3, email: 10, paper: 7 }, queued: 13 },
      ),
    );
  });

  it('previews the counts per channel, previews again with the switch and sends', async () => {
    renderDialog();
    expect(await screen.findByText('Telegram: 3')).toBeTruthy();
    expect(screen.getByText('Email: 10')).toBeTruthy();
    expect(screen.getByText('Paper (to print): 7')).toBeTruthy();
    expect(sendMonthInvoices).toHaveBeenCalledWith(
      '2026-10',
      { preview: true },
      { includeNothingDue: false },
    );

    await userEvent.click(screen.getByRole('checkbox', { name: 'Also send invoices with nothing due' }));
    expect(await screen.findByText('Email: 12')).toBeTruthy();
    expect(sendMonthInvoices).toHaveBeenCalledWith('2026-10', { preview: true }, { includeNothingDue: true });

    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() =>
      expect(sendMonthInvoices).toHaveBeenCalledWith(
        '2026-10',
        { preview: false },
        { includeNothingDue: true },
      ),
    );
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/deliveries?month=2026-10'));
  });

  it('cannot send when nothing new would be created', async () => {
    sendMonthInvoices.mockResolvedValue({ counts: { telegram: 0, email: 0, paper: 0 }, queued: 0 });
    renderDialog();
    expect(await screen.findByText(/Nothing new to send/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
