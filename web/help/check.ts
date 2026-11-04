import type { Locale } from '@nadel/shared';
import type { HelpIssue, HelpNode, HelpPage } from './types';

/** The six `##` headings of a `screen` page, in order, per language (see docs/help/README.md). */
export const SCREEN_HEADINGS: Record<Locale, readonly string[]> = {
  uk: [
    'Для чого цей екран',
    'Хто бачить',
    'Елементи екрана',
    'Приклад',
    'Знімки екрана',
    'Пов’язані сторінки',
  ],
  en: [
    'What the screen is for',
    'Who sees it',
    'Every element',
    'Worked example',
    'Screenshots',
    'Related pages',
  ],
};

export interface CheckInput {
  pages: Record<Locale, HelpPage[]>;
  /** App route patterns; `null` skips the route and menu coverage (the build cannot load the React routes). */
  routes: readonly string[] | null;
  /** `to` of every menu item; `null` skips the menu coverage. */
  navItems: readonly string[] | null;
  /** API `Permission` values; `member` is always allowed in addition. */
  permissions: readonly string[];
  shotExists: (locale: Locale, name: string) => boolean;
}

function walk(nodes: readonly HelpNode[], visit: (node: HelpNode) => void): void {
  for (const node of nodes) {
    visit(node);
    if ('children' in node) walk(node.children, visit);
  }
}

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((name) => b.includes(name));
}

/** Completeness checks over the compiled pages. Pure: all outside facts are passed in. */
export function checkHelp({ pages, routes, navItems, permissions, shotExists }: CheckInput): HelpIssue[] {
  const issues: HelpIssue[] = [];
  const locales = Object.keys(pages) as Locale[];
  const allowedPermissions = new Set<string>([...permissions, 'member']);

  const ids = new Map<Locale, Set<string>>(locales.map((l) => [l, new Set(pages[l].map((p) => p.id))]));
  const everyId = new Set([...ids.values()].flatMap((set) => [...set]));
  for (const id of everyId) {
    for (const locale of locales) {
      if (!ids.get(locale)?.has(id)) {
        issues.push({
          code: 'MISSING_TRANSLATION',
          pageId: id,
          locale,
          detail: `page "${id}" has no ${locale} translation`,
        });
      }
    }
  }

  for (const locale of locales) {
    for (const page of pages[locale]) {
      const where = `page "${page.id}" (${locale})`;

      for (const permission of page.permissions) {
        if (!allowedPermissions.has(permission)) {
          issues.push({
            code: 'UNKNOWN_PERMISSION',
            pageId: page.id,
            locale,
            detail: `${where} lists unknown permission "${permission}"`,
          });
        }
      }

      if (routes) {
        for (const route of page.routes) {
          if (!routes.includes(route)) {
            issues.push({
              code: 'UNKNOWN_ROUTE',
              pageId: page.id,
              locale,
              detail: `${where} lists route "${route}" that is not in the application`,
            });
          }
        }
      }

      if (page.kind === 'screen' && page.shots.length === 0) {
        issues.push({
          code: 'SCREEN_WITHOUT_SHOT',
          pageId: page.id,
          locale,
          detail: `screen ${where} has no screenshots`,
        });
      }

      const listed = page.shots.map((shot) => shot.name);
      const referenced: string[] = [];
      walk(page.body, (node) => {
        if (node.type === 'img') referenced.push(node.shot);
        if (node.type === 'link' && !everyId.has(node.pageId)) {
          issues.push({
            code: 'UNKNOWN_LINK',
            pageId: page.id,
            locale,
            detail: `${where} links to unknown page "${node.pageId}"`,
          });
        }
      });
      for (const name of new Set([...listed, ...referenced])) {
        if (!shotExists(locale, name)) {
          issues.push({
            code: 'MISSING_SHOT',
            pageId: page.id,
            locale,
            detail: `${where} needs screenshot "${name}" but help/img/${locale}/${name}.png is missing`,
          });
        }
      }
      for (const name of referenced) {
        if (!listed.includes(name)) {
          issues.push({
            code: 'MISSING_SHOT',
            pageId: page.id,
            locale,
            detail: `${where} shows screenshot "${name}" that is not listed in its shots`,
          });
        }
      }

      if (page.kind === 'screen') {
        const expected = SCREEN_HEADINGS[locale];
        const found = page.headings.filter((h) => h.level === 2).map((h) => h.text);
        const missing = expected.filter((heading) => !found.includes(heading));
        if (missing.length > 0) {
          issues.push({
            code: 'MISSING_SECTION',
            pageId: page.id,
            locale,
            detail: `screen ${where} lacks the section "${missing.join('", "')}"`,
          });
        } else if (found.filter((text) => expected.includes(text)).join('\n') !== expected.join('\n')) {
          issues.push({
            code: 'MISSING_SECTION',
            pageId: page.id,
            locale,
            detail: `screen ${where} has its sections out of order; expected "${expected.join('", "')}"`,
          });
        }
      }
    }
  }

  // The English page lists the same shot names as the Ukrainian one.
  for (const uk of pages.uk ?? []) {
    const en = pages.en?.find((page) => page.id === uk.id);
    if (!en) continue;
    const ukShots = uk.shots.map((s) => s.name);
    const enShots = en.shots.map((s) => s.name);
    if (!sameSet(ukShots, enShots)) {
      issues.push({
        code: 'MISSING_SHOT',
        pageId: uk.id,
        locale: 'en',
        detail: `page "${uk.id}" (en) lists shots [${enShots.join(', ')}] but uk lists [${ukShots.join(', ')}]`,
      });
    }
  }

  if (routes) {
    const covered = new Set(pages.uk?.flatMap((p) => p.routes) ?? []);
    for (const page of pages.en ?? []) page.routes.forEach((r) => covered.add(r));
    for (const route of routes) {
      if (!covered.has(route)) {
        issues.push({
          code: 'ROUTE_WITHOUT_PAGE',
          detail: `route "${route}" is not listed by any help page`,
        });
      }
    }
    for (const to of navItems ?? []) {
      if (!covered.has(to)) {
        issues.push({
          code: 'MENU_WITHOUT_PAGE',
          detail: `menu item "${to}" is not listed in the routes of any help page`,
        });
      }
    }
  } else if (navItems) {
    const covered = new Set(Object.values(pages).flatMap((list) => list.flatMap((p) => p.routes)));
    for (const to of navItems) {
      if (!covered.has(to)) {
        issues.push({
          code: 'MENU_WITHOUT_PAGE',
          detail: `menu item "${to}" is not listed in the routes of any help page`,
        });
      }
    }
  }

  return issues;
}

/** One line per issue, naming locale, page and detail. */
export function formatIssues(issues: readonly HelpIssue[]): string {
  return issues
    .map(
      (issue) =>
        `help ${issue.code}${issue.pageId ? ` ${issue.locale ?? ''}/${issue.pageId}` : ''}: ${issue.detail}`,
    )
    .join('\n');
}
