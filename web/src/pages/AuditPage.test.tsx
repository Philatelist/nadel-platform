import { screen } from '@testing-library/react';
import type { AuditEntry } from '@nadel/api-client';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AuditPage } from './AuditPage';

const entry: AuditEntry = {
  id: 'a1',
  occurredAt: '2026-10-01T10:00:00Z',
  action: 'PLOT_UPDATED',
  entityType: 'PLOT',
  entityId: 'p1',
  entityLabel: '12',
  changes: [
    { field: 'kind', oldValue: 'PLOT', newValue: 'COMMON' },
    { field: 'hasBuilding', oldValue: 'false', newValue: 'true' },
    { field: 'areaM2', oldValue: '600.00', newValue: '650.00' },
  ],
};

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListAuditEntries: () => ({
    data: { items: [entry], page: 0, size: 50, totalItems: 1, totalPages: 1 },
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

describe('AuditPage plot entries', () => {
  it('shows the plot action, a link to the card and readable field values', () => {
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Plot updated')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '12' })).toHaveAttribute('href', '/plots/p1');
    expect(screen.getByText(/Kind:/)).toBeInTheDocument();
    expect(screen.getByText(/Plot → Common land/)).toBeInTheDocument();
    expect(screen.getByText(/Building:/)).toBeInTheDocument();
    expect(screen.getByText(/No → Yes/)).toBeInTheDocument();
    expect(screen.getByText(/Area, m²:/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Plot' })).toBeInTheDocument();
  });
});
