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
vi.mock('../components/MemberDetails', () => ({ MemberDetails: () => <p>details</p> }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMember: () => ok({ id: 'm1' }),
  useGetPlotTariffs: () => ok({ month: '2026-10', total: '0.00', items: [] }),
  useGetPlotAccount: () => ok({ plotId: 'p7', debts: [], total: '0.00', charges: [] }),
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
});
