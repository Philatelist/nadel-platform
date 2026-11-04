import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useOptionalMe } from '../me';
import { viewerFromMe, type HelpViewer } from './helpVisibility';

const HelpViewerContext = createContext<HelpViewer | null>(null);

/** Gives the help an explicit viewer; the public help (no signed-in user, no `/api`) provides the anonymous one. */
export function HelpViewerProvider({ viewer, children }: { viewer: HelpViewer; children: ReactNode }) {
  return <HelpViewerContext value={viewer}>{children}</HelpViewerContext>;
}

/**
 * The current viewer: the one provided explicitly, otherwise the signed-in person from `MeProvider`.
 * Without either it throws, so a forgotten provider never silently shows every page.
 */
export function useHelpViewer(): HelpViewer {
  const provided = useContext(HelpViewerContext);
  const me = useOptionalMe();
  const derived = useMemo(() => (me ? viewerFromMe(me) : null), [me]);
  const viewer = provided ?? derived;
  if (!viewer) throw new Error('useHelpViewer needs HelpViewerProvider or MeProvider');
  return viewer;
}
