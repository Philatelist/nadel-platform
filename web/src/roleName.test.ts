import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditRoleValue, roleDescription, roleDisplayName } from './roleName';

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

describe('auditRoleValue', () => {
  it.each([
    ['uk', 'Казначей'],
    ['en', 'Treasurer'],
  ] as const)('maps a base token to the catalog name (%s)', (locale, expected) => {
    expect(auditRoleValue('base:TREASURER', messages(locale))).toBe(expected);
  });

  it('maps every part of a joined list', () => {
    expect(auditRoleValue('base:MEMBER, Electrician, base:TREASURER', messages('en'))).toBe(
      'Member, Electrician, Treasurer',
    );
  });

  it('leaves an unknown code unchanged', () => {
    expect(auditRoleValue('base:NOPE', messages('en'))).toBe('base:NOPE');
  });

  it('leaves a plain value unchanged', () => {
    expect(auditRoleValue('Cashier', messages('en'))).toBe('Cashier');
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

describe('auditRoleValue for description', () => {
  it.each([
    ['uk', 'Повний доступ'],
    ['en', 'Full access'],
  ] as const)('maps base:CHAIRMAN (%s)', (locale, expected) =>
    expect(auditRoleValue('base:CHAIRMAN', messages(locale), 'description')).toBe(expected),
  );
  it('leaves free text and unknown codes alone', () => {
    expect(auditRoleValue('Meters', messages('en'), 'description')).toBe('Meters');
    expect(auditRoleValue('base:NOPE', messages('en'), 'description')).toBe('base:NOPE');
  });
});
