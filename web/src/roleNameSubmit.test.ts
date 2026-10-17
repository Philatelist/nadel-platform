import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { roleDisplayName } from './roleName';
import { nameToSubmit } from './roleNameSubmit';

const treasurer = { name: 'Treasurer', baseCode: 'TREASURER', nameCustomized: false } as const;
const submit = (role: Parameters<typeof nameToSubmit>[0], typed: string, locale: 'uk' | 'en'): string =>
  nameToSubmit(role, typed, role ? roleDisplayName(role, messages(locale)) : '');

describe('nameToSubmit', () => {
  it('sends the stored name for an untouched catalog name in uk', () =>
    expect(submit(treasurer, messages('uk').roles.TREASURER, 'uk')).toBe('Treasurer'));
  it('sends the stored name for an untouched catalog name in en', () =>
    expect(submit(treasurer, 'Treasurer', 'en')).toBe('Treasurer'));
  it('sends the trimmed input when edited', () =>
    expect(submit(treasurer, '  Cashier ', 'uk')).toBe('Cashier'));
  it('sends the stored name for an untouched name with surrounding spaces', () =>
    expect(submit(treasurer, `  ${messages('uk').roles.TREASURER} `, 'uk')).toBe('Treasurer'));
  it('sends the trimmed input for a renamed base role', () => {
    const renamed = { ...treasurer, name: 'Cashier', nameCustomized: true };
    expect(submit(renamed, ' Cashier ', 'uk')).toBe('Cashier');
  });
  it('sends the trimmed input for a custom role', () => {
    const custom = { name: 'Electrician', nameCustomized: false };
    expect(submit(custom, ' Electrician ', 'uk')).toBe('Electrician');
  });
});
