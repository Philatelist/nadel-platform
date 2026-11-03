import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DeliveryPage, DeliveryRow } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { DeliveriesPage } from './DeliveriesPage';

const listParams = vi.fn();
const listOptions = vi.fn();
const retryMutate = vi.fn();
const printMutate = vi.fn();
let page: DeliveryPage;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListDeliveries: (params: unknown, options: unknown) => {
    listParams(params);
    listOptions(options);
    return { data: page, error: null, isPending: false, isError: false, refetch: vi.fn() };
  },
  useRetryDelivery: () => ({ mutate: retryMutate, isPending: false }),
  useMarkMonthPrinted: () => ({ mutate: printMutate, isPending: false }),
}));

const base: DeliveryRow = {
  id: 'd1',
  kind: 'INVOICE',
  month: '2026-10',
  plotNumber: '9',
  memberName: 'Іваненко Іван',
  channel: 'EMAIL',
  status: 'SENT',
  attempts: 1,
  sentAt: '2026-10-29T09:00:00Z',
};
const rows: DeliveryRow[] = [
  base,
  { ...base, id: 'd2', channel: 'TELEGRAM', status: 'FAILED', failureCode: 'TELEGRAM_BLOCKED' },
  { ...base, id: 'd3', channel: 'EMAIL', status: 'QUEUED', sentAt: undefined, attempts: 0 },
  { ...base, id: 'd4', channel: 'PAPER', status: 'TO_PRINT', note: 'VIBER_UNSUPPORTED', sentAt: undefined },
];
const summary = [
  { channel: 'EMAIL', status: 'SENT', count: 4 },
  { channel: 'TELEGRAM', status: 'FAILED', count: 2 },
  { channel: 'PAPER', status: 'TO_PRINT', count: 6 },
  { channel: 'PAPER', status: 'PRINTED', count: 1 },
] as const;

function renderPage(route = '/deliveries?month=2026-10') {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <DeliveriesPage />
    </QueryClientProvider>,
    { route },
  );
}

describe('DeliveriesPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    for (const m of [listParams, listOptions, retryMutate, printMutate]) m.mockReset();
    page = { items: rows, page: 0, size: 50, totalItems: 4, totalPages: 1, summary: [...summary] };
  });

  it('shows the counts per channel and the rows with reason and note', () => {
    renderPage();
    const counts = screen.getByRole('table', { name: 'Month summary' });
    const paper = within(counts).getByRole('row', { name: /Paper/ });
    expect(within(paper).getByText('6 / 1')).toBeTruthy();
    expect(within(counts).getByRole('row', { name: /Email/ }).textContent).toContain('4');
    expect(screen.getByText('The bot is blocked')).toBeTruthy();
    expect(screen.getByText('Viber is not supported yet')).toBeTruthy();
    expect(listParams).toHaveBeenCalledWith(expect.objectContaining({ month: '2026-10', page: 0 }));
  });

  it('puts a changed filter into the query', async () => {
    renderPage();
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    await userEvent.selectOptions(screen.getByLabelText('Channel', { selector: 'select' }), 'TELEGRAM');
    await waitFor(() =>
      expect(listParams).toHaveBeenLastCalledWith(
        expect.objectContaining({ month: '2026-10', status: 'FAILED', channel: 'TELEGRAM' }),
      ),
    );
  });

  it('offers "Send again" only on sent or failed e-mail and Telegram rows', async () => {
    renderPage();
    const buttons = screen.getAllByRole('button', { name: 'Send again' });
    expect(buttons).toHaveLength(2);
    await userEvent.click(buttons[1] as HTMLElement);
    expect(retryMutate).toHaveBeenCalledWith({ id: 'd2' });
  });

  it('marks the month printed only after a confirmation, and only with paper waiting', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Printed' }));
    expect(printMutate).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Printed' }));
    expect(printMutate).toHaveBeenCalledWith({ month: '2026-10' });
  });

  it('has no "Printed" button when no paper waits', () => {
    page = { ...page, summary: [{ channel: 'PAPER', status: 'PRINTED', count: 3 }] };
    renderPage();
    expect(screen.queryByRole('button', { name: 'Printed' })).toBeNull();
  });

  it('polls every 3 s while a row is queued and stops otherwise', () => {
    renderPage();
    const options = listOptions.mock.calls[0]?.[0] as {
      query: { refetchInterval: (q: { state: { data: DeliveryPage } }) => number | false };
    };
    const poll = options.query.refetchInterval;
    expect(poll({ state: { data: page } })).toBe(3000);
    expect(poll({ state: { data: { ...page, items: [base] } } })).toBe(false);
  });
});
