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

  it('shows a corrected ownership with the reason', () => {
    entry = {
      id: 'a4',
      occurredAt: '2026-10-02T10:00:00Z',
      action: 'OWNERSHIP_CORRECTED',
      entityType: 'OWNERSHIP',
      entityId: 'p7',
      entityLabel: '33',
      changes: [
        { field: 'owners', oldValue: 'Ivanenko Oleh 1/2', newValue: 'Ivanenko Oleh 2/3' },
        { field: 'reason', newValue: 'Typo in the contract' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Ownership corrected')).toBeInTheDocument();
    expect(screen.getByText(/Reason:/)).toBeInTheDocument();
    expect(screen.getByText(/Typo in the contract/)).toBeInTheDocument();
  });

  it('labels an import summary entry and its count fields', () => {
    entry = {
      id: 'a5',
      occurredAt: '2026-10-03T10:00:00Z',
      action: 'IMPORT_COMMITTED',
      entityType: 'IMPORT',
      entityId: 'i1',
      entityLabel: 'plots.xlsx',
      changes: [
        { field: 'plotsCreated', newValue: '5' },
        { field: 'membersUpdated', newValue: '2' },
        { field: 'ownershipsUnchanged', newValue: '1' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Excel import completed')).toBeInTheDocument();
    expect(screen.getByText('plots.xlsx')).toBeInTheDocument();
    expect(screen.getByText(/Plots created:/)).toBeInTheDocument();
    expect(screen.getByText(/Members updated:/)).toBeInTheDocument();
    expect(screen.getByText(/Owners unchanged:/)).toBeInTheDocument();
  });
});

describe('AuditPage charge type and tariff entries', () => {
  it('links a charge type entry to the charge types page with readable labels and booleans', () => {
    entry = {
      id: 'a6',
      occurredAt: '2026-10-04T10:00:00Z',
      action: 'CHARGE_TYPE_UPDATED',
      entityType: 'CHARGE_TYPE',
      entityId: 'c1',
      entityLabel: 'Road repair',
      changes: [
        { field: 'name', oldValue: 'Roads', newValue: 'Road repair' },
        { field: 'description', oldValue: '', newValue: 'One-off' },
        { field: 'active', oldValue: 'false', newValue: 'true' },
        { field: 'position', oldValue: '2', newValue: '1' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Charge type changed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Road repair' })).toHaveAttribute('href', '/charge-types');
    expect(screen.getByText(/Description:/)).toBeInTheDocument();
    expect(screen.getByText(/Active:/)).toBeInTheDocument();
    expect(screen.getByText(/No → Yes/)).toBeInTheDocument();
    expect(screen.getByText(/Position:/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Charge type' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Tariff' })).toBeInTheDocument();
  });

  it('links a tariff entry to the tariff by id and labels the other actions', () => {
    entry = {
      id: 'a7',
      occurredAt: '2026-10-05T10:00:00Z',
      action: 'CHARGE_TYPE_ARCHIVED',
      entityType: 'TARIFF',
      entityId: 't9',
      entityLabel: 'Per sotka',
      changes: [{ field: 'archived', oldValue: 'false', newValue: 'true' }],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Charge type archived')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Per sotka' })).toHaveAttribute('href', '/tariffs/t9');
  });
});
