import type { BillingMonthDetails, BillingMonthSummary } from '@nadel/api-client';

export const openMonth: BillingMonthDetails = {
  month: '2026-09',
  status: 'OPEN',
  runDone: false,
  totals: [
    {
      chargeTypeId: 't1',
      chargeTypeCode: 'ELECTRICITY',
      name: 'Electricity',
      nameCustomized: false,
      amount: '403.06',
    },
    {
      chargeTypeId: 't2',
      chargeTypeCode: 'MEMBERSHIP',
      name: 'Membership',
      nameCustomized: false,
      amount: '1250.50',
    },
  ],
  total: '1653.56',
  checklist: {
    runDone: false,
    notChargedCount: 3,
    staleMeters: [
      { meterId: 'm1', plotId: 'p7', plotNumber: '7', resource: 'ELECTRICITY', lastReadingOn: '2026-06-01' },
    ],
  },
};

export const closedMonth: BillingMonthDetails = {
  ...openMonth,
  month: '2026-08',
  status: 'CLOSED',
  closedAt: '2026-09-02T10:00:00Z',
  closedByName: 'Іваненко Петро',
};

export const summaries: BillingMonthSummary[] = [
  { month: '2026-09', status: 'OPEN', runDone: false, totals: openMonth.totals, total: openMonth.total },
  { month: '2026-08', status: 'CLOSED', runDone: true, totals: [], total: '0.00' },
];
