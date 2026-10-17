import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { roleDisplayName } from './roleName';
import { findNameClash } from './roleNameClash';

const roles = [
  { name: 'Treasurer', baseCode: 'TREASURER', nameCustomized: false },
  { name: 'Electrician', nameCustomized: false },
] as const;

const clash = (name: string, locale: 'uk' | 'en', others: readonly (typeof roles)[number][] = roles) =>
  findNameClash(name, others, (r) => roleDisplayName(r, messages(locale)), locale);

describe('findNameClash', () => {
  it('ignores case', () => expect(clash('ELECTRICIAN', 'en')).toBe(true));
  it('trims the input', () => expect(clash('  electrician ', 'en')).toBe(true));
  it('excludes the role itself when it is not in others', () =>
    expect(clash('Electrician', 'en', [roles[0]])).toBe(false));
  it('compares with the displayed name of a non-customised base role', () => {
    expect(clash('казначей', 'uk')).toBe(true);
    expect(clash('казначей', 'en')).toBe(false);
  });
  it('does not clash on empty input', () => {
    expect(clash('', 'en')).toBe(false);
    expect(clash('   ', 'en')).toBe(false);
  });
});
