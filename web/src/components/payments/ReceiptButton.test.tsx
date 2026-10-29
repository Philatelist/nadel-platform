import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ReceiptButton } from './ReceiptButton';

const getPaymentReceiptPdf = vi.fn();
const downloadBlob = vi.fn();

vi.mock('../../downloadBlob', () => ({ downloadBlob: (...args: unknown[]) => downloadBlob(...args) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getPaymentReceiptPdf: (...args: unknown[]) => getPaymentReceiptPdf(...args),
}));

describe('ReceiptButton', () => {
  beforeEach(() => {
    getPaymentReceiptPdf.mockReset();
    downloadBlob.mockReset();
  });

  it('is shown for a cash payment and downloads receipt_{number}.pdf', async () => {
    const blob = new Blob(['%PDF'], { type: 'application/pdf' });
    getPaymentReceiptPdf.mockResolvedValue(blob);
    renderWithApp(<ReceiptButton payment={{ id: 'pay1', source: 'CASH', receiptNumber: 42 }} />);
    await userEvent.click(screen.getByRole('button', { name: 'Print receipt' }));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'receipt_42.pdf'));
    expect(getPaymentReceiptPdf).toHaveBeenCalledWith('pay1');
  });

  it('is not shown for a bank payment', () => {
    renderWithApp(<ReceiptButton payment={{ id: 'pay2', source: 'BANK', receiptNumber: 7 }} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows the problem text when the receipt is not available', async () => {
    getPaymentReceiptPdf.mockRejectedValue(new ApiError(409, { code: 'RECEIPT_NOT_AVAILABLE' }));
    renderWithApp(<ReceiptButton payment={{ id: 'pay1', source: 'CASH', receiptNumber: 1 }} />);
    await userEvent.click(screen.getByRole('button', { name: 'Print receipt' }));
    expect(await screen.findByText('A receipt is printed only for a cash payment')).toBeTruthy();
    expect(downloadBlob).not.toHaveBeenCalled();
  });
});
