import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Charge, Permission, PlotAccount } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { charge } from './fixtures';
import { PlotAccountPanel } from './PlotAccountPanel';

const useGetPlotAccount = vi.fn();
let permissions: Permission[];

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlotAccount: (...args: unknown[]): unknown => useGetPlotAccount(...args),
  useReverseCharge: () => ({ mutate: vi.fn(), isPending: false }),
  useListChargeTypes: () => queryResult([]),
}));

const reversal: Charge = {
  ...charge,
  id: 'c2',
  kind: 'REVERSAL',
  source: 'MANUAL_REVERSAL',
  amount: '-403.06',
  reversible: false,
  reverses: 'c1',
  reason: 'Wrong meter',
};

const account = (charges: Charge[]): PlotAccount => ({
  plotId: 'p7',
  debts: [
    {
      chargeTypeId: 't1',
      chargeTypeCode: 'ELECTRICITY',
      name: 'Electricity',
      nameCustomized: false,
      debt: '403.06',
    },
  ],
  total: '403.06',
  charges,
});

function render(readOnly = false) {
  return renderWithApp(withQueries(<PlotAccountPanel plotId="p7" readOnly={readOnly} />));
}

describe('PlotAccountPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW'];
    useGetPlotAccount.mockReset();
    useGetPlotAccount.mockReturnValue(queryResult(account([charge, { ...reversal, reversedBy: undefined }])));
  });

  it('shows the debt per type and total from the server, then the charges as given', () => {
    render();
    const debts = screen.getByRole('region', { name: 'Debt by charge type' });
    expect(within(debts).getAllByText('₴403.06')).toHaveLength(2);
    expect(within(debts).getByText('Total debt')).toBeInTheDocument();
    const items = within(screen.getByRole('list', { name: 'Plot charges' })).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('By readings');
    expect(items[1]).toHaveTextContent('Reversal');
  });

  it('names the service period and billing month only when the period leaves the month', () => {
    useGetPlotAccount.mockReturnValue(
      queryResult(
        account([charge, { ...charge, id: 'c3', serviceFrom: '2026-10-01', serviceTo: '2026-10-31' }]),
      ),
    );
    render();
    expect(screen.getAllByText(/charged in October 2026/)).toHaveLength(1);
  });

  it('hides the reversal from a viewer without CHARGES_REVERSE', () => {
    render();
    expect(screen.queryByRole('button', { name: 'Reverse' })).toBeNull();
  });

  it('offers a reversal to CHARGES_REVERSE for the reversible charge only', async () => {
    permissions = ['CHARGES_VIEW', 'CHARGES_REVERSE'];
    render();
    expect(screen.getAllByRole('button', { name: 'Reverse' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Reverse the charge');
  });

  it('hides every action in the read-only view', () => {
    permissions = ['CHARGES_VIEW', 'CHARGES_REVERSE', 'CHARGES_RUN'];
    render(true);
    expect(screen.queryByRole('button', { name: 'Reverse' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Enter an opening balance' })).toBeNull();
  });

  it('offers the opening balance to CHARGES_RUN', () => {
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN'];
    render();
    expect(screen.getByRole('button', { name: 'Enter an opening balance' })).toBeInTheDocument();
  });

  it('asks the server for the date range, and ignores an inverted one', async () => {
    render();
    await userEvent.type(screen.getByLabelText('From'), '2026-10-01');
    expect(useGetPlotAccount).toHaveBeenLastCalledWith('p7', { from: '2026-10-01' });
    await userEvent.type(screen.getByLabelText('To'), '2026-09-01');
    expect(useGetPlotAccount).toHaveBeenLastCalledWith('p7', {});
    expect(screen.getByText('Check the dates')).toBeInTheDocument();
  });

  it('shows the calculation line and the reason as user content in the detail dialog', async () => {
    permissions = ['CHARGES_VIEW', 'CHARGES_REVERSE'];
    useGetPlotAccount.mockReturnValue(queryResult(account([reversal])));
    render();
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Wrong meter')).toHaveAttribute('data-user-content');
    expect(
      within(dialog).getByText(/93\.3 kWh × ₴4\.32 = ₴403\.06, 23 Sep–4 Oct \(12 days\)/),
    ).toBeInTheDocument();
  });

  it('says a closed month is made by an adjustment', async () => {
    permissions = ['CHARGES_VIEW', 'CHARGES_REVERSE'];
    useGetPlotAccount.mockReturnValue(
      queryResult(account([{ ...charge, billingMonthClosed: true, reversible: false }])),
    );
    render();
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/made by an adjustment/)).toBeInTheDocument();
  });
});
