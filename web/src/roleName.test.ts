import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { roleDescription, roleDisplayName } from './roleName';

describe('roleDisplayName', () => {
  it.each([
    ['uk', 'Казначей'],
    ['en', 'Treasurer'],
  ] as const)('shows the catalog name for a base role that was not renamed (%s)', (locale, expected) => {
    const role = { name: 'Stored legacy name', baseCode: 'TREASURER', nameCustomized: false } as const;
    expect(roleDisplayName(role, messages(locale))).toBe(expected);
  });

  it('shows the stored name for a renamed base role', () => {
    const role = { name: 'Cashier', baseCode: 'TREASURER', nameCustomized: true } as const;
    expect(roleDisplayName(role, messages('uk'))).toBe('Cashier');
  });

  it('shows the stored name for a custom role', () => {
    const role = { name: 'Electrician', nameCustomized: false } as const;
    expect(roleDisplayName(role, messages('uk'))).toBe('Electrician');
  });
});

describe('roleDescription', () => {
  const base = { baseCode: 'MEMBER', description: 'Own data only', descriptionCustomized: false } as const;
  it('shows the catalog text for a standard base description', () =>
    expect(roleDescription(base, messages('uk'))).toBe('Лише власні дані'));
  it('shows the stored text for a customised base role', () =>
    expect(
      roleDescription({ ...base, description: 'Ours', descriptionCustomized: true }, messages('uk')),
    ).toBe('Ours'));
  it('shows the stored text for a custom role, possibly none', () =>
    expect(roleDescription({ descriptionCustomized: true }, messages('uk'))).toBeUndefined());
});
