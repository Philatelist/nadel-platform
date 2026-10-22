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
