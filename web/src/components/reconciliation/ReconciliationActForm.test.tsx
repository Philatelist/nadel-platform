import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { kyivToday } from '../certificates/certificateFile';
import { ReconciliationActForm, yearStart } from './ReconciliationActForm';

const getPdf = vi.fn();
vi.mock('../../downloadBlob', () => ({ downloadBlob: vi.fn() }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getPlotReconciliationActPdf: (...args: unknown[]): unknown => getPdf(...args),
}));

describe('ReconciliationActForm', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    getPdf.mockReset();
  });

  it('defaults to the year start and today and downloads for the chosen period', async () => {
    getPdf.mockResolvedValue(new Blob(['x']));
    renderWithApp(<ReconciliationActForm plotId="p1" />);
    await userEvent.click(screen.getByRole('button', { name: 'Завантажити акт звірки' }));
    expect(getPdf).toHaveBeenCalledWith('p1', { from: yearStart(kyivToday()), to: kyivToday() });
  });

  it('shows the invalid period text under the field', async () => {
    getPdf.mockRejectedValue(
      new ApiError(400, { code: 'VALIDATION_FAILED', errors: [{ field: 'from', code: 'INVALID' }] }),
    );
    renderWithApp(<ReconciliationActForm plotId="p1" />);
    await userEvent.click(screen.getByRole('button', { name: 'Завантажити акт звірки' }));
    expect(await screen.findByText('Невірний період')).toBeInTheDocument();
  });

  it('shows the requisites warning for REQUISITES_MISSING', async () => {
    getPdf.mockRejectedValue(new ApiError(409, { code: 'REQUISITES_MISSING', missing: ['IBAN'] }));
    renderWithApp(<ReconciliationActForm plotId="p1" />);
    await userEvent.click(screen.getByRole('button', { name: 'Завантажити акт звірки' }));
    expect(await screen.findByText(/IBAN/)).toBeInTheDocument();
  });
});
