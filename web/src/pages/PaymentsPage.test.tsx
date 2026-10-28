import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { PaymentsPage } from './PaymentsPage';

const useListPayments = vi.fn();
const useListPlots = vi.fn();
const months = [
  { month: '2026-10', status: 'OPEN' },
  { month: '2026-09', status: 'CLOSED' },
];
let canEnter = true;

vi.mock('../me', () => ({
  useCan: (p: string) => (p === 'PAYMENTS_ENTER' ? canEnter : true),
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPayments: (...args: unknown[]) => useListPayments(...args),
  useGetPlot: () => ({ data: { id: 'p9', number: '9' } }),
  useListPlots: (...args: unknown[]) => useListPlots(...args),
  useListBillingMonths: () => queryResult(months),
}));

const cash = {
  id: 'pay1',
  groupId: 'g1',
  receiptNumber: 42,
  receivedOn: '2026-10-21',
  billingMonth: '2026-10',
  plotId: 'p9',
  plotNumber: '9',
  payer: { memberId: 'm1', shortName: 'Мельник І.' },
  source: 'CASH',
  amount: '700.00',
  allocated: '600.00',
  prepayment: '100.00',
  reversed: false,
};
const bankReversed = {
  ...cash,
  id: 'pay2',
  receiptNumber: undefined,
  bankReference: 'BK-77',
  source: 'BANK',
  reversed: true,
};

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname + location.search}</output>;
}

function renderList(route = '/payments') {
  renderWithApp(
    <>
      <Routes>
        <Route path="payments" element={<PaymentsPage />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

describe('PaymentsPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    canEnter = true;
    useListPlots.mockReset();
    useListPlots.mockReturnValue({
      data: {
        items: [
          { id: 'p1', number: '1', kind: 'PLOT', archived: false, street: 'Садова' },
          { id: 'p2', number: '2', kind: 'PLOT', archived: false },
        ],
      },
    });
    useListPayments.mockReset();
    useListPayments.mockReturnValue(
      queryResult({ items: [cash, bankReversed], page: 0, size: 20, totalItems: 2, totalPages: 1 }),
    );
  });

  it('lists receipt or bank reference, plot link, source and the reversed badge', () => {
    renderList();
    const table = screen.getByRole('table');
    expect(within(table).getByRole('link', { name: '№ 42' })).toHaveAttribute('href', '/payments/pay1');
    expect(within(table).getByRole('link', { name: 'BK-77' })).toHaveAttribute('href', '/payments/pay2');
    expect(within(table).getByText('Сторновано')).toBeInTheDocument();
    expect(within(table).getAllByRole('link', { name: '9' })[0]).toHaveAttribute('href', '/plots/p9');
    expect(screen.getByRole('link', { name: 'Внести платіж' })).toHaveAttribute('href', '/payments/new');
  });

  it('marks the payments that belong to a split group', () => {
    useListPayments.mockReturnValue(
      queryResult({
        items: [
          { ...cash, groupSize: 2 },
          { ...bankReversed, groupSize: 1 },
        ],
        page: 0,
        size: 20,
        totalItems: 2,
        totalPages: 1,
      }),
    );
    renderList();
    expect(screen.getAllByText('Одна оплата, ділянок: 2')).toHaveLength(1);
  });

  it('hides "Внести платіж" without the permission to enter payments', () => {
    canEnter = false;
    renderList();
    expect(screen.queryByRole('link', { name: 'Внести платіж' })).not.toBeInTheDocument();
  });

  it('reads the filters from the address and keeps a changed filter in it', async () => {
    const user = userEvent.setup();
    renderList('/payments?source=CASH&from=2026-10-01&month=2026-10&plotId=p9&page=2');
    expect(useListPayments).toHaveBeenLastCalledWith(
      {
        from: '2026-10-01',
        to: undefined,
        month: '2026-10',
        source: 'CASH',
        plotId: 'p9',
        page: 2,
        size: 20,
      },
      expect.anything(),
    );
    expect(screen.getByLabelText('Джерело')).toHaveValue('CASH');

    await user.selectOptions(screen.getByLabelText('Джерело'), 'BANK');
    const where = new URL(screen.getByTestId('where').textContent, 'http://x');
    expect(where.searchParams.get('source')).toBe('BANK');
    expect(where.searchParams.get('from')).toBe('2026-10-01');
    expect(where.searchParams.get('plotId')).toBe('p9');
    expect(where.searchParams.has('page')).toBe(false);
  });

  it('ignores a malformed filter in the address', () => {
    renderList('/payments?source=CHEQUE&from=yesterday');
    expect(useListPayments).toHaveBeenLastCalledWith(
      expect.objectContaining({ source: undefined, from: undefined }),
      expect.anything(),
    );
  });

  it('offers the billing months, newest first, with an empty option, and keeps the choice in the address', async () => {
    const user = userEvent.setup();
    renderList('/payments?month=2026-10');
    const select = screen.getByLabelText('Місяць обліку');
    expect(select.tagName).toBe('SELECT');
    expect(select).toHaveValue('2026-10');
    expect(
      within(select)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Усі місяці', 'Жовтень 2026 · Відкритий', 'Вересень 2026 · Закритий']);

    await user.selectOptions(select, '2026-09');
    expect(new URL(screen.getByTestId('where').textContent, 'http://x').searchParams.get('month')).toBe(
      '2026-09',
    );

    await user.selectOptions(select, '');
    expect(new URL(screen.getByTestId('where').textContent, 'http://x').searchParams.has('month')).toBe(
      false,
    );
  });

  it('shows the plot suggestions as a popover that is closed until typing or ArrowDown', async () => {
    const user = userEvent.setup();
    renderList();
    const box = screen.getByRole('combobox', { name: 'Ділянка' });
    expect(box).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();

    await user.type(box, '{ArrowDown}');
    expect(box).toHaveAttribute('aria-expanded', 'true');
    const list = screen.getByRole('listbox');
    expect(box).toHaveAttribute('aria-controls', list.id);
    expect(list).toHaveClass('absolute');
    expect(screen.getByRole('option', { name: '1 · Садова' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();

    await user.type(box, '2');
    expect(screen.getByRole('listbox')).toBeInTheDocument();
    await user.keyboard('{ArrowDown}{Enter}');
    expect(new URL(screen.getByTestId('where').textContent, 'http://x').searchParams.get('plotId')).toBe(
      'p1',
    );
  });
});
