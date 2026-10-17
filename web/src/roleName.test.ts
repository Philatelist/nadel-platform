import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { roleDisplayName } from './roleName';

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
