import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { PaymentPage } from './PaymentPage';

let payment: Record<string, unknown>;

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPayment: () => queryResult(payment),
}));

const type = { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false };

function base(): Record<string, unknown> {
  return {
    id: 'pay1',
    groupId: 'g1',
    plotId: 'p9',
    plotNumber: '9',
    source: 'CASH',
    amount: '700.00',
    receivedOn: '2026-10-21',
    billingMonth: '2026-10',
    receiptNumber: 42,
    allocated: '600.00',
    prepayment: '100.00',
    reversed: false,
    allocations: [
      {
        id: 'a1',
        chargeId: 'c1',
        chargeType: type,
        serviceFrom: '2026-01-01',
        serviceTo: '2026-01-31',
        accruedOn: '2026-01-01',
        amount: '400.00',
        system: false,
        createdAt: '2026-10-21T10:00:00Z',
      },
      {
        id: 'a2',
        chargeId: 'c2',
        chargeType: type,
        serviceFrom: '2026-02-01',
        serviceTo: '2026-02-28',
        accruedOn: '2026-02-01',
        amount: '200.00',
        origin: 'PREPAYMENT_AUTO',
        system: true,
        createdAt: '2026-10-22T10:00:00Z',
      },
    ],
    groupParts: [],
    createdAt: '2026-10-21T10:00:00Z',
    createdByName: 'Іваненко Петро',
  };
}

function renderCard() {
  renderWithApp(
    <Routes>
      <Route path="payments/:id" element={<PaymentPage />} />
    </Routes>,
    { route: '/payments/pay1' },
  );
}

describe('PaymentPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    payment = base();
  });

  it('shows the receipt, allocations with the system row, author and no note when absent', () => {
    renderCard();
    expect(screen.getByRole('heading', { level: 1, name: 'Квитанція № 42' })).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getByText('Зараховано з авансу')).toBeInTheDocument();
    expect(within(table).getAllByText('Членський внесок')).toHaveLength(2);
    expect(screen.getByText(/Іваненко Петро/)).toBeInTheDocument();
    expect(screen.queryByText('Примітка про платника')).not.toBeInTheDocument();
  });

  it('shows the payer note when the response carries it, and the reversed badge', () => {
    payment = { ...base(), payerNote: 'Брат власника', reversed: true };
    renderCard();
    expect(screen.getByText('Примітка про платника')).toBeInTheDocument();
    expect(screen.getByText('Брат власника')).toBeInTheDocument();
    expect(screen.getByText('Сторновано')).toBeInTheDocument();
  });
});
