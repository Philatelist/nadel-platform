import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { chargesByMonthInfo } from '../test/reportFixtures';
import { renderWithApp } from '../test/render';
import { ReportsPage } from './ReportsPage';

let reports: unknown[] = [];

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListReports: () => queryResult(reports),
}));

describe('ReportsPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    reports = [chargesByMonthInfo];
  });

  it('lists a card per report the API returns, with its title, description and link', () => {
    renderWithApp(<ReportsPage />);
    const items = within(screen.getByRole('list', { name: 'Reports' })).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(within(items[0]!).getByRole('link', { name: 'Charges by month' })).toHaveAttribute(
      'href',
      '/reports/charges-by-month',
    );
    expect(within(items[0]!).getByText(/What was charged in each billing month/)).toBeInTheDocument();
  });

  it('leaves out a report the catalog has no title for and keeps the others', () => {
    reports = [{ ...chargesByMonthInfo, code: 'water' }, chargesByMonthInfo];
    renderWithApp(<ReportsPage />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('shows nothing for a report code the catalog has no title for', () => {
    reports = [{ ...chargesByMonthInfo, code: 'water' }];
    renderWithApp(<ReportsPage />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('No reports are available.')).toBeInTheDocument();
  });

  it('shows no Debtors card when the API leaves it out, and one when it lists it', () => {
    renderWithApp(<ReportsPage />);
    expect(screen.queryByRole('link', { name: 'Debtors' })).not.toBeInTheDocument();
    reports = [chargesByMonthInfo, { ...chargesByMonthInfo, code: 'debtors', personalData: true }];
    renderWithApp(<ReportsPage />);
    expect(screen.getByRole('link', { name: 'Debtors' })).toHaveAttribute('href', '/reports/debtors');
  });
});
