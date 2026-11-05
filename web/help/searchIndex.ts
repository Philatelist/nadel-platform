import MiniSearch, { type Options } from 'minisearch';
import type { HelpPage } from './types';

/** Apostrophe variants (typographic, plain, modifier letter) are dropped so «м'який», «м’який» and «мякий» agree. */
const APOSTROPHES = /['’ʼ‘`]/g;
const NON_TERM = /[^\p{L}\p{N}]+/u;

/** Splits on everything that is not a letter or a digit; apostrophes stay inside a word until `processTerm`. */
export function tokenize(text: string): string[] {
  return text
    .replace(APOSTROPHES, "'")
    .split(/[^\p{L}\p{N}']+/u)
    .filter((part) => part !== '');
}

/** Lowercases and removes apostrophes and any other non-letter, non-digit characters. */
export function processTerm(term: string): string | null {
  const folded = term.replace(APOSTROPHES, '').replace(NON_TERM, '').toLowerCase();
  return folded === '' ? null : folded;
}

/** The options both sides use: the compiler builds the index, the browser loads it. They must be identical. */
export const SEARCH_OPTIONS = {
  idField: 'id',
  fields: ['title', 'headings', 'text'],
  storeFields: ['id', 'title'],
  tokenize,
  processTerm,
} satisfies Options;

export const SEARCH_BOOST = { title: 3, headings: 2, text: 1 } as const;

type SearchablePage = Pick<HelpPage, 'id' | 'title' | 'headings' | 'text'>;

/** Builds the index of one language from compiled pages (their `text` includes the resolved `{{ui:}}` names). */
export function buildSearchIndex(pages: readonly SearchablePage[]): MiniSearch {
  const index = new MiniSearch(SEARCH_OPTIONS);
  index.addAll(
    pages.map((page) => ({
      id: page.id,
      title: page.title,
      headings: page.headings.map((heading) => heading.text).join('\n'),
      text: page.text,
    })),
  );
  return index;
}

/** The serialized index stored in the bundle. */
export function serializeSearchIndex(pages: readonly SearchablePage[]): string {
  return JSON.stringify(buildSearchIndex(pages));
}
