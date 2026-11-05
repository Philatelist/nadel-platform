import { useCallback, useId, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router';
import { useT } from '../i18n';
import { HelpPanel } from './HelpPanel';
import { useHelpOrigin } from './helpOrigin';
import { useHelpPanelSlot } from './helpPanelSlot';
import { useHelpViewer } from './helpViewer';
import { isPageVisible } from './helpVisibility';
import { pageForPath } from './pageForPath';
import { useHelpBundle } from './useHelpBundle';

/**
 * «?» in the header: opens the help page of the current screen in a side panel; absent when no visible page covers
 * it. The panel follows the route: another screen shows its own page, and a screen without one closes the panel.
 */
export function HelpButton() {
  const t = useT();
  const { pathname } = useLocation();
  const viewer = useHelpViewer();
  const query = useHelpBundle();
  const slot = useHelpPanelSlot();
  const origin = useHelpOrigin();
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);

  const visible = query.data?.pages.filter((candidate) => isPageVisible(candidate, viewer));
  const page = visible ? pageForPath(visible, pathname) : undefined;
  const hasPage = page !== undefined;

  // A screen without a visible page closes the panel (state adjusted while rendering, as React advises).
  if (!hasPage && open) setOpen(false);

  if (!query.data || !page) return null;

  const panel = open && (
    <HelpPanel
      id={panelId}
      page={page}
      visibleIds={new Set(visible?.map((candidate) => candidate.id))}
      origin={origin}
      onClose={close}
    />
  );

  return (
    <>
      <button
        type="button"
        aria-label={t.help.open}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        title={t.help.open}
        onClick={() => setOpen(true)}
        className="inline-flex size-11 items-center justify-center rounded-control border border-line-strong bg-surface text-[15px] font-semibold text-ink hover:bg-accent-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        <span aria-hidden="true">?</span>
      </button>
      {slot ? createPortal(panel, slot) : panel}
    </>
  );
}
