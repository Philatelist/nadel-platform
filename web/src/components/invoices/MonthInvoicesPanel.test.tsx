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
  useListInvoices: () => ok({ items: [row], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
  useGetInvoiceReadiness: () => ok({ missing: [] }),
}));

const previewResult: InvoiceBuild = {
  preview: true,
  invoiceable: 50,
  toBuild: 30,
  nothingDue: 12,
  existing: 8,
};

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
    buildMonthInvoices.mockResolvedValue(previewResult);
  });

  it('shows the preview counts and asks for them with the switch value', async () => {
    renderPanel();
    expect(await screen.findByText('To be built: 30')).toBeTruthy();
    expect(screen.getByText('Plots with an owner: 50')).toBeTruthy();
    expect(screen.getByText('Nothing due, skipped: 12')).toBeTruthy();
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
});
