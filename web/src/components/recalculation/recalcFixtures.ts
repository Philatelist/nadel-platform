import type { Recalculation, RecalculationLine } from '@nadel/api-client';

const membership = { id: 'ct1', code: 'MEMBERSHIP', name: 'Membership fee', nameCustomized: false } as const;
const electricity = { id: 'ct2', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false } as const;

export const line = (over: Partial<RecalculationLine> = {}): RecalculationLine => ({
  plotId: 'p1',
  plotNumber: '12',
  chargeType: membership,
  serviceFrom: '2026-09-01',
  serviceTo: '2026-09-30',
  originalChargeId: 'c1',
  booked: '30.00',
  adjustments: '0.00',
  effective: '30.00',
  expected: '25.00',
  difference: '-5.00',
  action: 'ADJUST',
  bookedIn: '2026-10',
  reasons: ['TARIFF_VERSION'],
  createdChargeIds: [],
  ...over,
});

export const preview = (over: Partial<Recalculation> = {}): Recalculation => ({
  preview: true,
  booked: false,
  changeApplied: false,
  cause: 'MANUAL',
  causeType: 'MANUAL',
  scope: { months: ['2026-09', '2026-10'], chargeTypeIds: ['ct1'], plotIds: ['p1', 'p2'] },
  lines: [
    line(),
    line({
      serviceFrom: '2026-10-01',
      serviceTo: '2026-10-31',
      action: 'REVERSE_AND_RECHARGE',
      difference: '-5.00',
    }),
    line({
      plotId: 'p2',
      plotNumber: '14',
      chargeType: electricity,
      booked: '100.00',
      adjustments: '-3.00',
      effective: '97.00',
      expected: '90.00',
      difference: '-7.00',
    }),
  ],
  totals: [
    {
      chargeTypeId: 'ct1',
      chargeTypeCode: 'MEMBERSHIP',
      name: 'Membership fee',
      nameCustomized: false,
      amount: '-10.00',
    },
    {
      chargeTypeId: 'ct2',
      chargeTypeCode: 'ELECTRICITY',
      name: 'Electricity',
      nameCustomized: false,
      amount: '-7.00',
    },
  ],
  total: '-17.00',
  plotCount: 2,
  notCharged: [],
  digest: 'digest-1',
  ...over,
});
