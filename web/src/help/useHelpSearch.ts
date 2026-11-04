import MiniSearch from 'minisearch';
import { useMemo } from 'react';
import { SEARCH_BOOST, SEARCH_OPTIONS, processTerm, tokenize } from '../../help/searchIndex';
import type { HelpBundle } from '../../help/types';
import { visiblePageIds, type HelpViewer } from './helpVisibility';

export const MAX_RESULTS = 20;

export interface HelpSearchResult {
  id: string;
  title: string;
}

const indexes = new WeakMap<HelpBundle, MiniSearch>();

/** The index is loaded once per bundle; the bundle object is cached for the session. */
function indexOf(bundle: HelpBundle): MiniSearch | null {
  if (bundle.search === null) return null;
  let index = indexes.get(bundle);
  if (!index) {
    index = MiniSearch.loadJSON(bundle.search, SEARCH_OPTIONS);
    indexes.set(bundle, index);
  }
  return index;
}

/**
 * Pages matching `query` that the viewer may see, best first, at most {@link MAX_RESULTS}.
 * All terms must match (AND); when nothing matches, any term is enough (OR). Pure, no network.
 */
export function searchHelp(bundle: HelpBundle, query: string, viewer: HelpViewer): HelpSearchResult[] {
  const index = indexOf(bundle);
  if (!index || tokenize(query).every((term) => processTerm(term) === null)) return [];
  const visible = visiblePageIds(bundle.pages, viewer);
  const run = (combineWith: 'AND' | 'OR') =>
    index
      .search(query, {
        prefix: true,
        fuzzy: (term) => (term.length > 3 ? 1 : 0),
        boost: SEARCH_BOOST,
        combineWith,
        filter: (result) => visible.has(String(result.id)),
      })
      .slice(0, MAX_RESULTS)
      .map((result) => ({ id: String(result.id), title: String(result.title) }));
  const strict = run('AND');
  return strict.length > 0 ? strict : run('OR');
}

export function useHelpSearch(bundle: HelpBundle, query: string, viewer: HelpViewer): HelpSearchResult[] {
  return useMemo(() => searchHelp(bundle, query.trim(), viewer), [bundle, query, viewer]);
}
