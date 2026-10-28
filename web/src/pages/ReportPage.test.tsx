import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { chargesByMonthInfo, electricity, preview, sheet } from '../test/reportFixtures';
import { renderWithApp } from '../test/render';
import { Route, Routes } from 'react-router';
import { ReportPage } from './ReportPage';

let reports: unknown[] = [];
let report: Record<string, unknown> = {};
const getReportHook = vi.fn();
const downloadReport = vi.fn();
const downloadBlob = vi.fn();

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListReports: () => queryResult(reports),
  useListChargeTypes: () => queryResult([electricity]),
  useListBillingMonths: () => queryResult([]),
  useGetReport: (...args: unknown[]) => {
    getReportHook(...args);
    return report;
  },
  downloadReport: (...args: unknown[]) => downloadReport(...args),
}));

const balancesInfo = {
  code: 'plot-balances',
  personalData: false,
  filters: [
    { key: 'asOf', type: 'DATE', params: ['asOf'], required: true, defaults: [] },
    { key: 'statuses', type: 'STATUSES', params: ['statuses'], required: false, defaults: [] },
    { key: 'minDebt', type: 'AMOUNT', params: ['minDebt'], required: false, defaults: [] },
  ],
};

const paymentsInfo = {
  code: 'payments',
  personalData: false,
  filters: [
    {
      key: 'period',
      type: 'MONTH_RANGE',
      params: ['fromMonth', 'toMonth'],
      required: true,
      defaults: [],
      basis: 'BILLING_MONTHS',
    },
    {
      key: 'days',
      type: 'DATE_RANGE',
      params: ['from', 'to'],
      required: false,
      defaults: [],
      basis: 'PAYMENT_DATES',
    },
    { key: 'source', type: 'SOURCE', params: ['source'], required: false, defaults: [] },
  ],
};

const electricityTreeInfo = {
  code: 'electricity-tree',
  personalData: false,
  filters: [
    {
      key: 'period',
      type: 'DATE_RANGE',
      params: ['from', 'to'],
      required: true,
      defaults: [
        { param: 'from', value: '2026-09-01' },
        { param: 'to', value: '2026-09-30' },
      ],
      basis: 'SERVICE_DAYS',
    },
  ],
};

function ok(data: unknown) {
  return { data, error: null, isPending: false, isError: false, refetch: vi.fn() };
}

function renderReport(route = '/reports/charges-by-month') {
  renderWithApp(
    <Routes>
      <Route path="reports/:code" element={<ReportPage />} />
    </Routes>,
    { route },
  );
}

