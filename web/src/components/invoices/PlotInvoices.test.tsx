import { screen, waitFor } from '@testing-library/react';
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
const mutate = vi.fn();
let issueOptions: { mutation: { onError: (e: unknown) => void } };

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

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getInvoicePdf: (...args: unknown[]) => getInvoicePdf(...args),
  useListInvoices: () => ok({ items: [row], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
  useGetInvoiceReadiness: () => ok({ missing }),
  useIssuePlotInvoice: (o: typeof issueOptions) => {
    issueOptions = o;
    return { mutate, isPending: false };
  },
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
    mutate.mockReset();
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

  it('offers "Invoice for today" with INVOICES_ISSUE only', async () => {
    renderList();
    await userEvent.click(screen.getByRole('button', { name: 'Invoice for today' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'p1', data: {} });
  });

  it('hides "Invoice for today" without INVOICES_ISSUE', () => {
    permissions = ['CHARGES_VIEW'];
    renderList();
    expect(screen.queryByRole('button', { name: 'Invoice for today' })).toBeNull();
  });

  it('shows the requisites warning when the server answers REQUISITES_MISSING', async () => {
    renderList();
    issueOptions.mutation.onError(new ApiError(409, { code: 'REQUISITES_MISSING', missing: ['EDRPOU'] }));
    expect(await screen.findByText(/contact the platform operator/)).toBeTruthy();
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
