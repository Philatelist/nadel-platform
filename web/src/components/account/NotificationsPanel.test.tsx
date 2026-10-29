import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MyNotifications } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { NotificationsPanel } from './NotificationsPanel';

let data: MyNotifications;
const save = vi.fn();
const connect = vi.fn();
const disconnect = vi.fn();
let connectOptions: { onSuccess: (o: { deepLink: string; expiresAt: string }) => void };
let disconnectOptions: { onSuccess: () => void };

vi.mock('../../me', () => ({ useMe: () => ({ memberId: 'm1' }) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMyNotifications: () => ({ data, isSuccess: true, isPending: false, isError: false }),
  useUpdateMyNotifications: () => ({ mutate: save, isPending: false }),
  useCreateMyTelegramLink: (o: { mutation: typeof connectOptions }) => {
    connectOptions = o.mutation;
    return { mutate: connect, isPending: false };
  },
  useDeleteMyTelegramLink: (o: { mutation: typeof disconnectOptions }) => {
    disconnectOptions = o.mutation;
    return { mutate: disconnect, isPending: false };
  },
}));

function renderPanel() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <NotificationsPanel />
    </QueryClientProvider>,
  );
}

describe('NotificationsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    data = {
      channel: 'EMAIL',
      available: ['EMAIL', 'TELEGRAM', 'PAPER'],
      telegramLinked: false,
      botAvailable: true,
    };
  });

  it('lists only the available channels with the current one selected', () => {
    data = { ...data, available: ['EMAIL', 'PAPER'], botAvailable: false };
    renderPanel();
    expect(screen.getAllByRole('radio')).toHaveLength(2);
    expect(screen.getByRole('radio', { name: 'Email' })).toBeChecked();
    expect(screen.queryByRole('radio', { name: 'Telegram' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Connect Telegram' })).toBeNull();
  });

  it('sends the chosen channel', async () => {
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: 'Paper' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(save).toHaveBeenCalledWith({ data: { channel: 'PAPER' } });
  });

  it('notes that Viber is not supported', () => {
    data = { ...data, channel: 'VIBER' };
    renderPanel();
    expect(screen.getByText(/Viber is not supported yet/)).toBeInTheDocument();
  });

  it('opens the deep link on connect', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    renderPanel();
    expect(screen.getByText(/30 minutes/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Connect Telegram' }));
    expect(connect).toHaveBeenCalled();
    connectOptions.onSuccess({ deepLink: 'https://t.me/bot?start=x', expiresAt: '2026-01-01T00:00:00Z' });
    expect(open).toHaveBeenCalledWith('https://t.me/bot?start=x', '_blank', 'noopener');
  });

  it('shows the linked state and disconnects after confirmation', async () => {
    data = { ...data, telegramLinked: true };
    renderPanel();
    expect(screen.getByText('Telegram connected')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Telegram' }));
    expect(disconnect).not.toHaveBeenCalled();
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(
      Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent === 'Disconnect Telegram')!,
    );
    await waitFor(() => expect(disconnect).toHaveBeenCalled());
    expect(disconnectOptions).toBeDefined();
  });
});
