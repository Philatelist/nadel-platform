import type { RecalculationPage, RecalculationSummary } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { RecalculationsPage } from './RecalculationsPage';

const useListRecalculations = vi.fn();

vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: ['CHARGES_VIEW'] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListRecalculations: (...args: unknown[]): unknown => useListRecalculations(...args),
}));

const summary: RecalculationSummary = {
  id: 'r1',
  cause: 'MANUAL',
  causeType: 'MANUAL',
  reason: 'Membership rate corrected',
  total: '-5.00',
  lineCount: 2,
  plotCount: 1,
  createdAt: '2026-10-05T09:00:00Z',
  createdByName: 'Іваненко Петро',
};

const page = (items: RecalculationSummary[]): RecalculationPage => ({
  items,
  page: 0,
  size: 20,
  totalItems: items.length,
  totalPages: 1,
});

describe('RecalculationsPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    useListRecalculations.mockReset();
  });

  it('lists recalculations with cause, author, reason, total and a link to the detail', () => {
    useListRecalculations.mockReturnValue(queryResult(page([summary])));
    renderWithApp(<RecalculationsPage />);
    const row = within(screen.getByRole('table')).getAllByRole('row')[1] as HTMLElement;
    expect(row).toHaveTextContent('Manual recalculation');
    expect(within(row).getByText('Membership rate corrected')).toHaveAttribute('data-user-content');
    expect(within(row).getByText('Іваненко Петро')).toHaveAttribute('data-user-content');
    expect(row).toHaveTextContent('−₴5.00');
    expect(within(row).getByRole('link', { name: 'Open' })).toHaveAttribute(
      'href',
      '/charges/recalculations/r1',
    );
    expect(screen.getByRole('link', { name: 'Recalculations' })).toBeInTheDocument();
  });

  it('shows the empty state', () => {
    useListRecalculations.mockReturnValue(queryResult(page([])));
    renderWithApp(<RecalculationsPage />);
    expect(screen.getByText('There have been no recalculations yet')).toBeInTheDocument();
  });
});
