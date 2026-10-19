import { screen } from '@testing-library/react';
import type { AuditEntry } from '@nadel/api-client';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AuditPage } from './AuditPage';

let entry: AuditEntry = {
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

describe('AuditPage settings entries', () => {
  it('shows the settings action and readable visibility values', () => {
    entry = {
      id: 'a2',
      occurredAt: '2026-10-01T10:00:00Z',
      action: 'SETTINGS_UPDATED',
      entityType: 'SETTINGS',
      entityId: 's1',
      entityLabel: 'Stored backend label',
      changes: [{ field: 'memberPlotStatusVisibility', oldValue: 'OWN_ONLY', newValue: 'ALL_PLOTS' }],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Settings changed')).toBeInTheDocument();
    expect(screen.getByText(/Who sees plot colours:/)).toBeInTheDocument();
    expect(screen.getByText('Cooperative settings')).toBeInTheDocument();
    expect(screen.queryByText('Stored backend label')).not.toBeInTheDocument();
    expect(screen.getByText(/Own plots only → All plots/)).toBeInTheDocument();
  });
});

describe('AuditPage ownership entries', () => {
  it('shows the ownership action with a link to the plot and readable fields', () => {
    entry = {
      id: 'a3',
      occurredAt: '2026-10-01T10:00:00Z',
      action: 'OWNERSHIP_CHANGED',
      entityType: 'OWNERSHIP',
      entityId: 'p7',
      entityLabel: '33',
      changes: [
        { field: 'effectiveFrom', newValue: '2026-10-01' },
        { field: 'owners', newValue: 'Ivanenko Oleh 1/2, Petrenko Ivan 1/2' },
        { field: 'basis', newValue: 'Sale' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Owners changed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '33' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByText(/Owners from:/)).toBeInTheDocument();
    expect(screen.getByText(/Basis:/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Ownership' })).toBeInTheDocument();
  });
});
