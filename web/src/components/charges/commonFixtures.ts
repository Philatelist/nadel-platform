import type {
  CommonConsumerRunLine,
  CommonConsumerRunResult,
  CommonConsumerRunSummary,
} from '@nadel/api-client';

export const chargedWell: CommonConsumerRunLine = {
  meterId: 'w1',
  name: 'Well pump',
  split: 'CONNECTED_PLOTS',
  consumption: '1000.000',
  calculated: true,
  outcome: 'CHARGED',
  plotDays: 46,
  plots: [
    { plotId: 'p1', plotNumber: '1', days: 23, kwh: '500.000', amount: '2160.00' },
    { plotId: 'p2', plotNumber: '2', days: 23, kwh: '500.000', amount: '2160.00' },
  ],
  amount: '4320.00',
};

export const unknownLight: CommonConsumerRunLine = {
  meterId: 'l1',
  name: 'Street lighting',
  split: 'EQUAL_PER_PLOT',
  calculated: false,
  outcome: 'UNKNOWN',
  warning: { code: 'UNKNOWN_VALUE', meterId: 'l1', lastReadingOn: '2026-09-30' },
  plotDays: 0,
  plots: [],
  amount: '0.00',
};

export const chargedBefore: CommonConsumerRunLine = {
  meterId: 'g1',
  name: 'Gate',
  split: 'EQUAL_PER_PLOT',
  calculated: false,
  outcome: 'ALREADY_CHARGED',
  warning: { code: 'ALREADY_CHARGED', meterId: 'g1', runId: 'r0', from: '2026-10-01', to: '2026-10-31' },
  plotDays: 0,
  plots: [],
  amount: '0.00',
};

export const commonPreview: CommonConsumerRunResult = {
  from: '2026-10-01',
  to: '2026-10-23',
  preview: true,
  billingMonth: '2026-10',
  noTariff: false,
  consumers: [chargedWell, unknownLight],
  plots: 2,
  charges: 2,
  total: '4320.00',
  created: 0,
  digest: 'd1',
};

export const commonRun: CommonConsumerRunSummary = {
  id: 'r1',
  from: '2026-10-01',
  to: '2026-10-15',
  status: 'BOOKED',
  billingMonth: '2026-10',
  createdAt: '2026-10-16T09:30:00Z',
  createdByName: 'Іваненко Петро',
  total: '4320.00',
  plots: 2,
  charges: 2,
  chargedConsumers: 1,
  skippedConsumers: 1,
  reversible: true,
};
