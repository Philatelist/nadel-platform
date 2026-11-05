import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { parsePage, slugify } from './parse';
import type { HelpNode } from './types';

const FRONT = `---
id: sample
title: Sample page
description: One sentence.
kind: screen
routes:
  - /payments/new
permissions:
  - PAYMENTS_ENTER
section: payments-cash
order: 20
shots:
  - name: form
    user: treasurer
    path: /plots
    viewport: phone
    steps:
      - { click: Save }
---
`;

function parse(body: string, front = FRONT, locale: 'uk' | 'en' = 'uk') {
  return parsePage({ id: 'sample', locale, source: front + body, messages: messages(locale) });
}

function find(nodes: readonly HelpNode[], type: HelpNode['type']): HelpNode | undefined {
  for (const node of nodes) {
    if (node.type === type) return node;
    if ('children' in node) {
      const inner = find(node.children, type);
      if (inner) return inner;
    }
  }
  return undefined;
}

describe('parsePage front matter', () => {
  it('reads every field', () => {
    const { page, issues } = parse('## Title\n\nText.\n');
    expect(issues).toEqual([]);
    expect(page).toMatchObject({
      id: 'sample',
      locale: 'uk',
      title: 'Sample page',
      description: 'One sentence.',
      kind: 'screen',
      routes: ['/payments/new'],
      permissions: ['PAYMENTS_ENTER'],
      section: 'payments-cash',
      order: 20,
      shots: [
        { name: 'form', user: 'treasurer', path: '/plots', viewport: 'phone', steps: [{ click: 'Save' }] },
      ],
    });
  });

  it('reports BAD_FRONT_MATTER without a block, with a wrong id and with a bad section', () => {
    expect(parse('text', '').issues[0]?.code).toBe('BAD_FRONT_MATTER');
    const wrongId = parse('text', FRONT.replace('id: sample', 'id: other'));
    expect(wrongId.page).toBeNull();
    expect(wrongId.issues[0]).toMatchObject({ code: 'BAD_FRONT_MATTER', pageId: 'sample' });
    expect(wrongId.issues[0]?.detail).toContain('other');
    const badSection = parse('text', FRONT.replace('section: payments-cash', 'section: nowhere'));
    expect(badSection.issues[0]?.detail).toContain('section');
  });
});

describe('parsePage body', () => {
  it('builds headings with unique anchors', () => {
    const { page } = parse('## Хто бачить\n\n### Хто бачить\n\n## Хто бачить\n');
    expect(page?.headings).toEqual([
      { level: 2, id: 'хто-бачить', text: 'Хто бачить' },
      { level: 3, id: 'хто-бачить-2', text: 'Хто бачить' },
      { level: 2, id: 'хто-бачить-3', text: 'Хто бачить' },
    ]);
    expect(slugify('A, b & C')).toBe('a-b-c');
  });

  it('turns shot: images and help: links into nodes', () => {
    const { page } = parse(
      '![The form](shot:form)\n\nSee [payments](help:payments#list) and [site](https://example.org).\n',
    );
    expect(find(page?.body ?? [], 'img')).toEqual({ type: 'img', shot: 'form', alt: 'The form' });
    expect(find(page?.body ?? [], 'link')).toMatchObject({ pageId: 'payments', anchor: 'list' });
    expect(find(page?.body ?? [], 'a')).toMatchObject({ href: 'https://example.org' });
  });

  it('drops links with other schemes and keeps their text', () => {
    const { page } = parse('[click](javascript:alert(1))\n');
    expect(find(page?.body ?? [], 'a')).toBeUndefined();
    expect(page?.text).toContain('click');
  });

  it('resolves {{ui:...}} from the catalog of the page language', () => {
    const uk = parse('Press {{ui:payments.reverse.action}}.\n');
    expect(find(uk.page?.body ?? [], 'ui')).toEqual({
      type: 'ui',
      key: 'payments.reverse.action',
      text: messages('uk').payments.reverse.action,
    });
    const en = parse('Press {{ui:payments.reverse.action}}.\n', FRONT, 'en');
    expect(find(en.page?.body ?? [], 'ui')).toMatchObject({ text: 'Reverse' });
    expect(en.page?.text).toContain('Press Reverse.');
  });

  it('reports UNKNOWN_UI_KEY for a path that is not a string in the catalog', () => {
    const { issues } = parse('{{ui:payments.nothing}} and {{ui:payments.reverse}}\n');
    expect(issues.map((issue) => issue.code)).toEqual(['UNKNOWN_UI_KEY', 'UNKNOWN_UI_KEY']);
    expect(issues[0]?.detail).toContain('payments.nothing');
  });

  it('turns a blockquote starting with [!important] into a callout', () => {
    const { page } = parse('> [!important]\n> Be careful.\n\n> A plain quote.\n');
    const [callout, quote] = page?.body ?? [];
    expect(callout).toEqual({
      type: 'callout',
      children: [{ type: 'p', children: [{ type: 'text', text: 'Be careful.' }] }],
    });
    expect(quote?.type).toBe('blockquote');
  });

  it('renders tables and lists, and never raw HTML', () => {
    const { page } = parse('| A | B |\n|---|---|\n| 1 | 2 |\n\n- one\n- two\n\n<script>alert(1)</script>\n');
    expect(find(page?.body ?? [], 'table')).toBeDefined();
    expect(find(page?.body ?? [], 'ul')).toBeDefined();
    expect(JSON.stringify(page?.body)).not.toContain('"script"');
  });

  it('extracts plain text for search', () => {
    const { page } = parse('## Heading\n\nSome **bold** text and `code`.\n\n- item\n');
    expect(page?.text).toBe('Heading Some bold text and code . item');
  });
});
