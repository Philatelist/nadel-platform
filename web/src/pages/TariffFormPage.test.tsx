import { ApiError, type ChargeType, type TariffSummary } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { TariffFormPage } from './TariffFormPage';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };
const activateMutate = vi.fn();
let activateOptions: {
  mutation: { onSuccess: (saved: ChargeType) => Promise<void>; onError: (e: unknown) => void };
};
const listTariffs = vi.fn();
let activeTariffs: TariffSummary[] = [];

const types: ChargeType[] = [
  { id: 'c1', code: 'MEMBERSHIP', name: 'Membership fee', nameCustomized: false, active: true, position: 1 },
  { id: 'c2', name: 'Road repair', nameCustomized: true, active: true, position: 2 },
  { id: 'c3', code: 'WATER', name: 'Water', nameCustomized: false, active: false, position: 3 },
];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => ({
    data: types,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useUpdateChargeType: (o: typeof activateOptions) => {
    activateOptions = o;
    return { mutate: activateMutate, isPending: false };
  },
  useListTariffs: (params: unknown) => {
    listTariffs(params);
    return { data: activeTariffs };
  },
  useCreateTariff: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
}));

function renderForm(route = '/tariffs/new') {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <TariffFormPage />
    </QueryClientProvider>,
    { route },
  );
}

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText('Charge type'), 'c1');
  await user.type(screen.getByLabelText('Tariff name'), ' Per sotka ');
  await user.type(screen.getByLabelText('Valid from'), '2999-01-01');
  await user.type(screen.getByLabelText('Rate, ₴'), '4,32');
}

describe('TariffFormPage', () => {
  beforeEach(() => {
    mutate.mockClear();
    activateMutate.mockClear();
    listTariffs.mockClear();
    activeTariffs = [];
  });

  it('lists inactive charge types as an enable option and preselects an active one from the query string', () => {
    renderForm('/tariffs/new?chargeTypeId=c2');
    expect(screen.getByRole('option', { name: 'Membership fee' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Water (inactive — enable)' })).toBeInTheDocument();
    expect(screen.getByLabelText('Charge type')).toHaveValue('c2');
  });

  it('enables an inactive charge type with its current values, then selects it', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.selectOptions(screen.getByLabelText('Charge type'), 'c3');
    expect(activateMutate).toHaveBeenCalledWith({
      id: 'c3',
      data: { name: 'Water', description: undefined, active: true },
    });
    expect(screen.getByLabelText('Charge type')).toHaveValue('');
    await act(() => activateOptions.mutation.onSuccess({ ...types[2]!, active: true }));
    expect(screen.getByLabelText('Charge type')).toHaveValue('c3');
  });

  it('keeps the previous selection and shows the problem when enabling fails', async () => {
    const user = userEvent.setup();
    renderForm('/tariffs/new?chargeTypeId=c1');
    await user.selectOptions(screen.getByLabelText('Charge type'), 'c3');
    act(() => activateOptions.mutation.onError(new ApiError(409, { code: 'CHARGE_TYPE_ARCHIVED' })));
    expect(screen.getByLabelText('Charge type')).toHaveValue('c1');
    expect(await screen.findByText('This charge type is archived.')).toBeInTheDocument();
  });

  it('lists the active tariffs of the chosen type with their plots, shortened when long', async () => {
    const user = userEvent.setup();
    const base = { chargeTypeId: 'c1', scopeKind: 'ALL_PLOTS', conflicts: [], deletable: false } as const;
    activeTariffs = [
      { ...base, id: 't1', name: 'Per sotka', plotNumbers: ['1', '2'] },
      {
        ...base,
        id: 't2',
        name: 'Per plot',
        plotNumbers: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10'],
      },
    ] as unknown as TariffSummary[];
    renderForm();
    await user.selectOptions(screen.getByLabelText('Charge type'), 'c1');
    expect(listTariffs).toHaveBeenLastCalledWith({ status: 'ACTIVE', chargeTypeId: 'c1' });
    expect(screen.getByText('Current tariffs of this type')).toBeInTheDocument();
    expect(screen.getByText(/plots: 1, 2$/)).toBeInTheDocument();
    expect(screen.getByText(/plots: 1, 2, 3, 4, 5, 6, 7, 8, and 2 more$/)).toBeInTheDocument();
  });

  it('says "none" when the chosen type has no active tariffs', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.selectOptions(screen.getByLabelText('Charge type'), 'c1');
    expect(screen.getByText('none')).toBeInTheDocument();
  });

  it('links to the charge types', () => {
    renderForm();
    expect(screen.getByRole('link', { name: 'Charge types' })).toHaveAttribute('href', '/charge-types');
  });

  it('sends the rate as a normalised string with the trimmed name', async () => {
    const user = userEvent.setup();
    renderForm();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create tariff' }));
    expect(mutate).toHaveBeenCalledWith({
      data: {
        chargeTypeId: 'c1',
        name: 'Per sotka',
        firstVersion: {
          validFrom: '2999-01-01',
          basis: 'PER_PLOT',
          frequency: 'MONTHLY',
          rate: '4.32',
          dueDate: undefined,
          note: undefined,
        },
      },
    });
  });

  it('blocks a bad rate and a missing due date before sending', async () => {
    const user = userEvent.setup();
    renderForm();
    await fillValid(user);
    await user.clear(screen.getByLabelText('Rate, ₴'));
    await user.type(screen.getByLabelText('Rate, ₴'), '4.32001');
    await user.selectOptions(screen.getByLabelText('Basis'), 'FIXED_ONE_TIME');
    await user.click(screen.getByRole('button', { name: 'Create tariff' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(
      screen.getByText('The rate must be a non-negative number with at most 4 decimal places'),
    ).toBeInTheDocument();
    expect(screen.getByText('Enter the due date')).toBeInTheDocument();
  });

  it('maps server field errors with the firstVersion prefix onto the fields', async () => {
    const user = userEvent.setup();
    renderForm();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create tariff' }));
    act(() =>
      options.mutation.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'firstVersion.rate', code: 'RATE_INVALID' }],
        }),
      ),
    );
    expect(
      await screen.findByText('The rate must be a non-negative number with at most 4 decimal places'),
    ).toBeInTheDocument();
  });

  it('shows an archived charge type as a form message', async () => {
    const user = userEvent.setup();
    renderForm();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Create tariff' }));
    act(() => options.mutation.onError(new ApiError(409, { code: 'CHARGE_TYPE_ARCHIVED' })));
    expect(await screen.findByText('This charge type is archived.')).toBeInTheDocument();
  });
});
