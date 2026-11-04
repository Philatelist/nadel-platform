import type { Me, Permission } from '@nadel/api-client';
import { showsPlots } from './plotsMenu';

export interface NavItem {
  to: string;
  label:
    | 'plots'
    | 'members'
    | 'tariffs'
    | 'readings'
    | 'approvals'
    | 'meters'
    | 'charges'
    | 'payments'
    | 'cashbook'
    | 'reports'
    | 'certificates'
    | 'deliveries'
    | 'announcements'
    | 'imports'
    | 'roles'
    | 'audit'
    | 'settings';
  /** Omitted: shown to every signed-in member (the server decides what they may see). */
  permission?: Permission;
  /** Extra rule on top of `permission`, from the signed-in person. */
  visible?: (me: Me) => boolean;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { to: '/plots', label: 'plots', visible: showsPlots },
  { to: '/members', label: 'members', permission: 'MEMBERS_VIEW' },
  { to: '/tariffs', label: 'tariffs', permission: 'TARIFFS_VIEW' },
  { to: '/readings', label: 'readings', permission: 'READINGS_ENTER' },
  { to: '/readings/approvals', label: 'approvals', permission: 'READINGS_APPROVE' },
  { to: '/meters', label: 'meters', permission: 'METERS_VIEW' },
  { to: '/charges', label: 'charges', permission: 'CHARGES_VIEW' },
  { to: '/payments', label: 'payments', permission: 'PAYMENTS_VIEW' },
  { to: '/cashbook', label: 'cashbook', permission: 'CASHBOOK_VIEW' },
  {
    to: '/reports',
    label: 'reports',
    visible: (me) => me.permissions.includes('REPORTS_VIEW') || me.permissions.includes('CASHBOOK_VIEW'),
  },
  { to: '/documents/certificates', label: 'certificates', permission: 'CHARGES_VIEW' },
  { to: '/deliveries', label: 'deliveries', permission: 'INVOICES_ISSUE' },
  { to: '/announcements', label: 'announcements', permission: 'ANNOUNCEMENTS_SEND' },
  { to: '/imports', label: 'imports', permission: 'IMPORT_RUN' },
  { to: '/roles', label: 'roles', permission: 'ROLES_MANAGE' },
  { to: '/audit', label: 'audit', permission: 'AUDIT_VIEW' },
  {
    to: '/settings',
    label: 'settings',
    visible: (me) => me.permissions.includes('SETTINGS_MANAGE') || me.permissions.includes('METERS_MANAGE'),
  },
];
