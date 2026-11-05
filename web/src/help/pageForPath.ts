import { matchPath } from 'react-router';
import type { HelpBundlePage } from '../../help/types';

function paramCount(pattern: string): number {
  return pattern.split('/').filter((segment) => segment.startsWith(':')).length;
}

/**
 * The page that explains the screen at `pathname`. When several routes match (`/plots/new` and `/plots/:id`),
 * the most specific wins: fewest params, then the longest pattern.
 */
export function pageForPath<T extends Pick<HelpBundlePage, 'routes'>>(
  pages: readonly T[],
  pathname: string,
): T | undefined {
  let best: { page: T; params: number; length: number } | undefined;
  for (const page of pages) {
    for (const pattern of page.routes) {
      if (!matchPath({ path: pattern, end: true }, pathname)) continue;
      const params = paramCount(pattern);
      const length = pattern.length;
      if (!best || params < best.params || (params === best.params && length > best.length)) {
        best = { page, params, length };
      }
    }
  }
  return best?.page;
}
