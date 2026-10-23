import { cleanup, screen } from '@testing-library/react';
import type { AuditEntry } from '@nadel/api-client';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
        { field: 'waterConnectionsCreated', newValue: '6' },
        { field: 'openingBalancesUnchanged', newValue: '2' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Excel import completed')).toBeInTheDocument();
    expect(screen.getByText('plots.xlsx')).toBeInTheDocument();
    expect(screen.getByText(/Plots created:/)).toBeInTheDocument();
    expect(screen.getByText(/Members updated:/)).toBeInTheDocument();
    expect(screen.getByText(/Owners unchanged:/)).toBeInTheDocument();
    expect(screen.getByText(/Water connections created:/)).toBeInTheDocument();
    expect(screen.getByText(/Opening balances unchanged:/)).toBeInTheDocument();
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

describe('AuditPage tariff version entries', () => {
  it('labels the tariff actions and fields and formats rate, basis, frequency and dates', () => {
    entry = {
      id: 'a8',
      occurredAt: '2026-10-06T10:00:00Z',
      action: 'TARIFF_VERSION_ADDED',
      entityType: 'TARIFF',
      entityId: 't1',
      entityLabel: 'Per sotka',
      changes: [
        { field: 'validFrom', newValue: '2026-11-01' },
        { field: 'basis', newValue: 'PER_AREA_SOTKA' },
        { field: 'frequency', newValue: 'MONTHLY' },
        { field: 'rate', oldValue: '4.3200', newValue: '5.5' },
        { field: 'dueDate', newValue: '2026-12-01' },
        { field: 'note', newValue: 'Meeting' },
        { field: 'chargeType', newValue: 'Membership fee' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Tariff version added')).toBeInTheDocument();
    expect(screen.getByText(/Valid from:/)).toBeInTheDocument();
    expect(screen.getByText(/1 Nov 2026/)).toBeInTheDocument();
    expect(screen.getByText(/Basis:/)).toBeInTheDocument();
    expect(screen.getByText(/Per sotka$/, { selector: 'li' })).toBeInTheDocument();
    expect(screen.getByText(/Frequency:/)).toBeInTheDocument();
    expect(screen.getByText(/Monthly/)).toBeInTheDocument();
    expect(screen.getByText(/Rate:/)).toBeInTheDocument();
    expect(screen.getByText(/₴4\.32 → ₴5\.50/)).toBeInTheDocument();
    expect(screen.getByText(/Due date:/)).toBeInTheDocument();
    expect(screen.getByText(/Note:/)).toBeInTheDocument();
    expect(screen.getByText(/Charge type:/)).toBeInTheDocument();
  });

  it('labels a created tariff', () => {
    entry = {
      id: 'a9',
      occurredAt: '2026-10-06T10:00:00Z',
      action: 'TARIFF_CREATED',
      entityType: 'TARIFF',
      entityId: 't1',
      entityLabel: 'Per sotka',
      changes: [],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Tariff created')).toBeInTheDocument();
  });
});

describe('AuditPage tariff scope entries', () => {
  it('labels the scope actions and fields, with the scope value as a label', () => {
    entry = {
      id: 'a10',
      occurredAt: '2026-10-07T10:00:00Z',
      action: 'TARIFF_SCOPE_CHANGED',
      entityType: 'TARIFF',
      entityId: 't1',
      entityLabel: 'Per sotka',
      changes: [
        { field: 'scope', oldValue: 'ALL_PLOTS', newValue: 'SELECTED_PLOTS' },
        { field: 'selectedPlots', newValue: '12, 14' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Tariff plots changed')).toBeInTheDocument();
    expect(screen.getByText(/Applies to:/)).toBeInTheDocument();
    expect(screen.getByText(/All plots → Selected plots/)).toBeInTheDocument();
    expect(screen.getByText(/Selected plots:/)).toBeInTheDocument();
  });

  it.each([
    ['TARIFF_EXCLUSION_ADDED', 'Tariff exclusion added'],
    ['TARIFF_EXCLUSION_REMOVED', 'Tariff exclusion removed'],
  ] as const)('labels %s with the plot and reason fields', (action, label) => {
    entry = {
      id: 'a11',
      occurredAt: '2026-10-07T10:00:00Z',
      action,
      entityType: 'TARIFF',
      entityId: 't1',
      entityLabel: 'Per sotka',
      changes: [
        { field: 'plot', newValue: '12' },
        { field: 'reason', newValue: 'Vacant' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByText(/Plot:/)).toBeInTheDocument();
    expect(screen.getByText(/Reason:/)).toBeInTheDocument();
  });
});

function show(partial: Partial<AuditEntry>, locale: 'uk' | 'en' = 'en') {
  window.localStorage.setItem('nadel.locale', locale);
  entry = {
    id: 'x',
    occurredAt: '2026-10-01T10:00:00Z',
    action: 'PLOT_UPDATED',
    entityType: 'PLOT',
    entityId: 'p1',
    entityLabel: '12',
    changes: [],
    ...partial,
  };
  renderWithApp(<AuditPage />);
}

describe('AuditPage stored codes', () => {
  afterEach(() => window.localStorage.clear());

  it.each([
    ['uk', 'Членський внесок'],
    ['en', 'Membership fee'],
  ] as const)('shows a base: charge type token in the label, name and chargeType in %s', (locale, name) => {
    show(
      {
        action: 'CHARGE_TYPE_UPDATED',
        entityType: 'CHARGE_TYPE',
        entityLabel: 'base:MEMBERSHIP',
        changes: [
          { field: 'name', oldValue: 'base:MEMBERSHIP', newValue: 'Roads' },
          { field: 'chargeType', newValue: 'base:MEMBERSHIP' },
        ],
      },
      locale,
    );
    expect(screen.getByRole('link', { name })).toHaveAttribute('href', '/charge-types');
    expect(screen.getAllByText(new RegExp(name)).length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText(/base:/)).not.toBeInTheDocument();
    expect(screen.getByText('Roads')).toHaveAttribute('data-user-content');
  });

  it('shows an unknown token and a plain charge type name as stored, marked as user content', () => {
    show({
      entityType: 'CHARGE_TYPE',
      entityLabel: 'base:NOPE',
      changes: [{ field: 'name', oldValue: 'Roads', newValue: 'base:NOPE' }],
    });
    expect(screen.getByRole('link', { name: 'base:NOPE' })).toBeInTheDocument();
    expect(screen.getByText('Roads')).toHaveAttribute('data-user-content');
  });

  it('shows the tariff name as the label, and a legacy "X / Y" label as stored', () => {
    show({ entityType: 'TARIFF', entityId: 't1', entityLabel: 'Per sotka' });
    expect(screen.getByText('Per sotka')).toHaveAttribute('data-user-content');
    show({ entityType: 'TARIFF', entityId: 't1', entityLabel: 'Membership fee / Per sotka' });
    expect(screen.getAllByRole('link', { name: 'Membership fee / Per sotka' }).length).toBeGreaterThan(0);
  });

  it.each([
    ['en', 'Ivanenko Oleh 1/2, since 1 Jun 2015', 'Petrenko Ivan 1/2, 1 Jun 2015 – 5 Jul 2020'],
    ['uk', 'Ivanenko Oleh 1/2, з 01.06.2015', 'Petrenko Ivan 1/2, 01.06.2015 – 05.07.2020'],
  ] as const)('renders the owners JSON as a localized list in %s', (locale, open, closed) => {
    show(
      {
        action: 'OWNERSHIP_CORRECTED',
        entityType: 'OWNERSHIP',
        entityLabel: '33',
        changes: [
          {
            field: 'owners',
            newValue: JSON.stringify([
              { member: 'Ivanenko Oleh', share: '2/4', from: '2015-06-01' },
              { member: 'Petrenko Ivan', share: '1/2', from: '2015-06-01', to: '2020-07-05' },
            ]),
          },
        ],
      },
      locale,
    );
    const lines = Array.from(document.querySelectorAll('span.block')).map((line) => line.textContent);
    expect(lines).toEqual([open, closed]);
    expect(screen.getByText('Ivanenko Oleh')).toHaveAttribute('data-user-content');
    expect(screen.queryByText(/\[\{/)).not.toBeInTheDocument();
  });

  it('shows invalid or legacy owners text as stored', () => {
    show({
      entityType: 'OWNERSHIP',
      changes: [{ field: 'owners', oldValue: '[{"broken', newValue: 'Ivanenko Oleh 1/2, Petrenko Ivan 1/2' }],
    });
    expect(screen.getByText('[{"broken')).toBeInTheDocument();
    expect(screen.getByText('Ivanenko Oleh 1/2, Petrenko Ivan 1/2')).toBeInTheDocument();
  });

  it.each([
    ['en', 'View members, Edit plots', 'Email → Viber'],
    ['uk', 'Перегляд членів, Редагування ділянок', 'Електронна пошта → Viber'],
  ] as const)('maps permission codes and channel codes in %s', (locale, permissions, channels) => {
    show(
      {
        action: 'ROLE_UPDATED',
        entityType: 'ROLE',
        entityLabel: 'base:TREASURER',
        changes: [
          { field: 'permissions', oldValue: 'MEMBERS_VIEW', newValue: 'MEMBERS_VIEW, PLOTS_EDIT' },
          { field: 'deliveryChannel', oldValue: 'EMAIL', newValue: 'VIBER' },
        ],
      },
      locale,
    );
    expect(screen.getByText(new RegExp(permissions.replace(/[()]/g, '')))).toBeInTheDocument();
    expect(screen.getByText(channels, { exact: false })).toBeInTheDocument();
  });

  it.each([
    ['uk', 'Член кооперативу', 'Казначей', 'Повний доступ'],
    ['en', 'Member', 'Treasurer', 'Full access'],
  ] as const)('maps base: role tokens in roles and description in %s', (locale, member, treasurer, full) => {
    show(
      {
        action: 'MEMBER_ROLES_CHANGED',
        entityType: 'MEMBER',
        changes: [{ field: 'roles', newValue: 'base:MEMBER, Electrician, base:TREASURER' }],
      },
      locale,
    );
    expect(document.body.textContent).toContain(`${member}, Electrician, ${treasurer}`);
    expect(screen.queryByText(/base:/)).not.toBeInTheDocument();
    expect(screen.getByText('Electrician')).toHaveAttribute('data-user-content');
    expect(screen.queryByText('base:MEMBER')).not.toBeInTheDocument();
    cleanup();
    show(
      {
        action: 'ROLE_UPDATED',
        entityType: 'ROLE',
        entityLabel: 'base:TREASURER',
        changes: [{ field: 'description', oldValue: 'base:CHAIRMAN', newValue: 'Meters' }],
      },
      locale,
    );
    expect(document.body.textContent).toContain(`${full} → Meters`);
    expect(screen.queryByText(/base:/)).not.toBeInTheDocument();
    expect(screen.getByText('Meters')).toHaveAttribute('data-user-content');
  });

  it('formats dates, area and position by locale and keeps legacy values', () => {
    show(
      {
        changes: [
          { field: 'effectiveFrom', oldValue: '2026-01-01', newValue: 'not a date' },
          { field: 'areaM2', oldValue: '600.00', newValue: '1250.50' },
          { field: 'position', oldValue: '2', newValue: '1' },
        ],
      },
      'en',
    );
    expect(screen.getByText(/1 Jan 2026 → not a date/)).toBeInTheDocument();
    expect(screen.getByText(/600\.00 → 1,250\.50/)).toBeInTheDocument();
    expect(screen.getByText(/2 → 1/)).toBeInTheDocument();
  });

  it('formats the area with the Ukrainian separators', () => {
    show({ changes: [{ field: 'areaM2', oldValue: '600.00', newValue: '1250.50' }] }, 'uk');
    expect(screen.getByText(/600,00 → 1\s?250,50/)).toBeInTheDocument();
  });

  it('marks names, reasons and the author as user content', () => {
    show({
      actorName: 'Koval Anna',
      changes: [{ field: 'reason', newValue: 'Typo' }],
    });
    expect(screen.getByText('Koval Anna')).toHaveAttribute('data-user-content');
    expect(screen.getByText('Typo')).toHaveAttribute('data-user-content');
    expect(screen.getByText('12')).toHaveAttribute('data-user-content');
  });
});

describe('AuditPage tariff version correction entries', () => {
  afterEach(() => window.localStorage.clear());

  it.each([
    ['uk', 'Версію тарифу виправлено', 'Версію тарифу видалено', /60,00.₴ → 50,00.₴/, 'Діє з:'],
    ['en', 'Tariff version corrected', 'Tariff version deleted', /₴60\.00 → ₴50\.00/, 'Valid from:'],
  ] as const)(
    'labels the corrected and the deleted version in %s',
    (locale, updated, deleted, rate, from) => {
      show(
        {
          action: 'TARIFF_VERSION_UPDATED',
          entityType: 'TARIFF',
          entityId: 't1',
          entityLabel: 'Per sotka',
          changes: [{ field: 'rate', oldValue: '60.0000', newValue: '50.0000' }],
        },
        locale,
      );
      expect(screen.getByText(updated)).toBeInTheDocument();
      expect(screen.getByText(rate)).toBeInTheDocument();
      cleanup();
      show(
        {
          action: 'TARIFF_VERSION_DELETED',
          entityType: 'TARIFF',
          entityId: 't1',
          entityLabel: 'Per sotka',
          changes: [{ field: 'validFrom', oldValue: '2026-11-01' }],
        },
        locale,
      );
      expect(screen.getByText(deleted)).toBeInTheDocument();
      expect(screen.getByText(new RegExp(from))).toBeInTheDocument();
    },
  );
});

describe('AuditPage reading entries', () => {
  it('links a reading record to its meter and shows the date and value in the locale format', () => {
    entry = {
      id: 'a9',
      occurredAt: '2026-10-21T10:00:00Z',
      action: 'READING_CORRECTED',
      entityType: 'METER_READING',
      entityId: 'm1',
      entityLabel: 'EM-1',
      changes: [
        { field: 'takenOn', oldValue: '2026-10-05', newValue: '2026-10-06' },
        { field: 'value', oldValue: '8205.000', newValue: '8250.500' },
      ],
    };
    renderWithApp(<AuditPage />);
    expect(screen.getByText('Reading corrected')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'EM-1' })).toHaveAttribute('href', '/meters/m1');
    expect(screen.getByText(/Reading date:/)).toBeInTheDocument();
    expect(screen.getByText(/5 Oct 2026 → 6 Oct 2026/)).toBeInTheDocument();
    expect(screen.getByText(/8,205 → 8,250.5/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Meter reading' })).toBeInTheDocument();
  });
});
