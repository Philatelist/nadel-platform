import type { Charge } from '@nadel/api-client';

export const charge: Charge = {
  id: 'c1',
  plotId: 'p7',
  kind: 'REGULAR',
  source: 'CONSUMPTION',
  chargeType: { id: 't1', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false },
  amount: '403.06',
  billingMonth: '2026-10',
  billingMonthClosed: false,
  serviceFrom: '2026-09-23',
  serviceTo: '2026-10-04',
  accruedOn: '2026-10-04',
  reversible: true,
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
    readings: [{ meterId: 'm1', takenOn: '2026-10-04', value: '8205.000', calculated: true }],
  },
  createdAt: '2026-10-05T09:00:00Z',
  createdByName: 'Іваненко Петро',
};

/** A booked adjustment of `charge` by a recalculation: −5.00 for the billing month of the original. */
export const adjustment: Charge = {
  ...charge,
  id: 'a1',
  kind: 'ADJUSTMENT',
  source: 'RECALCULATION',
  amount: '-5.00',
  billingMonth: '2026-10',
  reversible: false,
  reason: 'Rate corrected',
  adjusts: { chargeId: 'c1', billingMonth: '2026-09', serviceFrom: '2026-09-01', serviceTo: '2026-09-30' },
  recalculationId: 'r1',
  calculation: { amount: '-5.00', parts: [], readings: [] },
};
