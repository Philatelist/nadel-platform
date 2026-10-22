import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Permission } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { closedMonth, openMonth } from '../components/charges/billingFixtures';
import { queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ChargesMonthPage } from './ChargesMonthPage';

let permissions: Permission[];
let month = openMonth;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetBillingMonth: () => queryResult(month),
  useRunBillingMonth: () => ({ mutate: vi.fn(), isPending: false }),
  useCloseBillingMonth: () => ({ mutate: vi.fn(), isPending: false }),
  useListChargeTypes: () => queryResult([]),
}));

function renderPage() {
  renderWithApp(
    withQueries(
      <Routes>
        <Route path="/charges/months/:ym" element={<ChargesMonthPage />} />
      </Routes>,
    ),
    { route: '/charges/months/2026-09' },
  );
}

describe('ChargesMonthPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'PERIODS_CLOSE'];
    month = openMonth;
  });

  it('shows the totals and the check list', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: 'September 2026' })).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
    expect(screen.getByText('The month run has not been done yet')).toBeInTheDocument();
    expect(screen.getByText('Items waiting in “Not charged”: 3')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByText('Meters without a recent reading')).toBeInTheDocument();
    expect(screen.getByText(/1[\s ,]?653\.56/)).toBeInTheDocument();
  });

  it('offers the close button only with PERIODS_CLOSE', () => {
    permissions = ['CHARGES_VIEW'];
    renderPage();
    expect(screen.queryByRole('button', { name: 'Close the month' })).toBeNull();
  });

  it('opens the confirmation dialog from the close button', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Close the month' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Close September 2026?');
  });

  it('offers no close button for a closed month', () => {
    month = closedMonth;
    renderPage();
    expect(screen.getByText('Closed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close the month' })).toBeNull();
  });

  it('offers the month run only with CHARGES_RUN and for an open month', async () => {
    renderPage();
    expect(screen.queryByRole('button', { name: 'Charge the month' })).not.toBeInTheDocument();
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN'];
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Charge the month' }));
    expect(screen.getByRole('heading', { name: 'Charge September 2026' })).toBeInTheDocument();
  });
});
