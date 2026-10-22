import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ChargeType } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { OpeningBalanceForm } from './OpeningBalanceForm';

const mutate = vi.fn();
const types: ChargeType[] = [
  { id: 't1', code: 'MEMBERSHIP', name: 'Membership fee', nameCustomized: false, active: true, position: 1 },
  { id: 't2', code: 'WATER', name: 'Water', nameCustomized: false, active: false, position: 2 },
];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => queryResult(types),
  useEnterOpeningBalance: () => ({ mutate, isPending: false }),
}));

describe('OpeningBalanceForm', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('lists active charge types only and refuses text that is not an amount', async () => {
    renderWithApp(withQueries(<OpeningBalanceForm plotId="p7" onDone={vi.fn()} onClose={vi.fn()} />));
    expect(screen.getByRole('option', { name: 'Membership fee' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Water' })).toBeNull();
    await userEvent.type(screen.getByLabelText('Debt amount, ₴'), 'abc');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('Enter an amount like 1250.50')).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it('sends the amount in the API form, the chosen type, today as the date and the trimmed reason', async () => {
    renderWithApp(withQueries(<OpeningBalanceForm plotId="p7" onDone={vi.fn()} onClose={vi.fn()} />));
    await userEvent.type(screen.getByLabelText('Debt amount, ₴'), '1250,5');
    await userEvent.type(screen.getByLabelText('Reason or document reference'), ' Act 12 ');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(mutate).toHaveBeenCalledWith({
      id: 'p7',
      data: {
        chargeTypeId: 't1',
        amount: '1250.50',
        date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        reason: 'Act 12',
      },
    });
  });
});
