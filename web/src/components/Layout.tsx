import { NavLink, Outlet } from 'react-router';
import { useAuth } from 'react-oidc-context';
import { useT } from '../i18n';

export function Layout() {
  const auth = useAuth();
  const t = useT();
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm ${isActive ? 'bg-accent-soft text-ink' : 'text-muted hover:text-ink'}`;

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-3">
        <span className="font-display text-lg font-bold">{t.app.name}</span>
        <nav className="flex gap-1">
          <NavLink to="/map" className={link}>{t.nav.map}</NavLink>
          <NavLink to="/cabinet" className={link}>{t.nav.cabinet}</NavLink>
        </nav>
        <button
          type="button"
          onClick={() => void auth.signoutRedirect()}
          className="ml-auto text-sm text-muted hover:text-ink"
        >
          {t.auth.signOut}
        </button>
      </header>
      <main className="min-h-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