describe('ReportPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    reports = [chargesByMonthInfo];
    report = ok(preview());
    getReportHook.mockReset();
    downloadReport.mockReset();
    downloadBlob.mockReset();
  });

  it('builds the filter form from the definitions, filled with their defaults', () => {
    renderReport();
    expect(screen.getByLabelText('From month')).toHaveValue('2026-01');
    expect(screen.getByLabelText('To month')).toHaveValue('2026-03');
    // A month filter is a select of billing months, never the browser's empty month mask.
    expect(screen.getByLabelText('From month').tagName).toBe('SELECT');
    expect(screen.getByLabelText('From month')).not.toHaveAttribute('type', 'month');
    expect(screen.getByLabelText('Street')).toHaveValue('');
    expect(screen.getByRole('checkbox', { name: 'Electricity' })).not.toBeChecked();
    expect(screen.getByText('The period is counted in billing months.')).toBeInTheDocument();
  });

  it('runs the preview with the filters of the URL and puts a submitted form into the URL', async () => {
    renderReport('/reports/charges-by-month?fromMonth=2026-02&toMonth=2026-02&chargeTypeIds=ct-1');
    expect(getReportHook.mock.calls[0]?.[0]).toBe('charges-by-month');
    expect(getReportHook.mock.calls[0]?.[1]).toEqual({
      fromMonth: '2026-02',
      toMonth: '2026-02',
      chargeTypeIds: ['ct-1'],
    });
    expect(screen.getByRole('checkbox', { name: 'Electricity' })).toBeChecked();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Show' }));
    expect(getReportHook.mock.calls.at(-1)?.[1]).toEqual({
      fromMonth: '2026-02',
      toMonth: '2026-02',
      chargeTypeIds: ['ct-1'],
    });
  });

  it('formats cells with the shared formatters and names the subtotal and totals lines', () => {
    renderReport();
    const table = screen.getByRole('table', { name: 'By billing month' });
    expect(within(table).getByRole('columnheader', { name: 'Charged' })).toBeInTheDocument();
    const rows = within(table).getAllByRole('row');
    const detail = within(rows[1]!).getAllByRole('cell');
    expect(detail.map((cell) => cell.textContent)).toEqual([
      'Jan 2026',
      'Closed',
      'Electricity',
      '12',
      '₴1,250.50',
    ]);
    expect(within(rows[2]!).getByText('Subtotal')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Total')).toBeInTheDocument();
  });

  it('shows an unknown value as an empty marker with its reason, never as 0', () => {
    report = ok(
      preview({
        sheets: [
          sheet({
            rows: [
              {
                kind: 'DETAIL',
                level: 0,
                cells: [{ value: '2026-01' }, {}, { value: 'ct-1' }, {}, { reason: 'NO_READING' }],
              },
            ],
            totals: [],
            rowCount: 1,
          }),
        ],
      }),
    );
    renderReport();
    const marker = screen.getByTitle('No reading, so the value is not known');
    expect(marker).toHaveTextContent('—');
    expect(screen.queryByText(/₴0/)).not.toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('notes the row count only when the preview is truncated', () => {
    renderReport();
    expect(screen.queryByText(/Showing first/)).not.toBeInTheDocument();
    report = ok(preview({ sheets: [sheet({ truncated: true, rowCount: 250 })] }));
    renderReport();
    expect(
      screen.getByText('Showing first 2 of 250 rows. The Excel file has all of them.'),
    ).toBeInTheDocument();
  });

  it('renders header notes from their codes and says so when a sheet has no rows', () => {
    report = ok(
      preview({
        headerNotes: [{ code: 'SERVICE_PERIOD_SHEET', params: {} }],
        sheets: [sheet({ rows: [], totals: [], rowCount: 0 })],
      }),
    );
    renderReport();
    expect(screen.getByText(/groups the same charges by the month of the service/)).toBeInTheDocument();
    expect(screen.getByText('No rows for these filters.')).toBeInTheDocument();
  });

  it('downloads the file in the UI language under a name built from the period', async () => {
    const blob = new Blob(['x']);
    downloadReport.mockResolvedValue(blob);
    renderReport('/reports/charges-by-month?fromMonth=2026-01&toMonth=2026-03');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download Excel' }));
    await vi.waitFor(() =>
      expect(downloadBlob).toHaveBeenCalledWith(blob, 'charges-by-month_2026-01-01_2026-03-31.xlsx'),
    );
    expect(downloadReport).toHaveBeenCalledWith('charges-by-month', {
      fromMonth: '2026-01',
      toMonth: '2026-03',
      lang: 'en',
    });
  });

  it('keeps the source and the received days of the payments report in the URL and names the file by the days', async () => {
    const blob = new Blob(['x']);
    downloadReport.mockResolvedValue(blob);
    reports = [paymentsInfo];
    report = ok(preview({ code: 'payments', sheets: [] }));
    renderReport(
      '/reports/payments?fromMonth=2026-10&toMonth=2026-10&source=CASH&from=2026-10-05&to=2026-10-20',
    );
    expect(getReportHook.mock.calls[0]?.[1]).toEqual({
      fromMonth: '2026-10',
      toMonth: '2026-10',
      source: 'CASH',
      from: '2026-10-05',
      to: '2026-10-20',
    });
    expect(screen.getByLabelText('Source')).toHaveValue('CASH');
    expect(within(screen.getByLabelText('Source')).getAllByRole('option')).toHaveLength(3);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download Excel' }));
    await vi.waitFor(() =>
      expect(downloadBlob).toHaveBeenCalledWith(blob, 'payments_2026-10-05_2026-10-20.xlsx'),
    );
  });

  it('says the report is too large for REPORT_TOO_LARGE', () => {
    report = {
      data: undefined,
      error: new ApiError(422, { code: 'REPORT_TOO_LARGE' }),
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    };
    renderReport();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The report has more than 20 000 rows on a sheet. Narrow the filters and try again.',
    );
  });

  it('offers no file and no table for a report that is too large', () => {
    report = {
      data: undefined,
      error: new ApiError(422, { code: 'REPORT_TOO_LARGE' }),
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    };
    renderReport();
    expect(screen.getByRole('button', { name: 'Download Excel' })).toBeDisabled();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(downloadReport).not.toHaveBeenCalled();
  });

  it('says the same when the file itself is refused as too large, and saves nothing', async () => {
    downloadReport.mockRejectedValue(new ApiError(422, { code: 'REPORT_TOO_LARGE' }));
    renderReport();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Download Excel' }));
    expect(
      await screen.findByText(
        'The report has more than 20 000 rows on a sheet. Narrow the filters and try again.',
      ),
    ).toBeInTheDocument();
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it('shows the plot status as its text, the per-type debt columns by charge type name, and the new notes', () => {
    reports = [balancesInfo];
    report = ok(
      preview({
        code: 'plot-balances',
        headerNotes: [
          { code: 'PAST_DATE_STATUS', params: {} },
          { code: 'STATUS_ALL_CHARGE_TYPES', params: {} },
          { code: 'OLDEST_UNPAID_IS_OLDEST_CHARGE', params: {} },
        ],
        sheets: [
          sheet({
            key: 'balances',
            columns: [
              { key: 'status', type: 'CODE' },
              { key: 'debt.ct-1', type: 'MONEY', chargeTypeId: 'ct-1' },
            ],
            rows: [{ kind: 'DETAIL', level: 0, cells: [{ value: 'RED' }, { value: '10.00' }] }],
            totals: [],
          }),
        ],
      }),
    );
    renderReport('/reports/plot-balances');
    const table = screen.getByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'Electricity: debt' })).toBeInTheDocument();
    expect(within(table).getByText('Debt above threshold')).toBeInTheDocument();
    expect(within(table).queryByText('RED')).not.toBeInTheDocument();
    expect(screen.getByText(/worked out as of the chosen day/)).toBeInTheDocument();
    expect(screen.getByText(/counts every charge type/)).toBeInTheDocument();
    expect(screen.getByText(/oldest charge that still has a debt/)).toBeInTheDocument();
  });

  it('sends asOf, statuses and minDebt as the query parameters', () => {
    reports = [balancesInfo];
    renderReport('/reports/plot-balances?asOf=2026-03-31&statuses=RED&statuses=YELLOW&minDebt=100.00');
    expect(getReportHook.mock.calls[0]?.[1]).toEqual({
      asOf: '2026-03-31',
      statuses: ['RED', 'YELLOW'],
      minDebt: '100.00',
    });
    expect(screen.getByLabelText('As of')).toHaveValue('2026-03-31');
    expect(screen.getByRole('checkbox', { name: 'Debt above threshold' })).toBeChecked();
    expect(screen.getByLabelText('Debt from')).toHaveValue('100.00');
  });

  it('keeps line breaks of a multi-owner text cell', () => {
    reports = [balancesInfo];
    report = ok(
      preview({
        code: 'plot-balances',
        sheets: [
          sheet({
            key: 'balances',
            columns: [{ key: 'owners', type: 'TEXT' }],
            rows: [{ kind: 'DETAIL', level: 0, cells: [{ value: 'A. One\nB. Two' }] }],
            totals: [],
          }),
        ],
      }),
    );
    renderReport('/reports/plot-balances');
    expect(screen.getByText(/A\. One/).closest('td')).toHaveClass('whitespace-pre-line');
  });

  it('shows the codes of the electricity report as texts, indents the tree and names the totals per source', () => {
    reports = [electricityTreeInfo];
    report = ok(
      preview({
        code: 'electricity-tree',
        headerNotes: [{ code: 'LOSSES_FIXED_PERCENT', params: { percent: '5.00' } }],
        sheets: [
          sheet({
            key: 'consumption',
            columns: [
              { key: 'name', type: 'TEXT' },
              { key: 'nodeKind', type: 'CODE' },
              { key: 'startKind', type: 'CODE' },
              { key: 'consumption', type: 'QUANTITY' },
            ],
            rows: [
              {
                kind: 'DETAIL',
                level: 0,
                cells: [{ value: 'ТП-1' }, { value: 'SUBSTATION' }, { value: 'REAL' }, { value: '2300.000' }],
              },
              {
                kind: 'DETAIL',
                level: 1,
                cells: [
                  { value: 'Квартал Б' },
                  { value: 'QUARTER' },
                  { value: 'UNKNOWN' },
                  { reason: 'DERIVED_FROM_UNKNOWN' },
                ],
              },
            ],
            totals: [],
          }),
          sheet({
            key: 'charged',
            columns: [
              { key: 'source', type: 'CODE' },
              { key: 'rowKind', type: 'CODE' },
              { key: 'runStatus', type: 'CODE' },
              { key: 'outcome', type: 'CODE' },
              { key: 'split', type: 'CODE' },
              { key: 'rate', type: 'DECIMAL' },
              { key: 'amount', type: 'MONEY' },
            ],
            rows: [
              {
                kind: 'DETAIL',
                level: 0,
                cells: [
                  { value: 'COMMON_CONSUMER' },
                  { value: 'RUN' },
                  { value: 'REVERSED' },
                  { value: 'NOT_CHARGED' },
                  { value: 'EQUAL_PER_PLOT' },
                  {},
                  {},
                ],
              },
              {
                kind: 'DETAIL',
                level: 0,
                cells: [
                  { value: 'LOSSES' },
                  { value: 'ADJUSTMENT' },
                  {},
                  { value: 'SKIPPED_ABOVE' },
                  {},
                  { reason: 'SEVERAL_RATES' },
                  { value: '-17.28' },
                ],
              },
            ],
            totals: [{ key: 'CONSUMPTION', cells: [{}, {}, {}, {}, {}, {}, { value: '280.80' }] }],
          }),
        ],
      }),
    );
    renderReport('/reports/electricity-tree');
    const tree = screen.getByRole('table', { name: 'Consumption' });
    const rows = within(tree).getAllByRole('row');
    expect(
      within(rows[1]!)
        .getAllByRole('cell')
        .map((cell) => cell.textContent),
    ).toEqual(['ТП-1', 'Substation', 'Real', '2,300']);
    const quarter = within(rows[2]!).getAllByRole('cell');
    expect(quarter.map((cell) => cell.textContent)).toEqual(['Квартал Б', 'Quarter', 'Unknown', '—']);
    expect(quarter[0]?.style.paddingLeft).toBe('2rem');
    expect(within(tree).getByTitle('Worked out from a value that is not known')).toBeInTheDocument();
    const charged = screen.getByRole('table', { name: 'Charged' });
    for (const text of [
      'Common consumers',
      'Run',
      'Reversed',
      'Not charged',
      'Equally per plot',
      'Losses',
      'Adjustment',
      'Skipped',
      'Consumption of plots',
    ]) {
      expect(within(charged).getByText(text)).toBeInTheDocument();
    }
    for (const code of [
      'COMMON_CONSUMER',
      'RUN',
      'REVERSED',
      'EQUAL_PER_PLOT',
      'ADJUSTMENT',
      'SKIPPED_ABOVE',
    ]) {
      expect(within(charged).queryByText(code)).not.toBeInTheDocument();
    }
    expect(
      within(charged).getByTitle('Priced at more than one rate; the amount is exact'),
    ).toBeInTheDocument();
    expect(screen.getByText(/charged as a fixed 5.00 % of each interval/)).toBeInTheDocument();
  });

  it('shows the water sources as texts on both sheets of the water report', () => {
    reports = [{ ...electricityTreeInfo, code: 'water' }];
    const sourceSheet = (key: string) =>
      sheet({
        key,
        columns: [
          { key: 'source', type: 'CODE' },
          { key: 'sourceName', type: 'TEXT' },
          { key: 'cubicMetres', type: 'QUANTITY' },
        ],
        rows: [
          {
            kind: 'DETAIL',
            level: 0,
            cells: [{ value: 'COOPERATIVE_SUPPLY' }, {}, { reason: 'NO_READING' }],
          },
          { kind: 'DETAIL', level: 0, cells: [{ value: 'METER' }, { value: 'Свердловина 1' }, {}] },
        ],
        totals: [],
      });
    report = ok(preview({ code: 'water', sheets: [sourceSheet('main'), sourceSheet('summary')] }));
    renderReport('/reports/water');
    for (const name of ['Water by plot', 'By source']) {
      const table = screen.getByRole('table', { name });
      expect(within(table).getByRole('cell', { name: 'Cooperative supply' })).toBeInTheDocument();
      expect(within(table).getByRole('cell', { name: 'Well' })).toBeInTheDocument();
      expect(within(table).queryByText('METER')).not.toBeInTheDocument();
      expect(within(table).getByTitle('No reading, so the value is not known')).toBeInTheDocument();
    }
  });

  it('shows the missing readings codes as texts and links the open "not charged" note to the queue', () => {
    reports = [{ ...electricityTreeInfo, code: 'missing-readings' }];
    report = ok(
      preview({
        code: 'missing-readings',
        headerNotes: [{ code: 'OPEN_NOT_CHARGED', params: { count: '3' } }],
        sheets: [
          sheet({
            key: 'main',
            columns: [
              { key: 'resource', type: 'CODE' },
              { key: 'kind', type: 'CODE' },
              { key: 'name', type: 'TEXT' },
              { key: 'pending', type: 'BOOLEAN' },
            ],
            rows: [
              {
                kind: 'DETAIL',
                level: 0,
                cells: [{ value: 'WATER' }, { value: 'PLOT' }, { value: '12а' }, { value: 'true' }],
              },
              {
                kind: 'DETAIL',
                level: 0,
                cells: [
                  { value: 'ELECTRICITY' },
                  { value: 'QUARTER' },
                  { value: 'Квартал А' },
                  { value: 'false' },
                ],
              },
            ],
            totals: [],
          }),
        ],
      }),
    );
    renderReport('/reports/missing-readings');
    const table = screen.getByRole('table', { name: 'Missing readings' });
    expect(
      within(within(table).getAllByRole('row')[1]!)
        .getAllByRole('cell')
        .map((cell) => cell.textContent),
    ).toEqual(['Water', 'Plot', '12а', 'Yes']);
    expect(within(table).getByText('Quarter')).toBeInTheDocument();
    expect(within(table).queryByText('ELECTRICITY')).not.toBeInTheDocument();
    expect(screen.getByText(/Open “not charged” items of the listed meters: 3\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open “Not charged”' })).toHaveAttribute(
      'href',
      '/charges/not-charged',
    );
  });

  it('renders the no-access page for a code the list does not contain', () => {
    reports = [];
    renderReport();
    expect(screen.getByRole('heading', { name: "You don't have access to this page" })).toBeInTheDocument();
  });

  it('renders the no-access page for /reports/debtors when the list leaves debtors out', () => {
    reports = [chargesByMonthInfo];
    renderReport('/reports/debtors');
    expect(screen.getByRole('heading', { name: "You don't have access to this page" })).toBeInTheDocument();
  });
});
