import type { LossesRunNode, LossesRunResult, LossesRunSummary } from '@nadel/api-client';

export const chargedNode: LossesRunNode = {
  meterId: 'qa',
  kind: 'QUARTER',
  name: 'Quarter A',
  consumption: '1100.000',
  children: '1000.000',
  losses: '100.000',
  pct: '9.09',
  calculated: true,
  outcome: 'CHARGED',
  plots: [
    { plotId: 'p1', plotNumber: '1', kwh: '60.000', amount: '259.20' },
    { plotId: 'p2', plotNumber: '2', kwh: '40.000', amount: '172.80' },
  ],
  amount: '432.00',
};

export const negativeNode: LossesRunNode = {
  meterId: 'qb',
  kind: 'QUARTER',
  name: 'Quarter B',
  consumption: '950.000',
  children: '1000.000',
  losses: '-50.000',
  pct: '-5.00',
  calculated: false,
  outcome: 'NEGATIVE',
  warning: { code: 'NEGATIVE_LOSSES', meterId: 'qb' },
  plots: [],
  amount: '0.00',
};

export const unknownNode: LossesRunNode = {
  meterId: 'qc',
  kind: 'QUARTER',
  name: 'Quarter C',
  calculated: false,
  outcome: 'UNKNOWN',
  warning: {
    code: 'UNKNOWN_VALUE',
    meterId: 'm9',
    meterKind: 'PLOT',
    plotNumber: '9',
    lastReadingOn: '2026-09-30',
  },
  plots: [],
  amount: '0.00',
};

export const alreadyChargedNode: LossesRunNode = {
  meterId: 'qd',
  kind: 'QUARTER',
  name: 'Quarter D',
  calculated: false,
  outcome: 'ALREADY_CHARGED',
  warning: { code: 'ALREADY_CHARGED', meterId: 'qd', runId: 'r0', from: '2026-10-01', to: '2026-10-31' },
  plots: [],
  amount: '0.00',
};

export const lossesPreview: LossesRunResult = {
  from: '2026-10-01',
  to: '2026-10-23',
  preview: true,
  billingMonth: '2026-10',
  nodes: [chargedNode, negativeNode, unknownNode],
  charges: [
    { plotId: 'p1', plotNumber: '1', kwh: '60.000', amount: '259.20' },
    { plotId: 'p2', plotNumber: '2', kwh: '40.000', amount: '172.80' },
  ],
  plots: 2,
  total: '432.00',
  created: 0,
  digest: 'd1',
};

export const bookedRun: LossesRunSummary = {
  id: 'r1',
  from: '2026-10-01',
  to: '2026-10-15',
  status: 'BOOKED',
  billingMonth: '2026-10',
  createdAt: '2026-10-16T09:30:00Z',
  createdByName: 'Іваненко Петро',
  total: '432.00',
  plots: 2,
  chargedNodes: 1,
  skippedNodes: 2,
  reversible: true,
};

export const reversedRun: LossesRunSummary = {
  ...bookedRun,
  id: 'r2',
  from: '2026-09-01',
  to: '2026-09-30',
  billingMonth: '2026-09',
  status: 'REVERSED',
  reversible: false,
  reversedAt: '2026-10-02T08:00:00Z',
  reversedByName: 'Шевченко Олена',
  reverseReason: 'Wrong meter',
};
