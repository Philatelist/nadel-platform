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

describe('import problem codes', () => {
  const codes = [
    'REQUIRED',
    'TOO_LONG',
    'INVALID',
    'CADASTRAL_INVALID',
    'CADASTRAL_NUMBER_TAKEN',
    'AREA_INVALID',
    'PLOT_HAS_OWNERSHIP_HISTORY',
    'SHEET_MISSING',
    'HEADER_MISSING',
    'DUPLICATE_IN_FILE',
    'AREA_BOTH_OR_NONE',
    'DATE_INVALID',
    'PHONE_INVALID',
    'CHANNEL_CONTACT_MISSING',
    'EMAIL_TAKEN',
    'MEMBER_KEY_MISSING',
    'MEMBER_AMBIGUOUS',
    'NUMBER_INVALID',
    'YES_NO_INVALID',
    'UNKNOWN_MEMBER_KEY',
    'UNKNOWN_PLOT',
    'OWNERSHIP_CONFLICT',
    'OWNERSHIP_DATE_MISMATCH',
    'SHARE_INVALID',
    'SHARES_NOT_ONE',
    'DUPLICATE_OWNER',
    'OWNERSHIP_DATE_INVALID',
    'PLOT_COMMON_LAND',
    'PLOT_HAS_METER',
    'UNKNOWN',
  ];

  it.each([
    ['uk', uk],
    ['en', en],
  ])('%s has a message for every row code', (_name, catalog) => {
    const messages: Record<string, string> = catalog.importProblem;
    for (const code of codes) expect(messages[code], code).toBeTruthy();
  });
});
