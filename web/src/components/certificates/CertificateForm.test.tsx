import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CertificateRow, Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { certificateFileName, kyivToday } from './certificateFile';
import { CertificateForm } from './CertificateForm';

let permissions: Permission[];
const mutate = vi.fn();
let options: { mutation: { onSuccess: (row: CertificateRow) => Promise<void> } };

vi.mock('../../me', () => ({ useCan: (p: Permission) => permissions.includes(p) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useIssuePlotCertificate: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
}));

const row: CertificateRow = {
  id: 'c1',
  number: 'Д-2026-1',
  plotId: 'p1',
  plotNumber: '9',
  asOf: '2026-10-29',
  totalDebt: '0.00',
  issuedAt: '2026-10-29T09:00:00Z',
  status: 'ISSUED',
};

function renderForm() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <CertificateForm plotId="p1" />
    </QueryClientProvider>,
  );
}

describe('CertificateForm', () => {
  beforeEach(() => {
    permissions = ['CERTIFICATES_ISSUE'];
    mutate.mockReset();
  });

  it('is hidden without CERTIFICATES_ISSUE', () => {
    permissions = ['CHARGES_VIEW'];
    const { container } = renderForm();
    expect(container).toBeEmptyDOMElement();
  });

  it('issues for the chosen day and shows the number with a PDF button', async () => {
    renderForm();
    await userEvent.click(screen.getByRole('button', { name: 'Видати довідку' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'p1', data: { asOf: kyivToday() } });
    await vi.waitFor(async () => {
      await options.mutation.onSuccess(row);
    });
    expect(await screen.findByText('Довідку видано: Д-2026-1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Завантажити PDF' })).toBeInTheDocument();
  });

  it('names the PDF file with a Latin D', () => {
    expect(certificateFileName('Д-2026-1')).toBe('certificate_D-2026-1.pdf');
  });
});
