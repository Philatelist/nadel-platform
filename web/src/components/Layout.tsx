import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router';
import { useMe } from '../me';
import { landingPath } from '../landing';
import { menuItems } from '../nav';
import { HelpButton } from '../help/HelpButton';
import { originState, useHelpOrigin } from '../help/helpOrigin';
import { HelpPanelSlotContext } from '../help/helpPanelSlot';
import { isBoardUser } from '../help/helpVisibility';
import { useT } from '../i18n';
import { RoleNameList } from './RoleName';
import { UserContent } from './UserContent';
import { LocaleSwitch } from './LocaleSwitch';
import { ThemeSwitch } from './ThemeSwitch';
import { OfflineBanner } from '../pwa/OfflineBanner';
import { UpdateBar } from '../pwa/UpdateBar';
import { useSignOut } from '../pwa/useSignOut';
import { Button } from './ui';

const itemClass = ({ isActive }: { isActive: boolean }) =>
  `flex min-h-11 items-center rounded-control px-3 text-[15px] no-underline ${
    isActive ? 'bg-accent-soft font-semibold text-accent' : 'text-ink hover:bg-accent-soft'
  }`;

const PROFILE_PATH = '/account/profile';

export function Layout() {
  const me = useMe();
  const signOut = useSignOut();
  const t = useT();
  const [open, setOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const [helpSlot, setHelpSlot] = useState<HTMLElement | null>(null);
  const helpOrigin = useHelpOrigin();

  const title = me.tenant?.name ?? t.app.name;
  const items = menuItems(me);

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
    <HelpPanelSlotContext value={helpSlot}>
      <div className="flex h-full flex-col">
        <OfflineBanner />
        <UpdateBar />
        {/* Below md the header is one compact row: name, help, Menu. The person, language and sign-out live in the menu. */}
        <header className="flex items-center gap-x-3 border-b border-line bg-surface px-4 py-2 md:flex-wrap md:gap-x-4 md:gap-y-2 md:py-3">
          <Link
            to={landingPath(me)}
            title={title}
            className="-my-2 min-w-0 max-w-full flex-1 truncate py-2 font-serif text-xl font-semibold text-ink no-underline md:my-0 md:min-w-[8rem] md:flex-none md:py-0"
          >
            {me.tenant?.name ? <UserContent>{title}</UserContent> : title}
          </Link>
          {me.tenant?.name && <span className="hidden text-sm text-ink-muted md:inline">{t.app.name}</span>}
          <div className="ml-auto flex min-w-0 items-center gap-x-3">
            <div className="hidden min-w-0 flex-col text-sm leading-tight md:flex">
              {/* desktop unchanged: the name opens the account, which links «My details»; the phone menu opens «My details» */}
              <Link to="/account" className="truncate font-medium text-ink">
                <UserContent>{me.fullName}</UserContent>
              </Link>
              <span className="truncate text-xs text-ink-muted">
                <RoleNameList roles={me.roles} />
              </span>
            </div>
            <HelpButton />
            <div className="hidden md:flex">
              <LocaleSwitch />
            </div>
            <div className="hidden md:flex">
              <ThemeSwitch />
            </div>
            {/* a wrapper, not a class on Button: its own inline-flex would override `hidden` */}
            <div className="hidden md:flex">
              <Button variant="secondary" onClick={() => void signOut()}>
                {t.auth.signOut}
              </Button>
            </div>
            <Button
              ref={menuButton}
              variant="secondary"
              className="md:hidden"
              aria-expanded={open}
              aria-controls="app-menu"
              onClick={() => setOpen((value) => !value)}
            >
              {t.nav.menu}
            </Button>
          </div>
        </header>
        <div className="flex min-h-0 flex-1 flex-col md:flex-row">
          <aside
            id="app-menu"
            className={`${open ? 'block' : 'hidden'} border-b border-line bg-surface p-3 md:block md:w-60 md:shrink-0 md:border-b-0 md:border-r`}
          >
            {/* On a phone the person, the language, the theme and sign-out are here, not in the header. */}
            <div className="mb-2 flex flex-col gap-2 border-b border-line pb-2 md:hidden">
              <NavLink to={PROFILE_PATH} className={itemClass} onClick={() => setOpen(false)}>
                <span className="flex min-w-0 flex-col leading-tight">
                  <span className="truncate font-medium">
                    <UserContent>{me.fullName}</UserContent>
                  </span>
                  <span className="truncate text-xs font-normal text-ink-muted">
                    <RoleNameList roles={me.roles} />
                  </span>
                </span>
              </NavLink>
              <div className="flex flex-wrap items-center gap-2">
                <LocaleSwitch />
                <ThemeSwitch />
                <Button variant="secondary" className="ml-auto" onClick={() => void signOut()}>
                  {t.auth.signOut}
                </Button>
              </div>
            </div>
            <nav aria-label={t.nav.main} className="flex flex-col gap-1">
              {items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/readings'}
                  className={itemClass}
                  onClick={() => setOpen(false)}
                >
                  {t.nav[item.label]}
                </NavLink>
              ))}
            </nav>
            {/* The help in the menu is for the board; a member reaches it through «?». It sits apart from the list. */}
            {isBoardUser(me.permissions) && (
              <div className="mt-2 border-t border-line pt-2">
                <NavLink
                  to="/help"
                  state={originState(helpOrigin)}
                  className={itemClass}
                  onClick={() => setOpen(false)}
                >
                  {t.help.title}
                </NavLink>
              </div>
            )}
          </aside>
          <main className="min-w-0 flex-1 overflow-auto px-4 py-6 md:px-7">
            <div className="mx-auto max-w-[1060px]">
              <Outlet />
            </div>
          </main>
          {/* The help panel is rendered here, as a column beside main, so the page narrows instead of being covered. */}
          <div ref={setHelpSlot} className="contents" />
        </div>
      </div>
    </HelpPanelSlotContext>
  );
}
