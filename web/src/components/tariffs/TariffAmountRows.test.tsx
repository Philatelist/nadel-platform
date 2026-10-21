import { screen } from '@testing-library/react';
import type { PlotTariffItem, PlotTariffs } from '@nadel/api-client';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderWithApp } from '../../test/render';
import { TariffAmountRows } from './TariffAmountRows';
import { hasText } from '../../test/hasText';

const base: PlotTariffItem = {
  tariffId: 't1',
  tariffName: 'Membership 2026',
  chargeTypeId: 'c1',
  chargeTypeCode: 'MEMBERSHIP',
  chargeTypeName: 'Membership fee',
  chargeTypeNameCustomized: false,
  basis: 'PER_AREA_SOTKA',
  frequency: 'MONTHLY',
  rate: '4.3200',
  status: 'AMOUNT',
  amount: '27.50',
};
const data: PlotTariffs = {
  month: '2026-10',
  total: '27.50',
  items: [
    base,
    {
      ...base,
      tariffId: 't2',
      tariffName: 'Power',
      chargeTypeCode: 'ELECTRICITY',
      chargeTypeName: 'Electricity',
      basis: 'PER_KWH',
      status: 'BY_READINGS',
      amount: undefined,
    },
    {
      ...base,
      tariffId: 't3',
      tariffName: 'Road works 2026',
      chargeTypeCode: undefined,
      chargeTypeName: 'Road repair',
      chargeTypeNameCustomized: true,
      status: 'EXCLUDED',
      amount: undefined,
      reason: 'Paid in advance',
    },
  ],
};

describe('TariffAmountRows', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('shows an amount, by readings, an exclusion with its reason and the server total', () => {
    renderWithApp(<TariffAmountRows data={data} />);
    expect(screen.getByText('Membership fee')).toBeInTheDocument();
    expect(screen.getByText('Membership 2026')).toBeInTheDocument();
    expect(screen.getAllByText(/₴4\.32 · Per sotka · Monthly/)[0]).toBeInTheDocument();
    expect(screen.getByText('by meter readings')).toBeInTheDocument();
    expect(screen.getByText(hasText('not charged — Paid in advance'))).toBeInTheDocument();
    expect(screen.getByText('Road repair')).toHaveAttribute('data-user-content');
    const total = screen.getByText('Total').parentElement;
    expect(total).toHaveTextContent('27');
  });

  it('shows the seeded charge type name in the active language', () => {
    window.localStorage.setItem('nadel.locale', 'uk');
    renderWithApp(<TariffAmountRows data={data} />);
    expect(screen.getByText('Членський внесок')).toBeInTheDocument();
    expect(screen.getByText('Електроенергія')).toBeInTheDocument();
    expect(screen.getByText('за показаннями')).toBeInTheDocument();
    expect(screen.getByText(hasText('не нараховується — Paid in advance'))).toBeInTheDocument();
    expect(screen.getByText('Разом')).toBeInTheDocument();
  });
});
