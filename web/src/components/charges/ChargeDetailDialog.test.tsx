import type { Charge, Permission } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { ChargeDetailDialog } from './ChargeDetailDialog';
import { charge } from './fixtures';

vi.mock('../../me', () => ({
  useCan: (p: Permission) => p === 'CHARGES_REVERSE',
  useOptionalMe: () => null,
}));

const consumption: Charge = {
  ...charge,
  calculation: {
    amount: '403.06',
    parts: [
      {
        basis: 'PER_KWH',
        frequency: 'MONTHLY',
        quantity: '93.300',
        unit: 'KWH',
        rate: '4.3200',
        days: 12,
        from: '2026-09-23',
        to: '2026-10-04',
      },
    ],
    readings: [
      { meterId: 'm1', takenOn: '2026-09-23', value: '8111.700' },
      { meterId: 'm1', takenOn: '2026-10-04', value: '8205.000' },
    ],
  },
};

const losses: Charge = {
  ...consumption,
  source: 'LOSSES',
  calculation: {
    amount: '20.15',
    parts: [{ ...consumption.calculation.parts[0]!, quantity: '4.665' }],
    readings: consumption.calculation.readings,
    lossesPercent: '5.00',
    consumption: '93.300',
  },
};

function open(value: Charge) {
  renderWithApp(withQueries(<ChargeDetailDialog charge={value} onClose={vi.fn()} />));
}

describe('ChargeDetailDialog', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('shows kWh with the rate and days, and both readings of a consumption charge', () => {
    open(consumption);
    const calculation = screen.getByRole('region', { name: 'Calculation' });
    expect(calculation).toHaveTextContent('93.3 kWh');
    expect(calculation).toHaveTextContent('4.32');
    expect(calculation).toHaveTextContent('12 days');
    const readings = screen.getByRole('region', { name: 'Readings' });
    expect(within(readings).getAllByRole('listitem')).toHaveLength(2);
    expect(readings).toHaveTextContent('23 Sep 2026: 8,111.7');
    expect(readings).toHaveTextContent('4 Oct 2026: 8,205');
    expect(screen.queryByText('Losses percentage')).toBeNull();
    expect(screen.queryByText('Consumption')).toBeNull();
  });

  it('shows the consumption and the percentage of a losses charge', () => {
    open(losses);
    expect(screen.getByText('Consumption').nextSibling).toHaveTextContent('93.3 kWh');
    expect(screen.getByText('Losses percentage').nextSibling).toHaveTextContent('5.00%');
    expect(screen.getByRole('region', { name: 'Calculation' })).toHaveTextContent('4.665 kWh');
  });

  it('names the node of each part and links the run of a proportional losses charge', () => {
    const part = consumption.calculation.parts[0]!;
    open({
      ...losses,
      calculation: {
        amount: '30.00',
        runId: 'r1',
        parts: [
          { ...part, nodeMeterId: 'qa', calculated: true },
          { ...part, nodeMeterId: 'sub' },
        ],
        readings: [],
      },
    });
    expect(screen.getByRole('link', { name: 'Open the run' })).toHaveAttribute(
      'href',
      '/charges/losses?run=r1',
    );
    const calculation = screen.getByRole('region', { name: 'Calculation' });
    const nodes = within(calculation).getAllByRole('link', { name: 'Node' });
    expect(nodes.map((n) => n.getAttribute('href'))).toEqual(['/meters/qa', '/meters/sub']);
    expect(calculation).toHaveTextContent('(calculated)');
  });

  it('links the single node of a losses charge once', () => {
    const part = consumption.calculation.parts[0]!;
    open({
      ...losses,
      calculation: { ...losses.calculation, nodeMeterId: 'qa', parts: [{ ...part, nodeMeterId: 'qa' }] },
    });
    expect(screen.getAllByRole('link', { name: 'Node' })).toHaveLength(1);
  });
});
