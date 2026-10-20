import { ApiError, type Permission, type Tariff } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { TariffPage } from './TariffPage';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };
let permissions: Permission[];
let tariff: Tariff;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetTariff: () => ({ data: tariff, error: null, isPending: false, isError: false, refetch: vi.fn() }),
  useListPlots: () => ({ data: { items: [] } }),
  useUpdateTariffScope: () => ({ mutate: vi.fn(), isPending: false }),
  useAddTariffVersion: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
}));
vi.mock('../me', () => ({
  useOptionalMe: () => null,
  useCan: (permission: Permission) => permissions.includes(permission),
}));

function renderPage() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/tariffs/:id" element={<TariffPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/tariffs/t1' },
  );
}

describe('TariffPage', () => {
  beforeEach(() => {
    mutate.mockClear();
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    tariff = {
      id: 't1',
      name: 'Per sotka',
      scopeKind: 'ALL_PLOTS',
      selectedPlots: [],
      exclusions: [],
      chargeType: {
        id: 'c1',
        code: 'MEMBERSHIP',
        name: 'Membership fee',
        nameCustomized: false,
        active: true,
        position: 1,
      },
      versions: [
        {
          id: 'v3',
          validFrom: '2027-01-01',
          basis: 'PER_AREA_SOTKA',
          frequency: 'MONTHLY',
          rate: '5.0000',
          pastDated: false,
          note: 'Meeting of 12 Dec',
        },
        {
          id: 'v2',
          validFrom: '2026-07-01',
          validTo: '2026-12-31',
          basis: 'PER_AREA_SOTKA',
          frequency: 'MONTHLY',
          rate: '4.3200',
          pastDated: true,
        },
        {
          id: 'v1',
          validFrom: '2026-01-01',
          validTo: '2026-06-30',
          basis: 'FIXED_ONE_TIME',
          frequency: 'ONE_OFF',
          rate: '100.0000',
          dueDate: '2026-03-15',
          pastDated: true,
        },
      ],
    };
  });

  it('shows the tariff, its charge type and the versions newest first with date ranges', () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Per sotka' })).toBeInTheDocument();
    expect(screen.getByText('Charge type: Membership fee')).toBeInTheDocument();
    const items = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual([
      'from 01/01/2027' + '5.0000 ₴ · Per sotka · Monthly' + 'Meeting of 12 Dec',
      'from 07/01/2026 to 12/31/2026' + '4.3200 ₴ · Per sotka · Monthly',
      'from 01/01/2026 to 06/30/2026' + '100.0000 ₴ · Fixed one-time amount · One-off' + 'due 03/15/2026',
    ]);
  });

  it('shows the new version form with TARIFFS_MANAGE', async () => {
    const user = userEvent.setup();
    renderPage();
    expect(screen.getByRole('heading', { name: 'New version' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Valid from'), '2999-01-01');
    await user.clear(screen.getByLabelText('Rate, ₴'));
    await user.type(screen.getByLabelText('Rate, ₴'), '6');
    await user.click(screen.getByRole('button', { name: 'Add version' }));
    expect(mutate).toHaveBeenCalledWith({
      id: 't1',
      data: {
        validFrom: '2999-01-01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'MONTHLY',
        rate: '6',
        dueDate: undefined,
        note: undefined,
      },
    });
  });

  it('hides the new version form without TARIFFS_MANAGE', () => {
    permissions = ['TARIFFS_VIEW'];
    renderPage();
    expect(screen.queryByRole('heading', { name: 'New version' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add version' })).toBeNull();
    expect(screen.getByText('from 01/01/2027', { exact: false })).toBeInTheDocument();
  });

  it('puts a taken date on the date field', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Valid from'), '2026-07-01');
    await user.click(screen.getByRole('button', { name: 'Add version' }));
    act(() => options.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_DATE_TAKEN' })));
    expect(await screen.findByText('A version with this date already exists')).toBeInTheDocument();
  });
});
