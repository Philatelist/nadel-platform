import { describe, expect, it } from 'vitest';
import { checkHelp, SCREEN_HEADINGS, type CheckInput } from './check';
import type { HelpIssue, HelpNode, HelpPage } from './types';

function page(over: Partial<HelpPage> & { id: string }): HelpPage {
  const locale = over.locale ?? 'uk';
  return {
    locale,
    title: over.id,
    description: 'd',
    kind: 'topic',
    routes: [],
    permissions: [],
    section: 'getting-started',
    order: 1,
    shots: [],
    headings: [],
    body: [],
    text: '',
    ...over,
  };
}

function screen(id: string, locale: 'uk' | 'en', over: Partial<HelpPage> = {}): HelpPage {
  return page({
    id,
    locale,
    kind: 'screen',
    shots: [{ name: `${id}-main`, user: 'chairman', path: '/x' }],
    headings: SCREEN_HEADINGS[locale].map((text) => ({ level: 2 as const, id: text, text })),
    ...over,
  });
}

function input(over: Partial<CheckInput> = {}): CheckInput {
  return {
    pages: {
      uk: [screen('plots', 'uk', { routes: ['/plots'] })],
      en: [screen('plots', 'en', { routes: ['/plots'] })],
    },
    routes: ['/plots'],
    navItems: ['/plots'],
    permissions: ['MEMBERS_VIEW'],
    shotExists: () => true,
    ...over,
  };
}

function only(issues: HelpIssue[], code: HelpIssue['code']): HelpIssue[] {
  return issues.filter((issue) => issue.code === code);
}

describe('checkHelp', () => {
  it('reports nothing for consistent content', () => {
    expect(checkHelp(input())).toEqual([]);
  });

  it('ROUTE_WITHOUT_PAGE names the route', () => {
    const issues = only(checkHelp(input({ routes: ['/plots', '/tariffs'] })), 'ROUTE_WITHOUT_PAGE');
    expect(issues).toHaveLength(1);
    expect(issues[0]?.detail).toContain('/tariffs');
  });

  it('MENU_WITHOUT_PAGE names the menu item', () => {
    const issues = only(checkHelp(input({ navItems: ['/plots', '/audit'] })), 'MENU_WITHOUT_PAGE');
    expect(issues).toHaveLength(1);
    expect(issues[0]?.detail).toContain('/audit');
  });

  it('UNKNOWN_ROUTE names the page and the route', () => {
    const issues = only(checkHelp(input({ routes: [], navItems: [] })), 'UNKNOWN_ROUTE');
    expect(issues.map((i) => i.pageId)).toEqual(['plots', 'plots']);
    expect(issues[0]?.detail).toContain('/plots');
  });

  it('MISSING_SHOT names the locale and the shot when the file is missing', () => {
    const issues = only(checkHelp(input({ shotExists: (locale) => locale === 'uk' })), 'MISSING_SHOT');
    expect(issues).toHaveLength(1);
    expect(issues[0]?.locale).toBe('en');
    expect(issues[0]?.detail).toContain('plots-main');
  });

  it('MISSING_SHOT when en lists different shot names than uk', () => {
    const en = screen('plots', 'en', {
      routes: ['/plots'],
      shots: [{ name: 'other', user: 'chairman', path: '/x' }],
    });
    const issues = only(
      checkHelp(input({ pages: { uk: [screen('plots', 'uk', { routes: ['/plots'] })], en: [en] } })),
      'MISSING_SHOT',
    );
    expect(issues.some((i) => i.detail.includes('other') && i.detail.includes('plots-main'))).toBe(true);
  });

  it('MISSING_TRANSLATION names the page and the missing language', () => {
    const issues = only(
      checkHelp(input({ pages: { uk: [screen('plots', 'uk', { routes: ['/plots'] })], en: [] } })),
      'MISSING_TRANSLATION',
    );
    expect(issues).toHaveLength(1);
    expect(issues[0]).toMatchObject({ pageId: 'plots', locale: 'en' });
  });

  it('MISSING_SECTION names the missing heading, and flags a wrong order', () => {
    const headings = SCREEN_HEADINGS.uk.slice(1).map((text) => ({ level: 2 as const, id: text, text }));
    const missing = only(
      checkHelp(
        input({
          pages: {
            uk: [screen('plots', 'uk', { routes: ['/plots'], headings })],
            en: [screen('plots', 'en', { routes: ['/plots'] })],
          },
        }),
      ),
      'MISSING_SECTION',
    );
    expect(missing).toHaveLength(1);
    expect(missing[0]?.detail).toContain(SCREEN_HEADINGS.uk[0]);

    const swapped = [...SCREEN_HEADINGS.en].reverse().map((text) => ({ level: 2 as const, id: text, text }));
    const order = only(
      checkHelp(
        input({
          pages: {
            uk: [screen('plots', 'uk', { routes: ['/plots'] })],
            en: [screen('plots', 'en', { routes: ['/plots'], headings: swapped })],
          },
        }),
      ),
      'MISSING_SECTION',
    );
    expect(order).toHaveLength(1);
    expect(order[0]?.detail).toContain('out of order');
  });

  it('UNKNOWN_LINK names the target id', () => {
    const body: HelpNode[] = [{ type: 'p', children: [{ type: 'link', pageId: 'ghost', children: [] }] }];
    const issues = only(
      checkHelp(
        input({
          pages: {
            uk: [screen('plots', 'uk', { routes: ['/plots'], body })],
            en: [screen('plots', 'en', { routes: ['/plots'] })],
          },
        }),
      ),
      'UNKNOWN_LINK',
    );
    expect(issues).toHaveLength(1);
    expect(issues[0]?.detail).toContain('ghost');
  });

  it('SCREEN_WITHOUT_SHOT names the page', () => {
    const uk = screen('plots', 'uk', { routes: ['/plots'], shots: [] });
    const issues = only(
      checkHelp(
        input({ pages: { uk: [uk], en: [screen('plots', 'en', { routes: ['/plots'], shots: [] })] } }),
      ),
      'SCREEN_WITHOUT_SHOT',
    );
    expect(issues).toHaveLength(2);
    expect(issues[0]?.detail).toContain('plots');
  });

  it('UNKNOWN_PERMISSION names the permission and accepts member', () => {
    const uk = screen('plots', 'uk', { routes: ['/plots'], permissions: ['member', 'NOPE'] });
    const issues = only(
      checkHelp(input({ pages: { uk: [uk], en: [screen('plots', 'en', { routes: ['/plots'] })] } })),
      'UNKNOWN_PERMISSION',
    );
    expect(issues).toHaveLength(1);
    expect(issues[0]?.detail).toContain('NOPE');
  });
});
