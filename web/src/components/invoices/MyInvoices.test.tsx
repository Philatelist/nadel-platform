import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InvoiceRow } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MyInvoices } from './MyInvoices';

let rows: InvoiceRow[];
const getInvoicePdf = vi.fn();
const downloadBlob = vi.fn();

vi.mock('../../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getInvoicePdf: (...args: unknown[]) => getInvoicePdf(...args),
  useListMyInvoices: () => ({
    data: rows,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

const row = (id: string, month: string, plotNumber: string): InvoiceRow => ({
  id,
  plotId: `p-${plotNumber}`,
  plotNumber,
  month,
  number: `${month}/${plotNumber}`,
  version: 1,
  due: '100.00',
  issuedAt: `${month}-28T09:00:00Z`,
  latest: true,
});

describe('MyInvoices', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    getInvoicePdf.mockReset();
    downloadBlob.mockReset();
  });

  it('puts the newest month first and older months below', () => {
    rows = [row('a', '2026-10', '1'), row('b', '2026-10', '2'), row('c', '2026-09', '1')];
    renderWithApp(<MyInvoices />);
    const latest = screen.getByRole('list', { name: 'Latest version' });
    expect(within(latest).getAllByRole('listitem')).toHaveLength(2);
    const older = screen.getByRole('list', { name: 'Earlier months', hidden: true });
    expect(within(older).getAllByRole('listitem', { hidden: true })).toHaveLength(1);
  });

  it('downloads the PDF of an invoice', async () => {
    const blob = new Blob(['%PDF']);
    getInvoicePdf.mockResolvedValue(blob);
    rows = [row('a', '2026-10', '1')];
    renderWithApp(<MyInvoices />);
    await userEvent.click(screen.getByRole('button', { name: 'Download PDF' }));
    expect(getInvoicePdf).toHaveBeenCalledWith('a');
    expect(downloadBlob).toHaveBeenCalledWith(blob, 'invoice_2026-10_plot-1_v1.pdf');
  });

  it('shows the empty state', () => {
    rows = [];
    renderWithApp(<MyInvoices />);
    expect(screen.getByText('There are no invoices yet.')).toBeTruthy();
  });
});
