import MiniSearch from 'minisearch';
import { describe, expect, it } from 'vitest';
import { searchHelp } from '../src/help/useHelpSearch';
import { ANONYMOUS_VIEWER } from '../src/help/helpVisibility';
import { buildSearchIndex, serializeSearchIndex } from './searchIndex';
import type { HelpBundle, HelpBundlePage, HelpPage } from './types';

function page(id: string, title: string, headings: string[], text: string): HelpPage {
  return {
    id,
    locale: 'uk',
    title,
    description: '',
    kind: 'topic',
    routes: [],
    permissions: [],
    section: 'payments-cash',
    order: 1,
    shots: [],
    headings: headings.map((h) => ({ level: 2 as const, id: h, text: h })),
    body: [],
    text,
  };
}

const pages = [
  page(
    'payments-enter',
    'Внесення платежу',
    ['Сторно платежу'],
    'Щоб скасувати помилковий платіж, натисніть Сторнувати.',
  ),
  page('plots', 'Ділянки', ['Список'], 'Кнопка Додати ділянку відкриває форму. Вулиця та кадастровий номер.'),
  page('meters', 'Лічильники', ['Показання'], "Введіть показання електролічильника. М'який перехід."),
];

function toBundlePage(p: HelpPage): HelpBundlePage {
  const copy: Partial<HelpPage> = { ...p };
  delete copy.text;
  return copy as HelpBundlePage;
}

function bundleOf(list: HelpPage[], locale: 'uk' | 'en' = 'uk'): HelpBundle {
  return {
    locale,
    pages: list.map((p) => toBundlePage(p)),
    search: serializeSearchIndex(list),
  };
}

const ids = (bundle: HelpBundle, query: string) =>
  searchHelp(bundle, query, ANONYMOUS_VIEWER).map((r) => r.id);

describe('help search', () => {
  const bundle = bundleOf(pages);

  it('finds a word from the text', () => {
    expect(ids(bundle, 'кадастровий')).toContain('plots');
  });

  it('finds a button name resolved from {{ui:}}', () => {
    expect(ids(bundle, 'Додати ділянку')).toEqual(['plots']);
  });

  it('finds a word with one wrong letter', () => {
    expect(ids(bundle, 'лічильнки')).toContain('meters');
  });

  it('finds English pages', () => {
    const en = bundleOf(
      [page('payments-enter', 'Entering a payment', ['Reversal'], 'Reverse a wrong payment.')],
      'en',
    );
    expect(ids(en, 'reverse payment')).toEqual(['payments-enter']);
  });

  it('returns nothing for a word that appears nowhere', () => {
    expect(ids(bundle, 'абракадабра')).toEqual([]);
  });

  it('falls back from AND to OR', () => {
    expect(ids(bundle, 'сторнувати платіж')[0]).toBe('payments-enter');
  });

  it('treats apostrophe variants alike', () => {
    expect(ids(bundle, 'м’який')).toContain('meters');
  });

  it('round-trips through JSON', () => {
    expect(buildSearchIndex(pages)).toBeInstanceOf(MiniSearch);
  });
});
