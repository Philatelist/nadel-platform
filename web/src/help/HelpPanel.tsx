import { useEffect, useId, useRef } from 'react';
import { Link } from 'react-router';
import { Button, buttonClass } from '../components/ui';
import { useLocale, useT } from '../i18n';
import type { HelpBundlePage } from '../../help/types';
import { HelpContent } from './HelpContent';

interface HelpPanelProps {
  page: HelpBundlePage;
  visibleIds: ReadonlySet<string>;
  onClose: () => void;
}

/**
 * Side panel with one help page. A modal `<dialog>` gives the focus trap and Escape; its backdrop is transparent,
 * so the screen behind stays as it is. Full screen below the `md` breakpoint.
 */
export function HelpPanel({ page, visibleIds, onClose }: HelpPanelProps) {
  const t = useT();
  const { locale } = useLocale();
  const titleId = useId();
  const opener = useRef<Element | null>(document.activeElement);

  useEffect(() => {
    const previous = opener.current;
    return () => {
      if (previous instanceof HTMLElement) previous.focus();
    };
  }, []);

  return (
    <dialog
      ref={(el) => {
        if (el && !el.open) el.showModal();
      }}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      aria-labelledby={titleId}
      lang={locale}
      className="fixed inset-y-0 right-0 left-auto m-0 flex h-full max-h-none w-full max-w-none flex-col border-l border-line bg-surface p-0 text-ink backdrop:bg-transparent md:w-[28rem]"
    >
      <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
        <h2 id={titleId} className="font-serif text-xl font-semibold">
          {page.title}
        </h2>
        <Button variant="secondary" onClick={onClose}>
          {t.help.close}
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        <HelpContent nodes={page.body} locale={locale} isPageVisible={(id) => visibleIds.has(id)} />
      </div>
      <div className="border-t border-line px-4 py-3">
        <Link to={`/help/${page.id}`} className={buttonClass('primary')} onClick={onClose}>
          {t.help.openFull}
        </Link>
      </div>
    </dialog>
  );
}
