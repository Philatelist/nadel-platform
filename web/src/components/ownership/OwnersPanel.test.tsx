import { screen, within } from '@testing-library/react';
import type { Permission, PlotOwnerships } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { OwnersPanel } from './OwnersPanel';

let permissions: Permission[];
let state: { data?: PlotOwnerships; isPending: boolean; isError: boolean };
const refetch = vi.fn();

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlotOwnerships: () => ({ error: null, refetch, ...state }),
}));

const ownerships: PlotOwnerships = {
  plotId: 'p9',
  current: [
    {
      id: 'o2',
      memberId: 'm11',
      memberName: 'Lytvynenko Svitlana',
      memberArchived: false,
      share: '2/4',
      validFrom: '2025-06-01',
      basis: 'Sale contract of 2025-05-20',
    },
    {
      id: 'o3',
      memberId: 'm12',
      memberName: 'Moroz Halyna',
      memberArchived: false,
      share: '1/2',
      validFrom: '2025-06-01',
    },
  ],
  history: [
    {
      id: 'o1',
      memberId: 'm20',
      memberName: 'Honcharenko Mykola',
      memberArchived: true,
      share: '1',
      validFrom: '2015-06-01',
      validTo: '2025-05-31',
      basis: 'Initial allocation',
    },
    {
      id: 'o0',
      memberId: 'm21',
      memberName: 'Older Owner',
      memberArchived: false,
      share: '1',
      validFrom: '2010-01-01',
      validTo: '2015-05-31',
    },
  ],
};

describe('OwnersPanel change owner button', () => {
  const full: Permission[] = ['PLOTS_VIEW', 'OWNERSHIP_EDIT', 'MEMBERS_VIEW'];
  const button = () => screen.queryByRole('button', { name: 'Change owner' });
  beforeEach(() => {
    state = { data: ownerships, isPending: false, isError: false };
  });

  it('is shown with PLOTS_VIEW, OWNERSHIP_EDIT and MEMBERS_VIEW', () => {
    permissions = full;
    renderWithApp(<OwnersPanel plotId="p9" />);
    expect(button()).toBeInTheDocument();
  });

  it.each([
    ['PLOTS_VIEW', full.filter((p) => p !== 'PLOTS_VIEW')],
    ['OWNERSHIP_EDIT', full.filter((p) => p !== 'OWNERSHIP_EDIT')],
    ['MEMBERS_VIEW', full.filter((p) => p !== 'MEMBERS_VIEW')],
  ])('is hidden without %s', (_name, given) => {
    permissions = given;
    renderWithApp(<OwnersPanel plotId="p9" />);
    expect(button()).not.toBeInTheDocument();
  });

  it('is hidden for common land and archived plots', () => {
    permissions = full;
    const { rerender } = renderWithApp(<OwnersPanel plotId="p9" kind="COMMON" />);
    expect(button()).not.toBeInTheDocument();
    rerender(<OwnersPanel plotId="p9" archived />);
    expect(button()).not.toBeInTheDocument();
  });
});

describe('OwnersPanel', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW'];
    state = { data: ownerships, isPending: false, isError: false };
  });

  it('lists current owners with reduced shares, the since date and the basis', () => {
    renderWithApp(<OwnersPanel plotId="p9" />);
    expect(screen.getByRole('heading', { name: 'Owners' })).toBeInTheDocument();
    const current = within(screen.getByRole('region', { name: 'Current owners' }));
    const items = current.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Lytvynenko Svitlana');
    expect(items[0]).toHaveTextContent('1/2');
    expect(items[0]).toHaveTextContent(/since 6\/1\/25/);
    expect(items[0]).toHaveTextContent('Basis: Sale contract of 2025-05-20');
    expect(items[1]).toHaveTextContent('Moroz Halyna');
  });

  it('lists history in the given order (newest first) with the period and an archived marker', () => {
    renderWithApp(<OwnersPanel plotId="p9" />);
    const items = within(screen.getByRole('region', { name: 'Ownership history' })).getAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual([
      expect.stringContaining('Honcharenko Mykola'),
      expect.stringContaining('Older Owner'),
    ]);
    expect(items[0]).toHaveTextContent('(archived)');
    expect(items[0]).toHaveTextContent('6/1/15 – 5/31/25');
    expect(items[0]).toHaveTextContent('Basis: Initial allocation');
    expect(items[1]).not.toHaveTextContent('(archived)');
  });

  it('shows the empty states', () => {
    state = { data: { plotId: 'p1', current: [], history: [] }, isPending: false, isError: false };
    renderWithApp(<OwnersPanel plotId="p1" />);
    expect(screen.getByText('No owner')).toBeInTheDocument();
    expect(screen.getByText('No ownership history')).toBeInTheDocument();
  });

  it('links member names to the member card only with MEMBERS_VIEW', () => {
    const { unmount } = renderWithApp(<OwnersPanel plotId="p9" />);
    expect(screen.queryByRole('link', { name: 'Moroz Halyna' })).toBeNull();
    expect(screen.getByText('Moroz Halyna')).toBeInTheDocument();
    unmount();
    permissions = ['PLOTS_VIEW', 'MEMBERS_VIEW'];
    renderWithApp(<OwnersPanel plotId="p9" />);
    expect(screen.getByRole('link', { name: 'Moroz Halyna' })).toHaveAttribute('href', '/members/m12');
  });

  it('shows loading and an error with Try again', () => {
    state = { isPending: true, isError: false };
    const { unmount } = renderWithApp(<OwnersPanel plotId="p9" />);
    expect(screen.queryByRole('region', { name: 'Current owners' })).toBeNull();
    unmount();
    state = { isPending: false, isError: true };
    renderWithApp(<OwnersPanel plotId="p9" />);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
