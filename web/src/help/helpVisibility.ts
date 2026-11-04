import type { Me } from '@nadel/api-client';
import type { HelpBundlePage } from '../../help/types';

/** Who is looking at the help. The anonymous visitor (public help) sees every page. */
export type HelpViewer =
  { kind: 'anonymous' } | { kind: 'user'; permissions: readonly string[]; ownsPlot: boolean };

export const ANONYMOUS_VIEWER: HelpViewer = { kind: 'anonymous' };

type VisibilityPage = Pick<HelpBundlePage, 'id' | 'permissions'>;

/** The platform operator has no permissions and no plots, so only pages for everyone are visible to them. */
export function viewerFromMe(me: Pick<Me, 'permissions' | 'ownedPlots'>): HelpViewer {
  return { kind: 'user', permissions: me.permissions, ownsPlot: me.ownedPlots.length > 0 };
}

/** `member` in a page's permissions stands for "owns a plot now"; any other entry is a permission code. */
export function isPageVisible(page: Pick<HelpBundlePage, 'permissions'>, viewer: HelpViewer): boolean {
  if (viewer.kind === 'anonymous') return true;
  if (page.permissions.length === 0) return true;
  return page.permissions.some((entry) =>
    entry === 'member' ? viewer.ownsPlot : viewer.permissions.includes(entry),
  );
}

export function visiblePageIds(pages: readonly VisibilityPage[], viewer: HelpViewer): Set<string> {
  return new Set(pages.filter((page) => isPageVisible(page, viewer)).map((page) => page.id));
}
