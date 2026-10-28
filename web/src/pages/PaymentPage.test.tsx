import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
vi.mock('../components/payments/AllocatePrepaymentDialog', () => ({
  AllocatePrepaymentDialog: () => <div role="dialog" aria-label="Розподіл авансу" />,
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
    currentAllocations: [
      {
        chargeId: 'c1',
        kind: 'REGULAR',
        chargeType: type,
        billingMonth: '2026-01',
        serviceFrom: '2026-01-01',
        serviceTo: '2026-01-31',
        accruedOn: '2026-01-01',
        amount: '400.00',
      },
    ],
    allocations: [
      {
        id: 'a1',
        chargeId: 'c1',
        chargeType: type,
        serviceFrom: '2026-01-01',
        serviceTo: '2026-01-31',
        accruedOn: '2026-01-01',
        amount: '400.00',
        origin: 'ENTRY',
        system: false,
        createdAt: '2026-10-21T10:00:00Z',
        createdByName: 'Іваненко Петро',
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

  it('shows the receipt, what is allocated now, and no note when absent', () => {
    renderCard();
    expect(screen.getByRole('heading', { level: 1, name: 'Квитанція № 42' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Зараз зараховано' })).toBeInTheDocument();
    const now = screen.getByRole('table', { name: 'Нарахування, на які платіж зараховано зараз' });
    expect(within(now).getByText('Членський внесок')).toBeInTheDocument();
    expect(screen.getByText(/Внесено:/)).toHaveTextContent('Іваненко Петро');
    expect(screen.queryByText('Примітка про платника')).not.toBeInTheDocument();
  });

  it('says so when the payment is not allocated anywhere now', () => {
    payment = { ...base(), currentAllocations: [] };
    renderCard();
    expect(screen.getByText('Платіж ще нікуди не зараховано')).toBeInTheDocument();
  });

  it('keeps the allocation history closed, with date, author and cause of every row once opened', async () => {
    renderCard();
    const history = screen.getByText('Історія розподілу').closest('details') as HTMLDetailsElement;
    expect(history.open).toBe(false);
    await userEvent.click(screen.getByText('Історія розподілу'));
    expect(history.open).toBe(true);
    const table = within(history).getByRole('table');
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent(/21\.10\.2026.*Членський внесок.*Іваненко Петро.*Внесення/);
    expect(rows[2]).toHaveTextContent(/Система.*Автоматично з авансу/);
  });

  it('shows the payer note when the response carries it, and the reversed badge', () => {
    payment = { ...base(), payerNote: 'Брат власника', reversed: true };
    renderCard();
    expect(screen.getByText('Примітка про платника')).toBeInTheDocument();
    expect(screen.getByText('Брат власника')).toBeInTheDocument();
    expect(screen.getByText('Сторновано')).toBeInTheDocument();
  });

  it('lists the other parts of a split payment with links and the reversed badge', () => {
    payment = {
      ...base(),
      groupParts: [
        { id: 'pay2', plotId: 'p15', plotNumber: '15', amount: '400.00', reversed: false },
        { id: 'pay3', plotId: 'p16', plotNumber: '16', amount: '100.00', reversed: true },
      ],
    };
    renderCard();
    const panel = screen.getByRole('table', { name: 'Інші частини одного платежу по ділянках платника' });
    expect(within(panel).getByRole('link', { name: '15' })).toHaveAttribute('href', '/payments/pay2');
    expect(within(panel).getByRole('link', { name: '16' })).toHaveAttribute('href', '/payments/pay3');
    expect(within(panel).getAllByText('Сторновано')).toHaveLength(1);
  });

  it('has no group panel for a payment of one plot', () => {
    renderCard();
    expect(screen.queryByText('Інші частини цього платежу')).not.toBeInTheDocument();
  });

  it('hides the actions when the server flags are false', () => {
    payment = { ...base(), reversible: false, reallocatable: false };
    renderCard();
    expect(screen.queryByRole('button', { name: 'Сторнувати' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Перерозподілити' })).not.toBeInTheDocument();
  });

  it('shows the actions when the server flags are true', () => {
    payment = { ...base(), reversible: true, reallocatable: true };
    renderCard();
    expect(screen.getByRole('button', { name: 'Сторнувати' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Перерозподілити' })).toBeInTheDocument();
  });

  it('offers the prepayment allocation only when the server flag is true', () => {
    payment = { ...base(), prepaymentAllocatable: false };
    renderCard();
    expect(screen.queryByRole('button', { name: 'Розподілити аванс' })).not.toBeInTheDocument();
  });

  it('opens the prepayment allocation from the card when the server flag is true', async () => {
    payment = { ...base(), prepaymentAllocatable: true };
    renderCard();
    await userEvent.click(screen.getByRole('button', { name: 'Розподілити аванс' }));
    expect(screen.getByRole('dialog', { name: 'Розподіл авансу' })).toBeInTheDocument();
  });

  it('lists releases with their cause and shows the reversal', () => {
    payment = {
      ...base(),
      reversed: true,
      reversedBy: {
        id: 'r1',
        reason: 'Помилка касира',
        billingMonth: '2026-11',
        createdAt: '2026-11-02T10:00:00Z',
        createdByName: 'Іваненко Петро',
      },
    };
    (payment.allocations as unknown[]).push({
      id: 'a3',
      chargeId: 'c1',
      chargeType: type,
      serviceFrom: '2026-01-01',
      serviceTo: '2026-01-31',
      accruedOn: '2026-01-01',
      amount: '-400.00',
      releaseCause: 'PAYMENT_REVERSED',
      system: true,
      createdAt: '2026-11-02T10:00:00Z',
    });
    renderCard();
    expect(screen.getByRole('row', { name: /Система.*Сторно платежу/ })).toBeInTheDocument();
    expect(screen.getByText('Помилка касира')).toBeInTheDocument();
    expect(screen.getByText('Причина сторнування')).toBeInTheDocument();
    expect(screen.getAllByText('Сторновано').length).toBeGreaterThan(0);
  });
});
