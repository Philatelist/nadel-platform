// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { messages } from '@nadel/shared';
import { reportTexts } from './reportTexts';

/** Reads a Java .properties file (UTF-8) into key/text pairs: comments skipped, `\uXXXX`, `\n`, `\t` and `\\` resolved. */
function readProperties(name: string): Map<string, string> {
  const path = new URL(`../../backend/src/main/resources/reports/${name}`, import.meta.url);
  const entries = new Map<string, string>();
  for (const line of readFileSync(path, 'utf-8').split(/\r?\n/)) {
    if (line.trim() === '' || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    const text = line
      .slice(eq + 1)
      .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)))
      .replace(/\\([nt\\])/g, (_, c: string) => (c === 'n' ? '\n' : c === 't' ? '\t' : '\\'));
    entries.set(line.slice(0, eq).trim(), text);
  }
  return entries;
}

function resolve(catalog: unknown, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (node, key) =>
        typeof node === 'object' && node !== null ? (node as Record<string, unknown>)[key] : undefined,
      catalog,
    );
}

describe.each([
  ['messages_uk.properties', messages('uk')],
  ['messages_en.properties', messages('en')],
])('report bundle %s', (file, catalog) => {
  const bundle = readProperties(file);

  it('has entries', () => {
    expect(bundle.size).toBeGreaterThan(20);
  });

  it('says in the web catalog exactly what the generated file says', () => {
    const mismatches = [...bundle].filter(([key, text]) => resolve(catalog, key) !== text);
    expect(mismatches.map(([key]) => key)).toEqual([]);
  });
});

describe('report bundles', () => {
  it('have the same keys', () => {
    expect([...readProperties('messages_uk.properties').keys()].sort()).toEqual(
      [...readProperties('messages_en.properties').keys()].sort(),
    );
  });
});

describe.each(['uk', 'en'] as const)('cash book reports in the %s catalog', (lang) => {
  const catalog = messages(lang);

  it.each(['cash-book', 'bank-movements', 'income-expense', 'expenses'])('names %s', (code) => {
    const texts = reportTexts(catalog, code);
    expect(texts?.title).toBeTruthy();
    expect(texts?.description).toBeTruthy();
  });

  it('has labels for the account and expense category filters', () => {
    expect(catalog.reports.filters.params.account).toBeTruthy();
    expect(catalog.reports.filters.params.expenseCategoryIds).toBeTruthy();
    expect(Object.keys(catalog.reports.filters.options.account)).toEqual(['CASH', 'BANK']);
    expect(catalog.reports.basis.MOVEMENT_DATES).toBeTruthy();
  });
});
