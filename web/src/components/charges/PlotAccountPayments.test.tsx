import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AccountPayment, Permission, PlotAccount } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { charge } from './fixtures';
import { PlotAccountPanel } from './PlotAccountPanel';

const useGetPlotAccount = vi.fn();
let permissions: Permission[];
let autoAllocate = false;

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('../payments/AllocatePrepaymentDialog', () => ({
  AllocatePrepaymentDialog: ({ onDone }: { onDone: () => void }) => (
    <div role="dialog">
      allocate dialog <button onClick={onDone}>close dialog</button>
    </div>
  ),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlotAccount: (...args: unknown[]): unknown => useGetPlotAccount(...args),
  useGetSettings: () => queryResult({ autoAllocatePrepayment: autoAllocate }),
  useReverseCharge: () => ({ mutate: vi.fn(), isPending: false }),
  useListChargeTypes: () => queryResult([]),
}));

const payment: AccountPayment = {
  id: 'pay1',
  kind: 'PAYMENT',
  groupId: 'g1',
  source: 'CASH',
  amount: '300.00',
  receivedOn: '2026-10-10',
  billingMonth: '2026-10',
  receiptNumber: 17,
  reversed: false,
  allocations: [
    {
      id: 'al1',
      chargeId: 'c1',
      chargeType: charge.chargeType,
      serviceFrom: '2026-09-23',
      serviceTo: '2026-10-04',
      accruedOn: '2026-10-04',
      amount: '300.00',
      system: false,
      createdAt: '2026-10-10T09:00:00Z',
    },
  ],
  createdAt: '2026-10-10T09:00:00Z',
};

const account = (over: Partial<PlotAccount> = {}): PlotAccount => ({
  plotId: 'p7',
  debts: [],
  total: '103.06',
  prepayment: '0.00',
  charges: [
    { ...charge, id: 'c-new', accruedOn: '2026-10-20', paid: '0.00', open: '50.00' },
    { ...charge, paid: '300.00', open: '103.06' },
  ],
  payments: [payment],
  prepaymentAllocations: [
    {
      allocatedOn: '2026-10-15',
      createdAt: '2026-10-15T09:00:00Z',
      origin: 'PREPAYMENT_AUTO',
      system: true,
      amount: '20.00',
      lines: [{ ...payment.allocations[0]!, id: 'al2', amount: '20.00' }],
    },
  ],
  ...over,
});

const render = (readOnly = false) =>
  renderWithApp(withQueries(<PlotAccountPanel plotId="p7" readOnly={readOnly} />));

describe('PlotAccountPanel with payments', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW'];
    autoAllocate = false;
    useGetPlotAccount.mockReset();
    useGetPlotAccount.mockReturnValue(queryResult(account()));
  });

  it('interleaves charges, payments and placements by date, newest first', () => {
    render();
    const list = screen.getByRole('list', { name: 'Plot charges' });
    const rows = Array.from(list.children).map((row) => row.textContent ?? '');
    expect(rows).toHaveLength(4);
    expect(rows[0]).toMatch(/^20 Oct 2026/);
    expect(rows[1]).toMatch(/^15 Oct 2026Allocated from prepayment/);
    expect(rows[2]).toMatch(/^10 Oct 2026Payment/);
    expect(rows[3]).toMatch(/^4 Oct 2026/);
    expect(within(list).getByText('Allocated from prepayment')).toBeInTheDocument();
  });

  it('shows receipt number, source, what the payment paid and the reversed badge', () => {
    useGetPlotAccount.mockReturnValue(
      queryResult(account({ payments: [{ ...payment, reversed: true, reversedBy: 'rev1' }] })),
    );
    render();
    expect(screen.getByText('№ 17')).toBeInTheDocument();
    expect(screen.getByText('Cash')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: /^Payment / })).toHaveTextContent('Electricity');
    expect(screen.getByText('Reversed')).toBeInTheDocument();
  });

  it('links the payment card only with PAYMENTS_VIEW', () => {
    const { unmount } = render();
    expect(screen.queryByRole('link', { name: '10 Oct 2026' })).toBeNull();
    unmount();
    permissions = ['CHARGES_VIEW', 'PAYMENTS_VIEW'];
    render();
    expect(screen.getByRole('link', { name: '10 Oct 2026' })).toHaveAttribute('href', '/payments/pay1');
  });

  it('shows charged, paid and open per charge as the server states them', () => {
    render();
    const row = screen.getByText('20 Oct 2026').closest('li') as HTMLElement;
    expect(within(row).getByText('Charged')).toBeInTheDocument();
    expect(within(row).getByText('Paid')).toBeInTheDocument();
    expect(within(row).getByText('Open')).toBeInTheDocument();
    expect(within(row).getByText('₴50.00')).toBeInTheDocument();
  });

  it('shows the prepayment row, and the allocate button only with PAYMENTS_ENTER and automatic allocation off', async () => {
    useGetPlotAccount.mockReturnValue(queryResult(account({ prepayment: '75.00' })));
    const first = render();
    expect(screen.getByText('Prepayment')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Allocate prepayment' })).toBeNull();
    first.unmount();

    permissions = ['CHARGES_VIEW', 'PAYMENTS_ENTER'];
    autoAllocate = true;
    const second = render();
    expect(screen.queryByRole('button', { name: 'Allocate prepayment' })).toBeNull();
    second.unmount();

    autoAllocate = false;
    const third = render(true);
    expect(screen.queryByRole('button', { name: 'Allocate prepayment' })).toBeNull();
    third.unmount();

    render();
    await userEvent.click(screen.getByRole('button', { name: 'Allocate prepayment' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'close dialog' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('hides the prepayment row when there is none', () => {
    render();
    expect(screen.queryByText('Prepayment')).toBeNull();
  });

  it('asks the server for payments only when the toggle is on', async () => {
    render();
    expect(useGetPlotAccount).toHaveBeenLastCalledWith('p7', {});
    await userEvent.click(screen.getByLabelText('Payments only'));
    expect(useGetPlotAccount).toHaveBeenLastCalledWith('p7', { kind: 'PAYMENTS' });
  });
});
