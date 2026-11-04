import { ApiError } from '@nadel/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { BankReconciliationPage } from './BankReconciliationPage';

const useGetBankReconciliation = vi.fn();
const enterBankStatement = vi.fn();
const bookBankDifference = vi.fn();
const confirmBankReconciliation = vi.fn();
const reverseAccountMovement = vi.fn();
let permissions: string[] = [];

vi.mock('../me', () => ({
  useCan: (p: string) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetBankReconciliation: (...args: unknown[]) => useGetBankReconciliation(...args),
  enterBankStatement: (...args: unknown[]) => enterBankStatement(...args),
  bookBankDifference: (...args: unknown[]) => bookBankDifference(...args),
  confirmBankReconciliation: (...args: unknown[]) => confirmBankReconciliation(...args),
  reverseAccountMovement: (...args: unknown[]) => reverseAccountMovement(...args),
}));

const figures = (opening: string, incoming: string, outgoing: string, closing: string) => ({
  opening,
  incoming,
  outgoing,
  closing,
});

const unbalanced = {
  month: '2026-10',
  accountId: 'a-bank',
  status: 'NOT_RECONCILED',
  statement: figures('100.00', '50.00', '20.00', '130.00'),
  books: figures('100.00', '50.00', '20.00', '130.00'),
  differences: figures('0.00', '0.00', '0.00', '-10.00'),
  balanced: false,
  movements: [
    {
      id: 'm1',
      accountId: 'a-bank',
      kind: 'BANK_DIFFERENCE',
      date: '2026-10-31',
      amount: '-10.00',
      reason: 'комісія банку',
      reversed: false,
      beforeOpening: false,
      source: { type: 'BANK_RECONCILIATION', id: 'r1' },
    },
  ],
  movementsTotal: 1,
};

function renderPage() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/cashbook/bank/:month" element={<BankReconciliationPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/cashbook/bank/2026-10' },
  );
}

describe('BankReconciliationPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    for (const fn of [
      enterBankStatement,
      bookBankDifference,
      confirmBankReconciliation,
      reverseAccountMovement,
    ]) {
      fn.mockReset();
    }
    useGetBankReconciliation.mockReturnValue(queryResult(unbalanced));
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER', 'CASHBOOK_APPROVE'];
  });

  it('shows the server figures and keeps «Звірено» disabled while the server says unbalanced', () => {
    renderPage();
    expect(screen.getByText('Не звірено')).toBeInTheDocument();
    const closing = screen.getByTestId('figure-closing');
    expect(closing.textContent).toMatch(/−10,00/);
    expect(screen.getByRole('button', { name: 'Звірено' })).toBeDisabled();
  });

  it('enables «Звірено» when balanced and confirms', async () => {
    useGetBankReconciliation.mockReturnValue(queryResult({ ...unbalanced, balanced: true }));
    confirmBankReconciliation.mockResolvedValue({ ...unbalanced, status: 'RECONCILED' });
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Звірено' }));
    await waitFor(() => expect(confirmBankReconciliation).toHaveBeenCalledWith('2026-10'));
  });

  it('sends the four figures as typed, signed ones included', async () => {
    enterBankStatement.mockResolvedValue(unbalanced);
    const user = userEvent.setup();
    renderPage();
    const opening = screen.getByLabelText('Залишок на початок місяця');
    await user.clear(opening);
    await user.type(opening, '-5,5');
    await user.click(screen.getByRole('button', { name: 'Зберегти виписку' }));
    await waitFor(() =>
      expect(enterBankStatement).toHaveBeenCalledWith(
        '2026-10',
        figures('-5.50', '50.00', '20.00', '130.00'),
      ),
    );
  });

  it('explains an inconsistent statement with the figures it sent', async () => {
    enterBankStatement.mockRejectedValue(
      new ApiError(400, {
        code: 'STATEMENT_INCONSISTENT',
        errors: [{ field: 'closing', code: 'STATEMENT_INCONSISTENT' }],
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Зберегти виписку' }));
    expect(
      await screen.findByText(/Цифри виписки не сходяться: .*100,00.*\+.*50,00.*−.*20,00.*≠.*130,00/),
    ).toBeInTheDocument();
  });

  it('shows what reopened the month', () => {
    useGetBankReconciliation.mockReturnValue(
      queryResult({
        ...unbalanced,
        status: 'NEEDS_RECHECK',
        reopened: {
          movementId: 'm9',
          kind: 'EXPENSE',
          date: '2026-10-20',
          amount: '-30.00',
          at: '2026-11-02T08:00:00Z',
        },
      }),
    );
    renderPage();
    expect(screen.getByText('Потребує повторної звірки')).toBeInTheDocument();
    expect(
      screen.getByText(/Місяць потрібно звірити знову: .* внесено витрату від 20\.10\.2026/),
    ).toBeInTheDocument();
  });

  it('warns when the statement in or out differs, yet «Звірено» works while the closing difference is 0', () => {
    useGetBankReconciliation.mockReturnValue(
      queryResult({
        ...unbalanced,
        differences: figures('0.00', '5.00', '0.00', '0.00'),
        balanced: true,
      }),
    );
    renderPage();
    expect(
      screen.getByText(/Надходження або списання за випискою відрізняються від книги/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Звірено' })).toBeEnabled();
  });

  it('shows no flow warning when every difference is zero', () => {
    useGetBankReconciliation.mockReturnValue(
      queryResult({ ...unbalanced, differences: figures('0.00', '0.00', '0.00', '0.00'), balanced: true }),
    );
    renderPage();
    expect(screen.queryByText(/Надходження або списання за випискою/)).not.toBeInTheDocument();
  });

  it('books a bank difference with a reason for those who approve', async () => {
    bookBankDifference.mockResolvedValue(unbalanced);
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Банківська різниця' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Сума/), '-10');
    await user.type(within(dialog).getByLabelText('Причина'), 'комісія банку');
    await user.click(within(dialog).getByRole('button', { name: 'Провести' }));
    await waitFor(() =>
      expect(bookBankDifference).toHaveBeenCalledWith('2026-10', {
        amount: '-10.00',
        reason: 'комісія банку',
      }),
    );
  });

  it('hides «Банківська різниця» and reversing without CASHBOOK_APPROVE', () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_ENTER'];
    renderPage();
    expect(screen.queryByRole('button', { name: 'Банківська різниця' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Сторнувати' })).not.toBeInTheDocument();
  });

  it('reverses a booked bank difference line with a reason', async () => {
    reverseAccountMovement.mockResolvedValue({});
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Причина'), 'помилка');
    await user.click(within(dialog).getByRole('button', { name: 'Сторнувати' }));
    await waitFor(() =>
      expect(reverseAccountMovement).toHaveBeenCalledWith('a-bank', 'm1', { reason: 'помилка' }),
    );
  });

  it('is read-only for a reconciled month', () => {
    useGetBankReconciliation.mockReturnValue(
      queryResult({ ...unbalanced, status: 'RECONCILED', balanced: true, reconciledByName: 'Іваненко І.' }),
    );
    renderPage();
    expect(screen.getByText('Звірено', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Зберегти виписку' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Банківська різниця' })).not.toBeInTheDocument();
  });
});
