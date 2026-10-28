import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CertificateRow, Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { CertificatesPage } from './CertificatesPage';

let permissions: Permission[];
const mutate = vi.fn();
const listParams = vi.fn();

vi.mock('../me', () => ({ useCan: (p: Permission) => permissions.includes(p) }));

const issued: CertificateRow = {
  id: 'c1',
  number: 'Д-2026-1',
  plotId: 'p1',
  plotNumber: '9',
  asOf: '2026-10-29',
  totalDebt: '1250.50',
  issuedAt: '2026-10-29T09:00:00Z',
  status: 'ISSUED',
  issuedByName: 'Іваненко Іван',
};
const annulled: CertificateRow = {
  ...issued,
  id: 'c2',
  number: 'Д-2026-2',
  status: 'ANNULLED',
  annulledAt: '2026-10-30T09:00:00Z',
  annulledByName: 'Іваненко Іван',
  annulReason: 'Помилка в даті',
};

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListCertificates: (params: unknown) => {
    listParams(params);
    return {
      data: { items: [issued, annulled], page: 0, size: 20, totalItems: 2, totalPages: 1 },
      error: null,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    };
  },
  useAnnulCertificate: () => ({ mutate, isPending: false }),
}));

function renderPage(route = '/documents/certificates') {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <CertificatesPage />
    </QueryClientProvider>,
    { route },
  );
}

describe('CertificatesPage', () => {
  beforeEach(() => {
    permissions = ['CHARGES_VIEW', 'CERTIFICATES_ISSUE'];
    mutate.mockReset();
    listParams.mockReset();
  });

  it('annuls only issued rows and requires a reason', async () => {
    renderPage();
    expect(screen.getAllByRole('button', { name: 'Анулювати' })).toHaveLength(1);
    expect(screen.getByText(/Помилка в даті/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Анулювати' }));
    await userEvent.click(screen.getByRole('button', { name: 'Анулювати довідку' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText('Вкажіть причину')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Причина анулювання'), 'Невірна ділянка');
    await userEvent.click(screen.getByRole('button', { name: 'Анулювати довідку' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'c1', data: { reason: 'Невірна ділянка' } });
  });

  it('hides annul without CERTIFICATES_ISSUE', () => {
    permissions = ['CHARGES_VIEW'];
    renderPage();
    expect(screen.queryByRole('button', { name: 'Анулювати' })).not.toBeInTheDocument();
  });

  it('reads the status filter from the address and changes it', async () => {
    renderPage('/documents/certificates?status=ANNULLED');
    expect(listParams).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ANNULLED' }));
    await userEvent.selectOptions(screen.getByLabelText('Стан'), 'ISSUED');
    await waitFor(() =>
      expect(listParams).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ISSUED' })),
    );
  });
});
