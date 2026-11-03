import { formatMoney, formatSignedMoney } from '@nadel/shared';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { CashbookPage } from './CashbookPage';

const useListMoneyAccounts = vi.fn();
const useListAccountMovements = vi.fn();
const setOpeningBalance = vi.fn();
let permissions: string[] = [];

vi.mock('../me', () => ({
  useCan: (p: string) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMoneyAccounts: (...args: unknown[]) => useListMoneyAccounts(...args),
  useListAccountMovements: (...args: unknown[]) => useListAccountMovements(...args),
  setOpeningBalance: (...args: unknown[]) => setOpeningBalance(...args),
}));

const cash = {
  id: 'a-cash',
  kind: 'CASH',
  asOf: '2026-11-03',
  balance: '-120.50',
  negative: true,
  opening: { amount: '500.00', date: '2026-10-01' },
};
const bank = { id: 'a-bank', kind: 'BANK', asOf: '2026-11-03', negative: false };

/** Formatted amounts use no-break spaces; compare them as plain text. */
const plain = (text: string | null) => (text ?? '').replace(/\s/g, ' ');
const amountText = (expected: string) => (content: string) => plain(content) === plain(expected);

function renderPage() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <CashbookPage />
    </QueryClientProvider>,
  );
}

function movement(overrides: Record<string, unknown>) {
  return {
    id: 'm1',
    date: '2026-11-02',
    kind: 'MEMBER_PAYMENT',
    amount: '1250.50',
    bookingMonth: '2026-11',
    beforeOpening: false,
    source: { type: 'PAYMENT', id: 'pay1' },
    plotId: 'p1',
    receiptNumber: 7,
    reversed: false,
    createdAt: '2026-11-02T10:00:00Z',
    ...overrides,
  };
}

function page(items: unknown[]) {
  return queryResult({
    accountId: 'a-cash',
    kind: 'CASH',
    from: '2026-11-01',
    to: '2026-11-03',
    openingBalance: '500.00',
    closingBalance: '-120.50',
    negative: true,
    items,
    page: 0,
    size: 100,
    totalItems: items.length,
    totalPages: 1,
  });
}

describe('CashbookPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    permissions = ['CASHBOOK_VIEW'];
    setOpeningBalance.mockReset();
    useListMoneyAccounts.mockReset();
    useListMoneyAccounts.mockReturnValue(queryResult([cash, bank]));
    useListAccountMovements.mockReset();
    useListAccountMovements.mockReturnValue(page([movement({})]));
  });

  it('shows the server balances through the formatter and the prompt for an account without opening', () => {
    renderPage();
    expect(plain(screen.getByTestId('balance-CASH').textContent)).toBe(plain(formatMoney('-120.50', 'uk')));
    expect(screen.getByText('Каса')).toBeInTheDocument();
    expect(screen.getByText('Банківський рахунок')).toBeInTheDocument();
    expect(screen.getByText('Введіть вхідний залишок')).toBeInTheDocument();
    expect(screen.queryByTestId('balance-BANK')).not.toBeInTheDocument();
  });

  it('warns only for the account whose balance is negative', () => {
    renderPage();
    expect(screen.getByText('Залишок каси від’ємний — проведіть перерахунок каси')).toBeInTheDocument();
    expect(screen.queryByText('Залишок банку від’ємний')).not.toBeInTheDocument();
  });

  it('warns about a negative bank balance with the bank text', () => {
    useListMoneyAccounts.mockReturnValue(
      queryResult([
        { ...cash, negative: false, balance: '10.00' },
        { ...bank, negative: true, balance: '-1.00' },
      ]),
    );
    renderPage();
    expect(screen.getByText('Залишок банку від’ємний')).toBeInTheDocument();
    expect(screen.queryByText(/Залишок каси від’ємний/)).not.toBeInTheDocument();
  });

  it('shows the opening balance form only with CASHBOOK_APPROVE, and only for an account without opening', () => {
    renderPage();
    expect(screen.queryByRole('button', { name: 'Зберегти вхідний залишок' })).not.toBeInTheDocument();
  });

  it('offers the opening balance form to CASHBOOK_APPROVE for the account without opening only', () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_APPROVE'];
    renderPage();
    expect(screen.getAllByRole('button', { name: 'Зберегти вхідний залишок' })).toHaveLength(1);
  });

  it('sends the typed amount normalised and the date', async () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_APPROVE'];
    setOpeningBalance.mockResolvedValue(bank);
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Сума'), '1 500,5');
    await user.click(screen.getByRole('button', { name: 'Зберегти вхідний залишок' }));
    await waitFor(() => expect(setOpeningBalance).toHaveBeenCalledTimes(1));
    expect(setOpeningBalance.mock.calls[0]?.[0]).toBe('a-bank');
    expect(setOpeningBalance.mock.calls[0]?.[1]).toMatchObject({ amount: '1500.50' });
  });

  it('shows journal amounts as the server sent them, kinds by name, and marks lines before the opening', () => {
    useListAccountMovements.mockReturnValue(
      page([
        movement({}),
        movement({
          id: 'm2',
          kind: 'HANDOVER_OUT',
          amount: '-300.00',
          source: { type: 'HANDOVER', id: 'h1' },
          receiptNumber: undefined,
          plotId: undefined,
        }),
        movement({
          id: 'm3',
          kind: 'EXPENSE',
          amount: '-20.00',
          beforeOpening: true,
          receiptNumber: undefined,
          plotId: undefined,
        }),
      ]),
    );
    renderPage();
    const table = screen.getAllByRole('table')[0] as HTMLElement;
    expect(within(table).getByText(amountText(formatSignedMoney('1250.50', 'uk')))).toBeInTheDocument();
    expect(within(table).getByText(amountText(formatSignedMoney('-300.00', 'uk')))).toBeInTheDocument();
    expect(within(table).getByText('Платіж члена')).toBeInTheDocument();
    expect(within(table).getByText('Здача в банк')).toBeInTheDocument();
    expect(within(table).getByText('Витрата')).toBeInTheDocument();
    expect(within(table).getByText('до вхідного залишку')).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: '№ 7' })).toHaveAttribute('href', '/payments/pay1');
  });
});
