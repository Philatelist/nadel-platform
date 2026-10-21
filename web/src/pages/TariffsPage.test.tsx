import { screen, within } from '@testing-library/react';
import type { ChargeType, Permission, TariffSummary } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { TariffsPage } from './TariffsPage';

let types: ChargeType[];
let permissions: Permission[];
let tariffs: TariffSummary[];
let tariffsState: { isPending: boolean; isError: boolean };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => ({
    data: types,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useListTariffs: () => ({
    data: tariffsState.isPending || tariffsState.isError ? undefined : tariffs,
    error: null,
    ...tariffsState,
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
    tariffs = [];
    tariffsState = { isPending: false, isError: false };
    localStorage.removeItem('nadel.locale');
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

  it('shows loading, not "No tariffs yet", while the tariffs are pending', () => {
    tariffsState = { isPending: true, isError: false };
    renderWithApp(<TariffsPage />);
    expect(screen.queryByText('No tariffs yet')).toBeNull();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('shows the error state, not "No tariffs yet", when the tariffs fail to load', () => {
    tariffsState = { isPending: false, isError: true };
    renderWithApp(<TariffsPage />);
    expect(screen.queryByText('No tariffs yet')).toBeNull();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('links to the charge types only with TARIFFS_MANAGE', () => {
    const { unmount } = renderWithApp(<TariffsPage />);
    expect(screen.getByRole('link', { name: 'Charge types' })).toHaveAttribute('href', '/charge-types');
    unmount();
    permissions = ['TARIFFS_VIEW'];
    renderWithApp(<TariffsPage />);
    expect(screen.queryByRole('link', { name: 'Charge types' })).toBeNull();
  });

  it('shows a link to a new tariff only with TARIFFS_MANAGE', () => {
    const { unmount } = renderWithApp(<TariffsPage />);
    expect(screen.getByRole('link', { name: 'New tariff' })).toHaveAttribute('href', '/tariffs/new');
    unmount();
    permissions = ['TARIFFS_VIEW'];
    renderWithApp(<TariffsPage />);
    expect(screen.queryByRole('link', { name: 'New tariff' })).toBeNull();
  });

  it('lists the tariffs under their charge type with rate, basis, frequency, date and plot count', () => {
    tariffs = [
      summary('t1', 'c1', 'Per sotka', { rate: '4.3200', basis: 'PER_AREA_SOTKA' }, 1),
      summary('t2', 'c1', 'Per plot', { rate: '120.5000', basis: 'PER_PLOT' }, 24),
      summary('t3', 'c2', 'Lighting', { rate: '0.1000', basis: 'PER_KWH' }, undefined),
    ];
    renderWithApp(<TariffsPage />);
    const membership = within(screen.getByRole('heading', { name: 'Membership fee' }).closest('section')!);
    expect(membership.getByRole('link', { name: 'Per sotka' })).toHaveAttribute('href', '/tariffs/t1');
    expect(membership.getByText(/4\.3200 ₴ · Per sotka · Monthly/)).toBeInTheDocument();
    expect(membership.getByText(/from 11\/01\/2026 · 1 plot$/)).toBeInTheDocument();
    expect(membership.getByText(/120\.5000 ₴ · Per plot/)).toBeInTheDocument();
    expect(membership.getByText(/24 plots$/)).toBeInTheDocument();
    const road = within(screen.getByRole('heading', { name: 'Road repair' }).closest('section')!);
    expect(road.getByText(/applies to plots with a meter$/)).toBeInTheDocument();
    expect(screen.getAllByText('No tariffs yet')).toHaveLength(1);
  });

  it('uses the Ukrainian plural forms of "ділянка"', () => {
    localStorage.setItem('nadel.locale', 'uk');
    tariffs = [1, 2, 4, 5, 11, 21, 22].map((n) =>
      summary(`t${n}`, 'c1', `Tariff ${n}`, { rate: '1.0000', basis: 'PER_PLOT' }, n),
    );
    renderWithApp(<TariffsPage />);
    const texts = screen
      .getAllByText(/^діє з .* · \d+ ділян(ка|ки|ок)$/)
      .map((el) => el.textContent?.split(' · ').pop());
    expect(texts).toEqual([
      '1 ділянка',
      '2 ділянки',
      '4 ділянки',
      '5 ділянок',
      '11 ділянок',
      '21 ділянка',
      '22 ділянки',
    ]);
    expect(screen.getAllByText(/^1,0000 ₴ · За ділянку · Щомісяця$/)).toHaveLength(7);
  });

  it('shows the per-sotka yearly row in Ukrainian with the start date', () => {
    localStorage.setItem('nadel.locale', 'uk');
    tariffs = [
      summary(
        't1',
        'c1',
        'Land tariff',
        { rate: '50.0000', basis: 'PER_AREA_SOTKA', frequency: 'YEARLY', validFrom: '2027-01-01' },
        3,
      ),
    ];
    renderWithApp(<TariffsPage />);
    expect(screen.getByText('50,0000 ₴ · За сотку · Щороку')).toBeInTheDocument();
    expect(screen.getByText(/^діє з 01\.01\.2027/)).toBeInTheDocument();
  });
});

function summary(
  id: string,
  chargeTypeId: string,
  name: string,
  version: Pick<TariffSummary['version'], 'rate' | 'basis'> &
    Partial<Pick<TariffSummary['version'], 'frequency' | 'validFrom'>>,
  plotCount: number | undefined,
): TariffSummary {
  return {
    id,
    name,
    chargeTypeId,
    scopeKind: 'ALL_PLOTS',
    plotCount,
    version: {
      id: `v-${id}`,
      validFrom: '2026-11-01',
      frequency: 'MONTHLY',
      pastDated: false,
      ...version,
    },
  };
}
