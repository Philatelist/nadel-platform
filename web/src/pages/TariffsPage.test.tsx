import { screen } from '@testing-library/react';
import type { ChargeType, Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { TariffsPage } from './TariffsPage';

let types: ChargeType[];
let permissions: Permission[];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => ({
    data: types,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));
vi.mock('../me', () => ({
  useOptionalMe: () => null,
  useCan: (permission: Permission) => permissions.includes(permission),
}));

describe('TariffsPage', () => {
  beforeEach(() => {
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    types = [
      {
        id: 'c1',
        code: 'MEMBERSHIP',
        name: 'Membership fee',
        nameCustomized: false,
        active: true,
        position: 1,
      },
      { id: 'c2', name: 'Road repair', nameCustomized: true, active: true, position: 2 },
      { id: 'c3', code: 'WATER', name: 'Water', nameCustomized: false, active: false, position: 3 },
    ];
  });

  it('lists the charge types in order, each without tariffs, and marks the inactive one', () => {
    renderWithApp(<TariffsPage />);
    expect(screen.getByRole('heading', { level: 1, name: 'Tariffs' })).toBeInTheDocument();
    const names = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(names).toEqual(['Membership fee', 'Road repair', 'Water']);
    expect(screen.getAllByText('No tariffs yet')).toHaveLength(3);
    expect(screen.getAllByText('Inactive')).toHaveLength(1);
  });

  it('links to the charge types only with TARIFFS_MANAGE', () => {
    const { unmount } = renderWithApp(<TariffsPage />);
    expect(screen.getByRole('link', { name: 'Charge types' })).toHaveAttribute('href', '/charge-types');
    unmount();
    permissions = ['TARIFFS_VIEW'];
    renderWithApp(<TariffsPage />);
    expect(screen.queryByRole('link', { name: 'Charge types' })).toBeNull();
  });
});
