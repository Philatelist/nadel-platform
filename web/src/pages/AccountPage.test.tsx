import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AccountPage } from './AccountPage';

const ok = (data: unknown) => ({ data, error: null, isPending: false, isError: false, refetch: vi.fn() });

vi.mock('../me', () => ({
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
vi.mock('../components/MemberDetails', () => ({ MemberDetails: () => <p>details</p> }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMember: () => ok({ id: 'm1' }),
  useGetPlotTariffs: () => ok({ month: '2026-10', total: '0.00', items: [] }),
}));

describe('AccountPage', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('shows what the member will be charged after the plots', () => {
    renderWithApp(<AccountPage />);
    expect(screen.getByRole('heading', { name: 'What I will be charged' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Plot 7' })).toBeInTheDocument();
  });
});
