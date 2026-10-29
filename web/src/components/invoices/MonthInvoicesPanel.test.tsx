import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type InvoiceBuild, type InvoiceRow, type Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MonthInvoicesPanel, monthPrintFileName } from './MonthInvoicesPanel';

let permissions: Permission[];
const buildMonthInvoices = vi.fn();
const getMonthInvoicesPrintPdf = vi.fn();
const downloadBlob = vi.fn();
const listInvoices = vi.fn();

vi.mock('../../me', () => ({ useCan: (p: Permission) => permissions.includes(p) }));
vi.mock('../../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));

const row: InvoiceRow = {
  id: 'inv1',
  plotId: 'p1',
  plotNumber: '9',
  month: '2026-10',
  number: '2026-10/9',
  version: 1,
  due: '1250.50',
  issuedAt: '2026-10-28T09:00:00Z',
  latest: true,
};
const ok = (data: unknown) => ({ data, error: null, isPending: false, isError: false, refetch: vi.fn() });

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  buildMonthInvoices: (...args: unknown[]) => buildMonthInvoices(...args),
  getMonthInvoicesPrintPdf: (...args: unknown[]) => getMonthInvoicesPrintPdf(...args),
  useListInvoices: (...args: unknown[]) => listInvoices(...args),
  useGetInvoiceReadiness: () => ok({ missing: [] }),
}));

const previewResult: InvoiceBuild = {
  preview: true,
  invoiceable: 50,
  toBuild: 30,
  nothingDue: 14,
  existing: 8,
  built: 28,
  newVersions: 2,
  unchanged: 6,
  skipped: 14,
};
const previewLine = 'To be built: 28. New versions: 2. Unchanged: 6. Skipped: 14.';

function renderPanel() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MonthInvoicesPanel month="2026-10" />
    </QueryClientProvider>,
  );
}

