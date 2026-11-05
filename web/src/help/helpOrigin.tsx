import { format } from '@nadel/shared';
import { Link, useLocation } from 'react-router';
import { useT } from '../i18n';
import { landingPath } from '../landing';
import { useOptionalMe } from '../me';
import { NAV_ITEMS } from '../nav';
import { useHelpViewer } from './helpViewer';
import { isPageVisible } from './helpVisibility';
import { pageForPath } from './pageForPath';
import { useHelpBundle } from './useHelpBundle';

/** Where the help was opened from: the path (with query) and, when known, the name of that screen. */
export interface HelpOrigin {
  from: string;
  label?: string;
}

/** The router `state` that carries the origin to the help. */
export interface HelpLocationState {
  helpOrigin?: HelpOrigin;
}

export function originState(origin: HelpOrigin | undefined): HelpLocationState | undefined {
  return origin ? { helpOrigin: origin } : undefined;
}

function readOrigin(state: unknown): HelpOrigin | undefined {
  if (typeof state !== 'object' || state === null || !('helpOrigin' in state)) return undefined;
  const origin = (state as { helpOrigin: unknown }).helpOrigin;
  if (typeof origin !== 'object' || origin === null) return undefined;
  const { from, label } = origin as { from?: unknown; label?: unknown };
  if (typeof from !== 'string' || !from.startsWith('/')) return undefined;
  return { from, label: typeof label === 'string' ? label : undefined };
}

/** The router state to pass along when moving between help pages, so the way back survives. */
export function useCarriedHelpState(): HelpLocationState | undefined {
  return originState(readOrigin(useLocation().state));
}

/** The current screen as an origin for the help: its path and the name of the help page or menu item covering it. */
export function useHelpOrigin(): HelpOrigin | undefined {
  const t = useT();
  const { pathname, search } = useLocation();
  const viewer = useHelpViewer();
  const query = useHelpBundle();
  if (pathname === '/help' || pathname.startsWith('/help/')) return undefined;
  const visible = query.data?.pages.filter((candidate) => isPageVisible(candidate, viewer));
  const page = visible ? pageForPath(visible, pathname) : undefined;
  const navItem = NAV_ITEMS.find((item) => item.to === pathname);
  return { from: `${pathname}${search}`, label: page?.title ?? (navItem ? t.nav[navItem.label] : undefined) };
}

/**
 * «← Back to X» at the top of a help screen: to the screen the help was opened from (router state), or, when the
 * help was opened directly, to the person's home screen. The public help has no home, so its contents page goes
 * back to the app and its pages keep their link to the contents.
 */
export function HelpBackLink() {
  const t = useT();
  const location = useLocation();
  const me = useOptionalMe();
  const origin = readOrigin(location.state);
  let to: string;
  let text: string;
  if (origin) {
    to = origin.from;
    text = origin.label ? format(t.help.backTo, { name: origin.label }) : t.help.back;
  } else if (me) {
    to = landingPath(me);
    const item = NAV_ITEMS.find((candidate) => candidate.to === to);
    text = item ? format(t.help.backTo, { name: t.nav[item.label] }) : t.help.back;
  } else if (location.pathname === '/help') {
    to = '/';
    text = format(t.help.backTo, { name: t.app.name });
  } else {
    return null;
  }
  return (
    <p>
      <Link to={to} className="text-accent underline">
        {text}
      </Link>
    </p>
  );
}
