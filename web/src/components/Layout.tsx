import { NavLink, Outlet } from 'react-router';
import { useAuth } from 'react-oidc-context';
import type { Permission } from '@nadel/api-client';
import { useMe } from '../me';
import { useT } from '../i18n';
import { LocaleSwitch } from './LocaleSwitch';

interface NavItem {
  to: string;
  label: 'account' | 'members' | 'audit';
  permission?: Permission;
}

// Roles is added with its route in a later slice.
const NAV_ITEMS: readonly NavItem[] = [
  { to: '/account', label: 'account' },
  { to: '/members', label: 'members', permission: 'MEMBERS_VIEW' },
  { to: '/audit', label: 'audit', permission: 'AUDIT_VIEW' },
];

export function Layout() {
  const { permissions } = useMe();
  const auth = useAuth();
  const t = useT();
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm ${isActive ? 'bg-accent-soft text-ink' : 'text-muted hover:text-ink'}`;

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3">
        <span className="font-display text-lg font-bold">{t.app.name}</span>
        <nav className="flex gap-1">
          {NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission)).map(
            (item) => (
              <NavLink key={item.to} to={item.to} className={link}>
                {t.nav[item.label]}
              </NavLink>
            ),
          )}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          <LocaleSwitch />
          <button
            type="button"
            onClick={() => void auth.signoutRedirect()}
            className="text-sm text-muted hover:text-ink"
          >
            {t.auth.signOut}
          </button>
        </div>
      </header>
      <main className="min-h-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
