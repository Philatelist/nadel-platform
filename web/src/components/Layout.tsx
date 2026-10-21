import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router';
import { useAuth } from 'react-oidc-context';
import type { Permission } from '@nadel/api-client';
import { useMe } from '../me';
import { useT } from '../i18n';
import { RoleNameList } from './RoleName';
import { UserContent } from './UserContent';
import { LocaleSwitch } from './LocaleSwitch';
import { ThemeSwitch } from './ThemeSwitch';
import { Button } from './ui';

interface NavItem {
  to: string;
  label: 'plots' | 'members' | 'tariffs' | 'meters' | 'imports' | 'roles' | 'audit' | 'settings';
  /** Omitted: shown to every signed-in member (the server decides what they may see). */
  permission?: Permission;
}

const NAV_ITEMS: readonly NavItem[] = [
  { to: '/plots', label: 'plots' },
  { to: '/members', label: 'members', permission: 'MEMBERS_VIEW' },
  { to: '/tariffs', label: 'tariffs', permission: 'TARIFFS_VIEW' },
  { to: '/meters', label: 'meters', permission: 'METERS_VIEW' },
  { to: '/imports', label: 'imports', permission: 'IMPORT_RUN' },
  { to: '/roles', label: 'roles', permission: 'ROLES_MANAGE' },
  { to: '/audit', label: 'audit', permission: 'AUDIT_VIEW' },
  { to: '/settings', label: 'settings', permission: 'SETTINGS_MANAGE' },
];

const itemClass = ({ isActive }: { isActive: boolean }) =>
  `flex min-h-11 items-center rounded-control px-3 text-[15px] no-underline ${
    isActive ? 'bg-accent-soft font-semibold text-accent' : 'text-ink hover:bg-accent-soft'
  }`;

export function Layout() {
  const me = useMe();
  const auth = useAuth();
  const t = useT();
  const [open, setOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);

  const title = me.tenant?.name ?? t.app.name;
  const items = NAV_ITEMS.filter((item) => !item.permission || me.permissions.includes(item.permission));
  const hasMenu = items.length > 0;

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        menuButton.current?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);

  return (
    <div className="flex h-full flex-col">
      {/* Below md the visual order (name, Menu, label, controls) differs from the Tab order: Menu is reached after the header controls, right before #app-menu. Deliberate. */}
      <header className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line bg-surface px-4 py-3">
        <Link
          to="/"
          title={title}
          className="order-1 md:order-none min-w-[8rem] max-w-full flex-1 truncate font-serif text-xl font-semibold text-ink no-underline md:flex-none"
        >
          {me.tenant?.name ? <UserContent>{title}</UserContent> : title}
        </Link>
        {me.tenant?.name && (
          <span className="order-3 text-sm text-ink-muted md:order-none">{t.app.name}</span>
        )}
        <div className="order-4 ml-auto flex min-w-0 flex-wrap md:order-none items-center gap-x-3 gap-y-2">
          <div className="flex min-w-0 flex-col text-sm leading-tight">
            <Link to="/account" className="truncate font-medium text-ink">
              <UserContent>{me.fullName}</UserContent>
            </Link>
            <span className="truncate text-xs text-ink-muted">
              <RoleNameList roles={me.roles} />
            </span>
          </div>
          <LocaleSwitch />
          <div className={hasMenu ? 'hidden md:flex' : 'flex'}>
            <ThemeSwitch />
          </div>
          <Button variant="secondary" onClick={() => void auth.signoutRedirect()}>
            {t.auth.signOut}
          </Button>
        </div>
        {hasMenu && (
          <Button
            ref={menuButton}
            variant="secondary"
            className="order-2 md:hidden"
            aria-expanded={open}
            aria-controls="app-menu"
            onClick={() => setOpen((value) => !value)}
          >
            {t.nav.menu}
          </Button>
        )}
      </header>
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        {hasMenu && (
          <aside
            id="app-menu"
            className={`${open ? 'block' : 'hidden'} border-b border-line bg-surface p-3 md:block md:w-60 md:shrink-0 md:border-b-0 md:border-r`}
          >
            <div className="mb-2 md:hidden">
              <ThemeSwitch />
            </div>
            <nav aria-label={t.nav.main} className="flex flex-col gap-1">
              {items.map((item) => (
                <NavLink key={item.to} to={item.to} className={itemClass} onClick={() => setOpen(false)}>
                  {t.nav[item.label]}
                </NavLink>
              ))}
            </nav>
          </aside>
        )}
        <main className="min-w-0 flex-1 overflow-auto px-4 py-6 md:px-7">
          <div className="mx-auto max-w-[1060px]">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
