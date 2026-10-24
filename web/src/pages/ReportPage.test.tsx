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
  useGetReport: (...args: unknown[]) => {
    getReportHook(...args);
    return report;
  },
  downloadReport: (...args: unknown[]) => downloadReport(...args),
}));

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

  it('renders the no-access page for a code the list does not contain', () => {
    reports = [];
    renderReport();
    expect(screen.getByRole('heading', { name: "You don't have access to this page" })).toBeInTheDocument();
  });
});
