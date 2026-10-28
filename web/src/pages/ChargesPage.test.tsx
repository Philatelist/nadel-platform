import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { summaries } from '../components/charges/billingFixtures';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ChargesPage } from './ChargesPage';

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: ['CHARGES_VIEW'] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListBillingMonths: () => queryResult(summaries),
}));

describe('ChargesPage', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('lists the months with status, totals and a link to each', () => {
    renderWithApp(<ChargesPage />);
    const items = within(screen.getByRole('list', { name: 'Billing months' })).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByRole('link', { name: 'September 2026' })).toHaveAttribute(
      'href',
      '/charges/months/2026-09',
    );
    expect(within(items[0]!).getByText('Open')).toBeInTheDocument();
    expect(within(items[0]!).getByText('Received')).toBeInTheDocument();
    expect(within(items[0]!).getByText(/1[\s ,]?200\.00/)).toBeInTheDocument();
    expect(within(items[0]!).getByText('Electricity')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Closed')).toBeInTheDocument();
    expect(within(items[1]!).getByText('No charges')).toBeInTheDocument();
  });
});
