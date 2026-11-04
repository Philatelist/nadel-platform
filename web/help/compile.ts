import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { LOCALES, messages, type Locale } from '@nadel/shared';
import { parsePage } from './parse';
import { HELP_SECTIONS, type HelpBundle, type HelpBundlePage, type HelpIssue, type HelpPage } from './types';

export interface CompileResult {
  /** Every page per language, with the plain text (for search and checks). */
  pages: Record<Locale, HelpPage[]>;
  /** What the client receives per language. */
  bundles: Record<Locale, HelpBundle>;
  issues: HelpIssue[];
}

const sectionRank = new Map<string, number>(HELP_SECTIONS.map((section, index) => [section, index]));

/** Contents order: by section, then by `order`, then by id. */
function byContents(a: HelpPage, b: HelpPage): number {
  return (
    (sectionRank.get(a.section) ?? 0) - (sectionRank.get(b.section) ?? 0) ||
    a.order - b.order ||
    a.id.localeCompare(b.id)
  );
}

function toBundlePage(page: HelpPage): HelpBundlePage {
  const bundlePage: Partial<HelpPage> = { ...page };
  delete bundlePage.text;
  return bundlePage as HelpBundlePage;
}

function readLocale(rootDir: string, locale: Locale, issues: HelpIssue[]): HelpPage[] {
  const dir = join(rootDir, locale);
  let files: string[] = [];
  try {
    files = readdirSync(dir)
      .filter((name) => name.endsWith('.md'))
      .sort();
  } catch {
    // a language without a folder simply has no pages; the completeness check reports the missing translations
  }
  const pages: HelpPage[] = [];
  const seen = new Set<string>();
  for (const file of files) {
    const id = file.slice(0, -'.md'.length);
    const result = parsePage({
      id,
      locale,
      source: readFileSync(join(dir, file), 'utf8'),
      messages: messages(locale),
    });
    issues.push(...result.issues);
    if (!result.page) continue;
    if (seen.has(id)) {
      issues.push({ code: 'DUPLICATE_ID', pageId: id, locale, detail: `page id "${id}" is used twice` });
      continue;
    }
    seen.add(id);
    pages.push(result.page);
  }
  return pages.sort(byContents);
}

/**
 * Compiles `<rootDir>/<lang>/<id>.md` into the pages and the per-language bundles.
 * Runs in Node (the Vite plugin, the capture and PDF scripts, tests); never in the browser.
 */
export function compileHelp(rootDir: string): CompileResult {
  const issues: HelpIssue[] = [];
  const pages = {} as Record<Locale, HelpPage[]>;
  const bundles = {} as Record<Locale, HelpBundle>;
  for (const locale of LOCALES) {
    pages[locale] = readLocale(rootDir, locale, issues);
    bundles[locale] = {
      locale,
      pages: pages[locale].map(toBundlePage),
      search: null,
    };
  }
  return { pages, bundles, issues };
}
