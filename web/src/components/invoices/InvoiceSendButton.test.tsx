import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InvoiceRow } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { InvoiceSendButton } from './InvoiceSendButton';

const sendInvoice = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => {
  const { useState } = await import('react');
  return {
    ...(await importOriginal<typeof import('@nadel/api-client')>()),
    // The generated hook calls its own copy of `sendInvoice`, so the hook is replaced with a small stateful fake.
    useSendInvoice: (options?: { mutation?: { onSettled?: () => void } }) => {
      const [data, setData] = useState<unknown>();
      return {
        data,
        isPending: false,
        isError: false,
        error: null,
        mutate: ({ id }: { id: string }) =>
          void sendInvoice(id).then((result: unknown) => {
            setData(result);
            options?.mutation?.onSettled?.();
          }),
      };
    },
  };
});

const row: InvoiceRow = {
  id: 'inv1',
  plotId: 'p1',
  plotNumber: '9',
  month: '2026-10',
  number: '2026-10/9',
  version: 1,
  due: '1250.50',
  issuedAt: '2026-10-28T09:00:00Z',
  latest: true,
};

describe('InvoiceSendButton', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    sendInvoice.mockReset();
    sendInvoice.mockResolvedValue({ counts: { telegram: 1, email: 1, paper: 0 }, queued: 2 });
  });

  it('asks first, then sends and shows the created counts', async () => {
    renderWithApp(
      <QueryClientProvider client={new QueryClient()}>
        <InvoiceSendButton row={row} />
      </QueryClientProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(screen.getByRole('dialog', { name: 'Send invoice 2026-10/9?' })).toBeTruthy();
    expect(sendInvoice).not.toHaveBeenCalled();

    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(sendInvoice.mock.calls[0]?.[0]).toBe('inv1'));
    expect(await screen.findByText('Created: Telegram 1, email 1, paper 0.')).toBeTruthy();
  });
});
