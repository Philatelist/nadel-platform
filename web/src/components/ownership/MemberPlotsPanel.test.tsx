import { screen } from '@testing-library/react';
import type { MemberOwnerships, Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MemberPlotsPanel } from './MemberPlotsPanel';

let permissions: Permission[];
let myId: string;
let data: MemberOwnerships;
const hook = vi.fn();

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useMe: () => ({ memberId: myId }),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMemberOwnerships: (...args: unknown[]) => {
    hook(...args);
    return { data, error: null, isPending: false, isError: false, refetch: vi.fn() };
  },
}));

describe('MemberPlotsPanel', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW'];
    myId = 'me';
    hook.mockClear();
    data = {
      current: [
        {
          ownershipId: 'o1',
          plotId: 'p7',
          plotNumber: '7',
          street: 'Garden St',
          share: '1/2',
          validFrom: '2020-03-01',
        },
      ],
      past: [
        {
          ownershipId: 'o0',
          plotId: 'p3',
          plotNumber: '3',
          share: '1',
          validFrom: '2010-01-01',
          validTo: '2019-12-31',
        },
      ],
    };
  });

  it('lists plots owned now and before', () => {
    renderWithApp(<MemberPlotsPanel memberId="m1" />);
    expect(screen.getByRole('heading', { name: 'Plots' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Owns now' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Plot 7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByText('Garden St')).toBeInTheDocument();
    expect(screen.getByText('1/2')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Owned before' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Plot 3' })).toBeInTheDocument();
  });

  it('shows empty states', () => {
    data = { current: [], past: [] };
    renderWithApp(<MemberPlotsPanel memberId="m1" />);
    expect(screen.getByText('This member owns no plots.')).toBeInTheDocument();
    expect(screen.getByText('No plots owned before.')).toBeInTheDocument();
  });

  it('is shown with MEMBERS_VIEW alone and on the own card', () => {
    permissions = ['MEMBERS_VIEW'];
    const { unmount } = renderWithApp(<MemberPlotsPanel memberId="m1" />);
    expect(screen.getByRole('heading', { name: 'Plots' })).toBeInTheDocument();
    unmount();
    permissions = [];
    renderWithApp(<MemberPlotsPanel memberId="me" />);
    expect(screen.getByRole('heading', { name: 'Plots' })).toBeInTheDocument();
  });

  it('is hidden without permission for another member', () => {
    permissions = [];
    renderWithApp(<MemberPlotsPanel memberId="m1" />);
    expect(screen.queryByRole('heading', { name: 'Plots' })).toBeNull();
    expect(hook).toHaveBeenCalledWith('m1', { query: { enabled: false } });
  });
});
