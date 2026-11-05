import { useEffect, useId, useRef } from 'react';
import { Link } from 'react-router';
import { Button, buttonClass } from '../components/ui';
import { useLocale, useT } from '../i18n';
import type { HelpBundlePage } from '../../help/types';
import { HelpContent } from './HelpContent';
import { originState, type HelpOrigin } from './helpOrigin';

interface HelpPanelProps {
  id: string;
  page: HelpBundlePage;
  visibleIds: ReadonlySet<string>;
  origin: HelpOrigin | undefined;
  onClose: () => void;
}

/**
 * Side panel with one help page. It is not modal: no backdrop, no focus trap, the screen beside it stays usable.
 * On desktop it is a column next to `main`; below `md` it covers the screen and has a «← Back» that closes it.
 * Focus moves into it on open and returns to the opener on close.
 */
export function HelpPanel({ id, page, visibleIds, origin, onClose }: HelpPanelProps) {
  const t = useT();
  const { locale } = useLocale();
  const titleId = useId();
  const panel = useRef<HTMLElement>(null);
  const opener = useRef<Element | null>(document.activeElement);

  useEffect(() => {
    const element = panel.current;
    element?.focus();
    const previous = opener.current;
    return () => {
      // give the focus back only if it is still ours: after a menu click it belongs to the new screen
      const active = document.activeElement;
      if ((active === document.body || element?.contains(active)) && previous instanceof HTMLElement) {
        previous.focus();
      }
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <aside
      ref={panel}
      id={id}
      tabIndex={-1}
      aria-labelledby={titleId}
      lang={locale}
      className="fixed inset-0 z-40 flex flex-col bg-surface text-ink focus:outline-none md:static md:z-auto md:w-[28rem] md:shrink-0 md:border-l md:border-line"
    >
      <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
        <Button variant="secondary" className="md:hidden" onClick={onClose}>
          {t.help.back}
        </Button>
        <h2 id={titleId} className="min-w-0 flex-1 font-serif text-xl font-semibold">
          {page.title}
        </h2>
        <Button variant="secondary" onClick={onClose}>
          {t.help.close}
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        <HelpContent nodes={page.body} locale={locale} isPageVisible={(pageId) => visibleIds.has(pageId)} />
      </div>
      <div className="border-t border-line px-4 py-3">
        <Link
          to={`/help/${page.id}`}
          state={originState(origin)}
          className={buttonClass('primary')}
          onClick={onClose}
        >
          {t.help.openFull}
        </Link>
      </div>
    </aside>
  );
}
