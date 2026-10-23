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
const updateMutate = vi.fn();
const deleteMutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };
let updateOptions: { mutation: { onError: (e: unknown) => void } };
let deleteOptions: { mutation: { onError: (e: unknown) => void } };
let permissions: Permission[];
let tariff: Tariff;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetTariff: () => ({ data: tariff, error: null, isPending: false, isError: false, refetch: vi.fn() }),
  useListPlots: () => ({ data: { items: [] } }),
  useUpdateTariffScope: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateTariffVersion: (o: typeof updateOptions) => {
    updateOptions = o;
    return { mutate: updateMutate, isPending: false };
  },
  useDeleteTariffVersion: (o: typeof deleteOptions) => {
    deleteOptions = o;
    return { mutate: deleteMutate, isPending: false };
  },
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
    updateMutate.mockClear();
    deleteMutate.mockClear();
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    tariff = {
      id: 't1',
      name: 'Per sotka',
      scopeKind: 'ALL_PLOTS',
      selectedPlots: [],
      exclusions: [],
      conflicts: [],
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
      'from 1 Jan 2027' + '₴5.00 · Per sotka · Monthly' + 'Meeting of 12 Dec' + 'EditDelete',
      'from 1 Jul 2026 to 31 Dec 2026' + '₴4.32 · Per sotka · Monthly' + 'EditDelete',
      'from 1 Jan 2026 to 30 Jun 2026' +
        '₴100.00 · Fixed one-time amount · One-off' +
        'due 15 Mar 2026' +
        'EditDelete',
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
    expect(screen.getByText('from 1 Jan 2027', { exact: false })).toBeInTheDocument();
  });

  it('puts a taken date on the date field', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Valid from'), '2026-07-01');
    await user.click(screen.getByRole('button', { name: 'Add version' }));
    act(() => options.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_DATE_TAKEN' })));
    expect(await screen.findByText('A version with this date already exists')).toBeInTheDocument();
  });

  it('edits a version in the prefilled form and sends it with PUT', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[1]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit version' });
    expect(within(form).getByLabelText('Valid from')).toHaveValue('2026-07-01');
    expect(within(form).getByLabelText('Rate, ₴')).toHaveValue('4.3200');
    await user.clear(within(form).getByLabelText('Rate, ₴'));
    await user.type(within(form).getByLabelText('Rate, ₴'), '4.5');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    expect(updateMutate).toHaveBeenCalledWith({
      id: 't1',
      versionId: 'v2',
      data: {
        validFrom: '2026-07-01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'MONTHLY',
        rate: '4.5',
        dueDate: undefined,
        note: undefined,
      },
    });
  });

  it('confirms before deleting a version and names it by its start date', async () => {
    const user = userEvent.setup();
    renderPage();
    const second = within(screen.getByRole('list')).getAllByRole('listitem')[1]!;
    await user.click(within(second).getByRole('button', { name: 'Delete' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/valid from 1 Jul 2026/)).toBeInTheDocument();
    expect(deleteMutate).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(deleteMutate).toHaveBeenCalledWith({ id: 't1', versionId: 'v2' });
  });

  it('says a tariff must keep a version when the last one is deleted', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    act(() => deleteOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_LAST_VERSION' })));
    expect(await screen.findByText('A tariff must have at least one version')).toBeInTheDocument();
  });

  it('disables the controls with a hint when the version is in use (409 on delete)', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    act(() => deleteOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    expect(await within(first).findByText(/already used in charges/)).toBeInTheDocument();
    expect(within(first).getByRole('button', { name: 'Edit' })).toBeDisabled();
    expect(within(first).getByRole('button', { name: 'Delete' })).toBeDisabled();
  });

  it('disables the controls when saving a version in use fails with 409', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    act(() => updateOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    expect(await screen.findByText(/already used in charges/)).toBeInTheDocument();
    expect(within(first).getByRole('button', { name: 'Edit' })).toBeDisabled();
  });

  it('puts a taken date of an edited version on the date field', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    act(() => updateOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_DATE_TAKEN' })));
    expect(await screen.findByText('A version with this date already exists')).toBeInTheDocument();
  });

  it('shows no edit or delete controls without TARIFFS_MANAGE', () => {
    permissions = ['TARIFFS_VIEW'];
    renderPage();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('shows no edit or delete controls for an archived charge type', () => {
    tariff = { ...tariff, chargeType: { ...tariff.chargeType, active: false } };
    renderPage();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });
});
