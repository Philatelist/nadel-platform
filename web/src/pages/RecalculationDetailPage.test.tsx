import type { Recalculation, RecalculationLine } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { RecalculationDetailPage } from './RecalculationDetailPage';

const useGetRecalculation = vi.fn();

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: ['CHARGES_VIEW'] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetRecalculation: (...args: unknown[]): unknown => useGetRecalculation(...args),
}));

const type = { id: 't2', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false } as const;

const line = (over: Partial<RecalculationLine>): RecalculationLine => ({
  plotId: 'p7',
  plotNumber: '7',
  chargeType: type,
  serviceFrom: '2026-09-01',
  serviceTo: '2026-09-30',
  originalChargeId: 'c1',
  booked: '30.00',
  adjustments: '0.00',
  effective: '30.00',
  expected: '25.00',
  difference: '-5.00',
  action: 'ADJUST',
  reasons: ['TARIFF_VERSION'],
  createdChargeIds: ['a1'],
  adjustmentChargeId: 'a1',
  ...over,
});

const recalculation: Recalculation = {
  id: 'r1',
  preview: false,
  booked: true,
  changeApplied: false,
  cause: 'MANUAL',
  causeType: 'MANUAL',
  scope: { months: ['2026-09', '2026-10'], chargeTypeIds: ['t2'], plotIds: ['p7', 'p8'] },
  reason: 'Membership rate corrected',
  lines: [
    line({}),
    line({
      serviceFrom: '2026-10-01',
      serviceTo: '2026-10-31',
      action: 'REVERSE_AND_RECHARGE',
      reasons: ['TARIFF_VERSION', 'AREA'],
      reversalChargeId: 'x1',
      newChargeId: 'x2',
      adjustmentChargeId: undefined,
    }),
    line({
      plotId: 'p8',
      plotNumber: '8',
      action: 'NEW',
      originalChargeId: undefined,
      reasons: ['NOT_CHARGED'],
    }),
  ],
  totals: [
    {
      chargeTypeId: 't2',
      chargeTypeCode: 'MEMBERSHIP',
      name: 'Membership',
      nameCustomized: false,
      amount: '-15.00',
    },
  ],
  total: '-15.00',
  plotCount: 2,
  notCharged: [],
  digest: 'd',
  createdAt: '2026-10-05T09:00:00Z',
  createdByName: 'Іваненко Петро',
};

function render() {
  return renderWithApp(
    <Routes>
      <Route path="/charges/recalculations/:id" element={<RecalculationDetailPage />} />
    </Routes>,
    { route: '/charges/recalculations/r1' },
  );
}

describe('RecalculationDetailPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    useGetRecalculation.mockReset();
    useGetRecalculation.mockReturnValue(queryResult(recalculation));
  });

  it('shows the scope, the reason as user content and the totals', () => {
    render();
    expect(useGetRecalculation).toHaveBeenCalledWith('r1');
    expect(screen.getByText('Membership rate corrected')).toHaveAttribute('data-user-content');
    expect(screen.getByText('Sep 2026, Oct 2026')).toBeInTheDocument();
    expect(screen.getAllByText('−₴15.00').length).toBeGreaterThanOrEqual(2);
  });

  it('groups lines by plot then month with amounts, action, reasons and the plot account link', () => {
    render();
    const section = screen.getByRole('region', { name: 'Recalculation lines' });
    const headings = within(section).getAllByRole('heading', { level: 2 });
    expect(headings.map((h) => h.textContent)).toEqual(['Plot 7', 'Plot 8']);
    expect(within(section).getAllByRole('link', { name: 'Plot account' })[0]).toHaveAttribute(
      'href',
      '/plots/p7',
    );
    expect(
      within(section)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Sep 2026', 'Oct 2026', 'Sep 2026']);
    const rows = within(section).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Adjustment');
    expect(rows[1]).toHaveTextContent('₴30.00');
    expect(rows[1]).toHaveTextContent('₴25.00');
    expect(rows[1]).toHaveTextContent('−₴5.00');
    expect(rows[1]).toHaveTextContent('A tariff version changed');
    expect(rows[3]).toHaveTextContent('Reversal and new charge');
    expect(rows[3]).toHaveTextContent('The plot area changed');
    expect(rows[3]).toHaveTextContent('Reversal, New charge');
  });
});
