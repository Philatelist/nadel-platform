import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AccountPage } from './AccountPage';

const ok = (data: unknown) => ({ data, error: null, isPending: false, isError: false, refetch: vi.fn() });

vi.mock('../me', () => ({
  useCan: () => false,
  useOptionalMe: () => null,
  useMe: () => ({
    memberId: 'm1',
    roles: [],
    permissions: [],
    tenant: { name: 'Demo' },
    ownedPlots: [{ plotId: 'p7', number: '7', share: '1/1' }],
  }),
}));
vi.mock('../roleName', () => ({ useRoleName: () => () => '' }));
vi.mock('../components/ownership/MyPlotsPanel', () => ({ MyPlotsPanel: () => <p>my plots</p> }));
vi.mock('../components/readings/MyMetersPanel', () => ({ MyMetersPanel: () => <p>my meters</p> }));
vi.mock('../components/invoices/MyInvoices', () => ({ MyInvoices: () => <p>my invoices</p> }));
vi.mock('../components/account/NotificationsPanel', () => ({
  NotificationsPanel: () => <p>notifications</p>,
}));
vi.mock('../components/MemberDetails', () => ({ MemberDetails: () => <p>details</p> }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMember: () => ok({ id: 'm1' }),
  useGetPlotTariffs: () => ok({ month: '2026-10', total: '0.00', items: [] }),
  useGetSettings: () => ok({ autoAllocatePrepayment: false }),
  useGetPlotAccount: () =>
    ok({
      plotId: 'p7',
      debts: [],
      total: '0.00',
      prepayment: '50.00',
      charges: [],
      payments: [
        {
          id: 'pay1',
          kind: 'PAYMENT',
          groupId: 'g1',
          source: 'CASH',
          amount: '300.00',
          receivedOn: '2026-10-03',
          billingMonth: '2026-10',
          receiptNumber: 42,
          reversed: false,
          allocations: [
            {
              id: 'al1',
              chargeId: 'c1',
              chargeType: { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false },
              serviceFrom: '2026-09-01',
              serviceTo: '2026-09-30',
              accruedOn: '2026-09-30',
              amount: '250.00',
              system: false,
              createdAt: '2026-10-03T09:00:00Z',
            },
          ],
          createdAt: '2026-10-03T09:00:00Z',
        },
      ],
      prepaymentAllocations: [],
    }),
}));

describe('AccountPage', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('shows the own account and what the member will be charged after the plots', () => {
    renderWithApp(<AccountPage />);
    expect(screen.getByRole('heading', { name: 'My charges' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Expected charges' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { name: 'Plot 7' })).toHaveLength(2);
    expect(screen.getByText('my meters')).toBeInTheDocument();
  });

  it('shows the own payment with its receipt number, what it paid and the prepayment, without a card link', () => {
    renderWithApp(<AccountPage />);
    expect(screen.getAllByText('№ 42').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('list', { name: /^Payment / })[0]).toHaveTextContent('Membership');
    expect(screen.getAllByText('Prepayment').length).toBeGreaterThan(0);
    expect(screen.queryByRole('link', { name: /2026/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Allocate prepayment' })).toBeNull();
  });
});
