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
const useListExpenseCategories = vi.fn();
const createHandover = vi.fn();
const createOtherIncome = vi.fn();
const reverseHandover = vi.fn();
const reverseOtherIncome = vi.fn();
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
  useListExpenseCategories: (...args: unknown[]) => useListExpenseCategories(...args),
  createHandover: (...args: unknown[]) => createHandover(...args),
  createOtherIncome: (...args: unknown[]) => createOtherIncome(...args),
  reverseHandover: (...args: unknown[]) => reverseHandover(...args),
  reverseOtherIncome: (...args: unknown[]) => reverseOtherIncome(...args),
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

  it('shows the last count and the unresolved difference with a link to the count', () => {
    useListMoneyAccounts.mockReturnValue(
      queryResult([
        {
          ...cash,
          lastCount: {
            id: 'c1',
            countedAt: '2026-11-03T10:00:00Z',
            countedOn: '2026-11-03',
            counted: '480.00',
            bookBalance: '500.00',
            difference: '-20.00',
            status: 'UNRESOLVED',
            createdAt: '2026-11-03T10:00:00Z',
          },
          unresolvedDifference: '-20.00',
        },
        bank,
      ]),
    );
    renderPage();
    expect(plain(screen.getByTestId('last-count-CASH').textContent)).toContain('різниця');
    const link = screen.getByRole('link', { name: /Неврегульована різниця/ });
    expect(link).toHaveAttribute('href', '/cashbook/counts/c1');
    expect(plain(link.textContent)).toContain('20,00');
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

describe('CashbookPage handovers and other income', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    permissions = ['CASHBOOK_VIEW'];
    reverseHandover.mockReset();
    reverseOtherIncome.mockReset();
    createHandover.mockReset();
    createOtherIncome.mockReset();
    useListMoneyAccounts.mockReturnValue(queryResult([cash, bank]));
    useListExpenseCategories.mockReturnValue(
      queryResult([
        { id: 'c1', kind: 'INCOME', systemCode: 'BANK_INTEREST', archived: false },
        { id: 'c2', kind: 'INCOME', systemCode: 'GRANTS', archived: true },
      ]),
    );
    useListAccountMovements.mockReturnValue(
      page([
        movement({
          id: 'h1',
          kind: 'HANDOVER_OUT',
          amount: '-300.00',
          source: { type: 'HANDOVER', id: 'hid' },
          receiptNumber: undefined,
          plotId: undefined,
        }),
        movement({
          id: 'e1',
          kind: 'EXPENSE',
          amount: '-20.00',
          source: { type: 'EXPENSE', id: 'eid' },
          receiptNumber: undefined,
          plotId: undefined,
        }),
      ]),
    );
  });

  it('shows the entry buttons only with CASHBOOK_ENTER', () => {
    renderPage();
    expect(screen.queryByRole('button', { name: 'Здати в банк' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Інший прихід' })).not.toBeInTheDocument();
  });

  it('records a handover with the typed values', async () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
    createHandover.mockResolvedValue({});
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Здати в банк' }));
    await user.type(screen.getByLabelText('Сума'), '1 500,5');
    await user.type(screen.getByLabelText('Номер квитанції банку'), 'КВ-123');
    await user.click(screen.getByRole('button', { name: 'Записати здачу' }));
    await waitFor(() => expect(createHandover).toHaveBeenCalledTimes(1));
    expect(createHandover.mock.calls[0]?.[0]).toMatchObject({ amount: '1500.50', documentNumber: 'КВ-123' });
  });

  it('requires the slip number for a handover', async () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Здати в банк' }));
    await user.type(screen.getByLabelText('Сума'), '10');
    await user.click(screen.getByRole('button', { name: 'Записати здачу' }));
    expect(screen.getByText('Вкажіть номер квитанції.')).toBeInTheDocument();
    expect(createHandover).not.toHaveBeenCalled();
  });

  it('records other income with a non-archived category', async () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
    createOtherIncome.mockResolvedValue({});
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Інший прихід' }));
    expect(screen.queryByRole('option', { name: 'Гранти' })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Сума'), '40');
    await user.selectOptions(screen.getByLabelText('Стаття доходу'), 'c1');
    await user.click(screen.getByRole('button', { name: 'Записати прихід' }));
    await waitFor(() => expect(createOtherIncome).toHaveBeenCalledTimes(1));
    expect(createOtherIncome.mock.calls[0]?.[0]).toMatchObject({
      account: 'BANK',
      amount: '40.00',
      categoryId: 'c1',
    });
  });

  it('offers reversal of a handover only with CASHBOOK_APPROVE, and sends the source id', async () => {
    permissions = ['CASHBOOK_VIEW'];
    const { unmount } = renderPage();
    expect(screen.queryByRole('button', { name: 'Сторнувати' })).not.toBeInTheDocument();
    unmount();

    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_APPROVE'];
    reverseHandover.mockResolvedValue({});
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getAllByRole('button', { name: 'Сторнувати' })[0] as HTMLElement);
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Сторнувати' }));
    expect(screen.getByText('Вкажіть причину сторнування.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Причина'), 'помилка');
    await user.click(within(dialog).getByRole('button', { name: 'Сторнувати' }));
    await waitFor(() => expect(reverseHandover).toHaveBeenCalledWith('hid', { reason: 'помилка' }));
  });

  it('links an expense row to its card', () => {
    renderPage();
    expect(screen.getAllByRole('link', { name: 'Відкрити витрату' })[0]).toHaveAttribute(
      'href',
      '/cashbook/expenses/eid',
    );
  });
});
