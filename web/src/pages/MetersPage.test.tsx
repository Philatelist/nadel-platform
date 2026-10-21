import { screen } from '@testing-library/react';
import type { Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { plotMeter, queryResult, wellMeter } from '../test/meterFixtures';
import { MetersPage } from './MetersPage';

let permissions: Permission[];
const useListMeters = vi.fn();

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMeters: (...args: unknown[]): unknown => useListMeters(...args),
  useRegisterMeter: () => ({ mutate: vi.fn(), isPending: false }),
}));

describe('MetersPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    useListMeters.mockReset();
    useListMeters.mockReturnValue(queryResult([plotMeter, wellMeter]));
  });

  it('lists meters with plot, kind, last reading with unit and a link to the card', () => {
    permissions = ['METERS_VIEW'];
    renderWithApp(<MetersPage />);
    expect(screen.getByRole('link', { name: 'EM-104523' })).toHaveAttribute('href', '/meters/m1');
    expect(screen.getByRole('link', { name: 'Well 1' })).toHaveAttribute('href', '/meters/m2');
    expect(screen.getByText('8,205 kWh on 1 Jun 2026')).toBeInTheDocument();
    expect(screen.getByText('1,234.5 kWh on 1 Mar 2026')).toBeInTheDocument();
    expect(screen.getByText('Removed')).toBeInTheDocument();
  });

  it('offers registration only with both METERS_VIEW and METERS_MANAGE', () => {
    permissions = ['METERS_VIEW'];
    const { unmount } = renderWithApp(<MetersPage />);
    expect(screen.queryByRole('button', { name: 'Register a meter' })).toBeNull();
    unmount();
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    renderWithApp(<MetersPage />);
    expect(screen.getByRole('button', { name: 'Register a meter' })).toBeInTheDocument();
  });

  it('takes the filters from the URL', () => {
    permissions = ['METERS_VIEW'];
    renderWithApp(<MetersPage />, { route: '/meters?resource=WATER&kind=PLOT&street=Lipova&removed=true' });
    expect(useListMeters).toHaveBeenCalledWith({
      resource: 'WATER',
      kind: 'PLOT',
      street: 'Lipova',
      includeRemoved: true,
    });
    expect(screen.getByRole('checkbox', { name: 'Show removed meters' })).toBeChecked();
  });

  it('ignores an unknown filter value', () => {
    permissions = ['METERS_VIEW'];
    renderWithApp(<MetersPage />, { route: '/meters?resource=GAS' });
    expect(useListMeters).toHaveBeenCalledWith(expect.objectContaining({ resource: undefined }));
  });
});