describe('MonthInvoicesPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'INVOICES_ISSUE'];
    buildMonthInvoices.mockReset();
    getMonthInvoicesPrintPdf.mockReset();
    downloadBlob.mockReset();
    listInvoices.mockReset();
    listInvoices.mockReturnValue(ok({ items: [row], page: 0, size: 20, totalItems: 1, totalPages: 1 }));
    buildMonthInvoices.mockResolvedValue(previewResult);
  });

  it('shows the preview in the categories of the result and asks for it with the switch value', async () => {
    renderPanel();
    expect(await screen.findByText(previewLine)).toBeTruthy();
    expect(screen.getByText('Plots with an owner: 50')).toBeTruthy();
    expect(screen.getByText('Already invoiced for this month: 8')).toBeTruthy();
    expect(buildMonthInvoices).toHaveBeenCalledWith(
      '2026-10',
      { preview: true },
      { includeNothingDue: false },
    );
    await userEvent.click(screen.getByRole('checkbox', { name: 'Include invoices with nothing due' }));
    await waitFor(() =>
      expect(buildMonthInvoices).toHaveBeenCalledWith(
        '2026-10',
        { preview: true },
        { includeNothingDue: true },
      ),
    );
  });

  it('shows a spinner while building, then the result line', async () => {
    let finish: (v: InvoiceBuild) => void = () => undefined;
    buildMonthInvoices.mockImplementation((_m: string, params: { preview: boolean }) =>
      params.preview
        ? Promise.resolve(previewResult)
        : new Promise<InvoiceBuild>((resolve) => (finish = resolve)),
    );
    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: 'Build invoices for October 2026' }));
    expect(await screen.findByText('Building invoices…')).toBeTruthy();
    finish({ preview: false, invoiceable: 50, built: 30, newVersions: 2, unchanged: 6, skipped: 12 });
    expect(await screen.findByText('Built: 30. New versions: 2. Unchanged: 6. Skipped: 12.')).toBeTruthy();
    expect(screen.queryByText('Building invoices…')).toBeNull();
  });

  it('shows the result in place of the preview after a build until the switch changes', async () => {
    const after: InvoiceBuild = {
      ...previewResult,
      toBuild: 0,
      existing: 36,
      built: 0,
      newVersions: 0,
      unchanged: 36,
      skipped: 14,
    };
    let built = false;
    buildMonthInvoices.mockImplementation((_m: string, params: { preview: boolean }) => {
      if (!params.preview) {
        built = true;
        return Promise.resolve({
          preview: false,
          invoiceable: 50,
          built: 28,
          newVersions: 2,
          unchanged: 6,
          skipped: 14,
        });
      }
      return Promise.resolve(built ? after : previewResult);
    });
    renderPanel();
    expect(await screen.findByText(previewLine)).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Build invoices for October 2026' }));
    expect(await screen.findByText('Built: 28. New versions: 2. Unchanged: 6. Skipped: 14.')).toBeTruthy();
    // the preview is refetched after the build, but it is not shown next to the result
    await waitFor(() => expect(buildMonthInvoices).toHaveBeenCalledTimes(3));
    expect(screen.queryByText(previewLine)).toBeNull();
    expect(screen.queryByRole('list', { name: 'What the build will do' })).toBeNull();
    expect(screen.queryByText('Already invoiced for this month: 36')).toBeNull();

    // a change of the switch drops the result and shows the current preview
    await userEvent.click(screen.getByRole('checkbox', { name: 'Include invoices with nothing due' }));
    expect(await screen.findByText('Already invoiced for this month: 36')).toBeTruthy();
    expect(screen.queryByText('Built: 28. New versions: 2. Unchanged: 6. Skipped: 14.')).toBeNull();
  });

  it('lists the latest version per plot with its earlier versions collapsed', async () => {
    listInvoices.mockImplementation((params: { latestOnly?: boolean }) =>
      ok({
        items:
          params.latestOnly === true
            ? [{ ...row, version: 2 }]
            : [
                { ...row, version: 2 },
                { ...row, id: 'v1' },
              ],
        page: 0,
        size: 20,
        totalItems: 1,
        totalPages: 1,
      }),
    );
    renderPanel();
    expect(listInvoices).toHaveBeenCalledWith({ month: '2026-10', latestOnly: true, page: 0, size: 20 });
    const toggle = screen.getByRole('button', { name: 'Earlier versions (1)' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    await userEvent.click(toggle);
    expect(await screen.findByText('(version 1)')).toBeTruthy();
    expect(listInvoices).toHaveBeenLastCalledWith(
      { plotId: 'p1', month: '2026-10', page: 0, size: 100 },
      { query: { enabled: true } },
    );
  });

  it('shows the limit when the build is too large', async () => {
    buildMonthInvoices.mockImplementation((_m: string, params: { preview: boolean }) =>
      params.preview
        ? Promise.resolve(previewResult)
        : Promise.reject(new ApiError(422, { code: 'INVOICE_BUILD_TOO_LARGE', limit: 500 })),
    );
    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: 'Build invoices for October 2026' }));
    expect(await screen.findByText('One build creates invoices for at most 500 plots')).toBeTruthy();
  });

  it('hides the build and print buttons without INVOICES_ISSUE but keeps the list', () => {
    permissions = ['CHARGES_VIEW'];
    renderPanel();
    expect(screen.queryByRole('button', { name: /Build invoices/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'PDF for printing' })).toBeNull();
    expect(screen.getByText('Plot 9')).toBeTruthy();
    expect(buildMonthInvoices).not.toHaveBeenCalled();
  });

  it('prints with the chosen scope', async () => {
    const blob = new Blob(['%PDF']);
    getMonthInvoicesPrintPdf.mockResolvedValue(blob);
    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: 'PDF for printing' }));
    await waitFor(() => expect(getMonthInvoicesPrintPdf).toHaveBeenCalledWith('2026-10', { scope: 'PAPER' }));
    await userEvent.click(screen.getByRole('radio', { name: 'All plots' }));
    await userEvent.click(screen.getByRole('button', { name: 'PDF for printing' }));
    await waitFor(() =>
      expect(getMonthInvoicesPrintPdf).toHaveBeenLastCalledWith('2026-10', { scope: 'ALL' }),
    );
    expect(downloadBlob).toHaveBeenLastCalledWith(blob, 'invoices_2026-10_all.pdf');
    expect(monthPrintFileName('2026-10', 'PAPER')).toBe('invoices_2026-10_paper.pdf');
  });

  it('offers "Send invoices" for the month only with INVOICES_ISSUE', async () => {
    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: 'Send invoices for October 2026' }));
    expect(screen.getByRole('dialog', { name: 'Send invoices for October 2026' })).toBeTruthy();
  });

  it('hides "Send invoices" without INVOICES_ISSUE', () => {
    permissions = ['CHARGES_VIEW'];
    renderPanel();
    expect(screen.queryByRole('button', { name: /Send invoices/ })).toBeNull();
  });
});
