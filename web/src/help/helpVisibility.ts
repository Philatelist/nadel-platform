import type { Me } from '@nadel/api-client';
import type { HelpBundlePage } from '../../help/types';

/** Who is looking at the help. The anonymous visitor (public help) sees every page. */
export type HelpViewer =
  { kind: 'anonymous' } | { kind: 'user'; permissions: readonly string[]; ownsPlot: boolean };

const membersViewPermission = 'MEMBERS_VIEW';

export const ANONYMOUS_VIEWER: HelpViewer = { kind: 'anonymous' };

type VisibilityPage = Pick<HelpBundlePage, 'id' | 'permissions'>;

/** The platform operator has no permissions and no plots, so only pages for everyone are visible to them. */
export function viewerFromMe(me: Pick<Me, 'permissions' | 'ownedPlots'>): HelpViewer {
  return { kind: 'user', permissions: me.permissions, ownsPlot: me.ownedPlots.length > 0 };
}

/**
 * `board` in a page's permissions stands for "holds at least one permission" (the base MEMBER role has none).
 * `member` in a page's permissions stands for "owns a plot now, or holds MEMBERS_VIEW" (board people who look after
 * members may read what members see, so the chairman sees every page). Any other entry is a permission code.
 * Help is a filter for clarity, not a secret.
 */
export function isPageVisible(page: Pick<HelpBundlePage, 'permissions'>, viewer: HelpViewer): boolean {
  if (viewer.kind === 'anonymous') return true;
  if (page.permissions.length === 0) return true;
  return page.permissions.some((entry) => {
    if (entry === 'board') return isBoardUser(viewer.permissions);
    if (entry === 'member') return viewer.ownsPlot || viewer.permissions.includes(membersViewPermission);
    return viewer.permissions.includes(entry);
  });
}

/** A board user holds at least one permission; the base Member role has none. */
export function isBoardUser(permissions: readonly string[]): boolean {
  return permissions.length > 0;
}

export function visiblePageIds(pages: readonly VisibilityPage[], viewer: HelpViewer): Set<string> {
  return new Set(pages.filter((page) => isPageVisible(page, viewer)).map((page) => page.id));
}
