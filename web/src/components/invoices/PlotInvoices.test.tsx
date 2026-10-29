import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type InvoiceRow, type Permission, type RequisiteField } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { PlotInvoices, invoiceFileName } from './PlotInvoices';
import { RequisitesWarning } from './RequisitesWarning';

let permissions: Permission[];
let missing: RequisiteField[];
const getInvoicePdf = vi.fn();
const downloadBlob = vi.fn();
const issuePlotInvoice = vi.fn();
const listInvoices = vi.fn();

vi.mock('../../me', () => ({ useCan: (p: Permission) => permissions.includes(p) }));
vi.mock('../../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));

const row: InvoiceRow = {
  id: 'inv1',
  plotId: 'p1',
  plotNumber: '9',
  month: '2026-10',
  number: '2026-10/9',
  version: 2,
  due: '1250.50',
  issuedAt: '2026-10-28T09:00:00Z',
  latest: true,
};
const ok = (data: unknown) => ({ data, error: null, isPending: false, isError: false, refetch: vi.fn() });
const pageOf = (items: InvoiceRow[]) =>
  ok({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });
const version = (n: number, latest: boolean): InvoiceRow => ({ ...row, id: `inv-v${n}`, version: n, latest });

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getInvoicePdf: (...args: unknown[]) => getInvoicePdf(...args),
  issuePlotInvoice: (...args: unknown[]) => issuePlotInvoice(...args),
  useListInvoices: (...args: unknown[]) => listInvoices(...args),
  useGetInvoiceReadiness: () => ok({ missing }),
}));

function renderList() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <PlotInvoices plotId="p1" />
    </QueryClientProvider>,
  );
}

describe('PlotInvoices', () => {
  beforeEach(() => {
    permissions = ['CHARGES_VIEW', 'INVOICES_ISSUE'];
    missing = [];
    getInvoicePdf.mockReset();
    downloadBlob.mockReset();
    issuePlotInvoice.mockReset();
    listInvoices.mockReset();
    listInvoices.mockReturnValue(pageOf([row]));
  });

  it('lists only the latest version of each month', () => {
    renderList();
    expect(listInvoices).toHaveBeenCalledWith({ plotId: 'p1', latestOnly: true, page: 0, size: 20 });
  });

  it('downloads the PDF under invoice_{month}_plot-{n}_v{N}.pdf', async () => {
    const blob = new Blob(['%PDF']);
    getInvoicePdf.mockResolvedValue(blob);
    renderList();
    await userEvent.click(screen.getByRole('button', { name: 'Download PDF' }));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'invoice_2026-10_plot-9_v2.pdf'));
    expect(getInvoicePdf).toHaveBeenCalledWith('inv1');
    expect(screen.getByText('(version 2)')).toBeTruthy();
  });

  it('"Invoice for today" issues the invoice and downloads the PDF of the returned version, with a spinner', async () => {
    let issued: (r: InvoiceRow) => void = () => undefined;
    issuePlotInvoice.mockReturnValue(new Promise<InvoiceRow>((resolve) => (issued = resolve)));
    const blob = new Blob(['%PDF']);
    getInvoicePdf.mockResolvedValue(blob);
    renderList();

    await userEvent.click(screen.getByRole('button', { name: 'Invoice for today' }));
    expect(issuePlotInvoice).toHaveBeenCalledWith('p1', {});
    const busy = screen.getByRole('button', { name: 'Preparing the invoice…' });
    expect((busy as HTMLButtonElement).disabled).toBe(true);
    expect(within(busy).getByRole('status')).toBeTruthy();

    issued({ ...row, id: 'inv3', version: 3 });
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'invoice_2026-10_plot-9_v3.pdf'));
    expect(getInvoicePdf).toHaveBeenCalledWith('inv3');
    expect(await screen.findByRole('button', { name: 'Invoice for today' })).toBeTruthy();
  });

  it('hides "Invoice for today" without INVOICES_ISSUE', () => {
    permissions = ['CHARGES_VIEW'];
    renderList();
    expect(screen.queryByRole('button', { name: 'Invoice for today' })).toBeNull();
  });

  it('shows the requisites warning when the server answers REQUISITES_MISSING', async () => {
    issuePlotInvoice.mockRejectedValue(
      new ApiError(409, { code: 'REQUISITES_MISSING', missing: ['EDRPOU'] }),
    );
    renderList();
    await userEvent.click(screen.getByRole('button', { name: 'Invoice for today' }));
    expect(await screen.findByText(/contact the platform operator/)).toBeTruthy();
    expect(getInvoicePdf).not.toHaveBeenCalled();
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it('keeps the earlier versions collapsed and loads them when expanded', async () => {
    listInvoices.mockImplementation((params: { latestOnly?: boolean }) =>
      params.latestOnly === true
        ? pageOf([version(3, true)])
        : pageOf([version(3, true), version(2, false), version(1, false)]),
    );
    renderList();

    const toggle = screen.getByRole('button', { name: 'Earlier versions (2)' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('(version 2)')).toBeNull();
    expect(listInvoices).toHaveBeenCalledWith(
      { plotId: 'p1', month: '2026-10', page: 0, size: 100 },
      { query: { enabled: false } },
    );

    await userEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(listInvoices).toHaveBeenLastCalledWith(
      { plotId: 'p1', month: '2026-10', page: 0, size: 100 },
      { query: { enabled: true } },
    );
    const earlier = screen.getByRole('list', { name: 'Earlier versions (2)' });
    expect(within(earlier).getByText('(version 2)')).toBeTruthy();
    expect(within(earlier).getByText('(version 1)')).toBeTruthy();
    expect(within(earlier).queryByText('(version 3)')).toBeNull();
    expect(within(earlier).getAllByRole('button', { name: 'Download PDF' })).toHaveLength(2);
  });

  it('shows no earlier versions for a version 1', () => {
    listInvoices.mockReturnValue(pageOf([version(1, true)]));
    renderList();
    expect(screen.queryByRole('button', { name: /Earlier versions/ })).toBeNull();
  });

  it('builds the file name from month, plot and version', () => {
    expect(invoiceFileName({ month: '2026-10', plotNumber: '12/A', version: 1 })).toBe(
      'invoice_2026-10_plot-12-A_v1.pdf',
    );
  });
});

describe('RequisitesWarning', () => {
  const renderWarning = (fields: RequisiteField[]) => renderWithApp(<RequisitesWarning missing={fields} />);

  it('links a missing IBAN to Settings', () => {
    renderWarning(['IBAN']);
    expect(screen.getByText(/IBAN of the cooperative is not filled in/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Requisites/ }).getAttribute('href')).toBe('/settings');
  });

  it('sends a missing ЄДРПОУ to the platform operator', () => {
    renderWarning(['EDRPOU']);
    expect(screen.getByText(/contact the platform operator/)).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders nothing when nothing is missing', () => {
    renderWarning([]);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
