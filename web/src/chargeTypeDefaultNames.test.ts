// @vitest-environment node
import { messages } from '@nadel/shared';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// The server refuses a new name equal to a default name; the properties file is its source of truth and the
// catalogs must not drift from it.
const properties = readFileSync(
  new URL('../../backend/src/main/resources/tariffs/charge-type-default-names.properties', import.meta.url),
  'utf8',
);
const fromFile = new Map<string, string>();
for (const line of properties.split(/\r?\n/)) {
  const match = /^([A-Z_]+)\.(en|uk)=(.+)$/.exec(line.trim());
  if (match) fromFile.set(`${match[1]}.${match[2]}`, match[3] ?? '');
}

describe('charge type default names', () => {
  it.each(['uk', 'en'] as const)('%s catalog equals the backend properties file', (locale) => {
    const defaults = messages(locale).chargeTypes.defaultNames;
    const expected = Object.fromEntries(
      Object.keys(defaults).map((code) => [code, fromFile.get(`${code}.${locale}`)]),
    );
    expect(Object.keys(expected)).toHaveLength(5);
    expect(defaults).toEqual(expected);
  });
});
