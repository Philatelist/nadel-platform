import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { CashCountNewPage, CashCountPage } from './CashCountPage';

const enterCashCount = vi.fn();
const bookCashCountDifference = vi.fn();
const getCashCountActPdf = vi.fn();
const useGetCashCount = vi.fn();
let permissions: string[] = [];

vi.mock('../me', () => ({
  useCan: (p: string) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  enterCashCount: (...args: unknown[]) => enterCashCount(...args),
  bookCashCountDifference: (...args: unknown[]) => bookCashCountDifference(...args),
  getCashCountActPdf: (...args: unknown[]) => getCashCountActPdf(...args),
  useGetCashCount: (...args: unknown[]) => useGetCashCount(...args),
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: vi.fn() }));

const count = {
  id: 'c1',
  preview: false,
  accountId: 'a-cash',
  countedAt: '2026-11-03T10:00:00Z',
  countedOn: '2026-11-03',
  counted: '480.00',
  bookBalance: '500.00',
  difference: '-20.00',
  status: 'UNRESOLVED',
  explanation: 'не знайшли чек',
  movementsSince: [],
  movementsSinceTotal: 0,
};

function renderNew() {
  const client = new QueryClient();
  renderWithApp(
    <QueryClientProvider client={client}>
      <Routes>
        <Route path="/cashbook/counts/new" element={<CashCountNewPage />} />
        <Route path="/cashbook/counts/:id" element={<div>card</div>} />
      </Routes>
    </QueryClientProvider>,
    { route: '/cashbook/counts/new' },
  );
}

function renderCard() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/cashbook/counts/:id" element={<CashCountPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/cashbook/counts/c1' },
  );
}

describe('CashCountNewPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    enterCashCount.mockReset();
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
  });

  it('shows the server difference, asks for an explanation and saves', async () => {
    enterCashCount.mockImplementation((_body: unknown, params?: { preview?: boolean }) =>
      Promise.resolve(params?.preview ? { ...count, preview: true, id: undefined } : count),
    );
    const user = userEvent.setup();
    renderNew();
    await user.type(screen.getByLabelText('Фактично в касі'), '480');
    const preview = await screen.findByTestId('count-preview');
    expect(within(preview).getByText(/−20,00/)).toBeInTheDocument();
    expect(enterCashCount).toHaveBeenCalledWith(expect.objectContaining({ counted: '480.00' }), {
      preview: true,
    });
    await user.click(screen.getByRole('button', { name: 'Записати перерахунок' }));
    expect(await screen.findByText('Вкажіть пояснення різниці.')).toBeInTheDocument();
    expect(enterCashCount).not.toHaveBeenCalledWith(expect.anything(), undefined);
    await user.type(screen.getByLabelText('Пояснення різниці'), 'не знайшли чек');
    await user.click(screen.getByRole('button', { name: 'Записати перерахунок' }));
    await waitFor(() =>
      expect(enterCashCount).toHaveBeenCalledWith(
        expect.objectContaining({ counted: '480.00', explanation: 'не знайшли чек' }),
      ),
    );
    expect(await screen.findByText('card')).toBeInTheDocument();
  });
});

describe('CashCountPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    bookCashCountDifference.mockReset();
    useGetCashCount.mockReturnValue(queryResult(count));
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER', 'CASHBOOK_APPROVE'];
  });

  it('books the difference with a reason for those who approve', async () => {
    bookCashCountDifference.mockResolvedValue({ ...count, status: 'BOOKED' });
    const user = userEvent.setup();
    renderCard();
    expect(screen.getByText(/Неврегульована різниця/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Оформити різницю' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Причина'), 'витрата без чека');
    await user.click(within(dialog).getByRole('button', { name: 'Оформити' }));
    await waitFor(() =>
      expect(bookCashCountDifference).toHaveBeenCalledWith('c1', { reason: 'витрата без чека' }),
    );
  });

  it('hides the booking button without CASHBOOK_APPROVE', () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
    renderCard();
    expect(screen.queryByRole('button', { name: 'Оформити різницю' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Акт перерахунку каси' })).toBeInTheDocument();
  });
});
