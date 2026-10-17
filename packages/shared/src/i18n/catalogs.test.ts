import { describe, expect, it } from 'vitest';
import { en } from './en';
import { uk } from './uk';

function leafPaths(node: unknown, prefix = ''): string[] {
  if (typeof node !== 'object' || node === null) return [prefix];
  return Object.entries(node).flatMap(([k, v]) => leafPaths(v, prefix ? `${prefix}.${k}` : k));
}

describe('i18n catalogs', () => {
  it('uk and en have the same keys', () => {
    expect(leafPaths(uk).sort()).toEqual(leafPaths(en).sort());
  });
});
