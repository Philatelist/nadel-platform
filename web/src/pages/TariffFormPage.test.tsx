import { ApiError, type ChargeType } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { TariffFormPage } from './TariffFormPage';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };

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
  beforeEach(() => mutate.mockClear());

  it('offers only active charge types and preselects the one from the query string', () => {
    renderForm('/tariffs/new?chargeTypeId=c2');
    expect(screen.getByRole('option', { name: 'Membership fee' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Water' })).toBeNull();
    expect(screen.getByLabelText('Charge type')).toHaveValue('c2');
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
