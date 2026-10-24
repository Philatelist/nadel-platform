import type { ReportInfo, ReportPreview, ReportSheet } from '@nadel/api-client';

export const chargesByMonthInfo: ReportInfo = {
  code: 'charges-by-month',
  personalData: false,
  filters: [
    {
      key: 'period',
      type: 'MONTH_RANGE',
      params: ['fromMonth', 'toMonth'],
      required: true,
      defaults: [
        { param: 'fromMonth', value: '2026-01' },
        { param: 'toMonth', value: '2026-03' },
      ],
      basis: 'BILLING_MONTHS',
    },
    { key: 'chargeTypes', type: 'CHARGE_TYPES', params: ['chargeTypeIds'], required: false, defaults: [] },
    { key: 'street', type: 'STREET', params: ['street'], required: false, defaults: [] },
  ],
};

export const electricity = {
  id: 'ct-1',
  code: 'ELECTRICITY',
  name: 'Electricity',
  nameCustomized: false,
} as const;

export function sheet(overrides: Partial<ReportSheet> = {}): ReportSheet {
  return {
    key: 'byBillingMonth',
    columns: [
      { key: 'month', type: 'MONTH' },
      { key: 'status', type: 'CODE' },
      { key: 'chargeType', type: 'CHARGE_TYPE' },
      { key: 'plots', type: 'INTEGER' },
      { key: 'regular', type: 'MONEY' },
    ],
    rows: [
      {
        kind: 'DETAIL',
        level: 0,
        cells: [
          { value: '2026-01' },
          { value: 'CLOSED' },
          { value: 'ct-1' },
          { value: '12' },
          { value: '1250.50' },
        ],
      },
      {
        kind: 'SUBTOTAL',
        level: 0,
        cells: [{ value: '2026-01' }, { value: 'CLOSED' }, {}, {}, { value: '1250.50' }],
      },
    ],
    totals: [{ key: 'all', cells: [{}, {}, {}, {}, { value: '1250.50' }] }],
    rowCount: 2,
    truncated: false,
    ...overrides,
  };
}

export function preview(overrides: Partial<ReportPreview> = {}): ReportPreview {
  return {
    code: 'charges-by-month',
    generatedAt: '2026-04-01T09:00:00Z',
    filters: [],
    headerNotes: [],
    chargeTypes: [electricity],
    sheets: [sheet()],
    ...overrides,
  };
}
