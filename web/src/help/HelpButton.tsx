import { useState } from 'react';
import { useLocation } from 'react-router';
import { useT } from '../i18n';
import { HelpPanel } from './HelpPanel';
import { useHelpViewer } from './helpViewer';
import { isPageVisible } from './helpVisibility';
import { pageForPath } from './pageForPath';
import { useHelpBundle } from './useHelpBundle';

/** «?» in the header: opens the help page of the current screen; absent when no visible page covers it. */
export function HelpButton() {
  const t = useT();
  const { pathname } = useLocation();
  const viewer = useHelpViewer();
  const query = useHelpBundle();
  // The panel belongs to the screen it was opened on; moving to another screen closes it.
  const [openFor, setOpenFor] = useState<string | null>(null);

  const visible = query.data?.pages.filter((candidate) => isPageVisible(candidate, viewer));
  const page = visible ? pageForPath(visible, pathname) : undefined;
  if (!query.data || !page) return null;

  return (
    <>
      <button
        type="button"
        aria-label={t.help.open}
        aria-haspopup="dialog"
        title={t.help.open}
        onClick={() => setOpenFor(pathname)}
        className="inline-flex size-11 items-center justify-center rounded-control border border-line-strong bg-surface text-[15px] font-semibold text-ink hover:bg-accent-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        <span aria-hidden="true">?</span>
      </button>
      {openFor === pathname && (
        <HelpPanel
          page={page}
          visibleIds={new Set(visible?.map((candidate) => candidate.id))}
          onClose={() => setOpenFor(null)}
        />
      )}
    </>
  );
}
