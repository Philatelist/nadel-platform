import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { descriptionToSubmit } from './roleDescriptionSubmit';
import { roleDescription } from './roleName';

const treasurer = {
  baseCode: 'TREASURER',
  description: 'Stored English seed',
  descriptionCustomized: false,
} as const;
const submit = (role: Parameters<typeof descriptionToSubmit>[0], typed: string, locale: 'uk' | 'en') =>
  descriptionToSubmit(role, typed, role ? (roleDescription(role, messages(locale)) ?? '') : '');

describe('descriptionToSubmit', () => {
  it('sends the stored description for an untouched catalog text in uk', () =>
    expect(submit(treasurer, messages('uk').roleDescriptions.TREASURER, 'uk')).toBe('Stored English seed'));
  it('sends the stored description for an untouched catalog text in en', () =>
    expect(submit(treasurer, messages('en').roleDescriptions.TREASURER, 'en')).toBe('Stored English seed'));
  it('sends the trimmed input when edited', () =>
    expect(submit(treasurer, '  Cash desk ', 'uk')).toBe('Cash desk'));
  it('sends the trimmed input for a customised base role', () => {
    const custom = { ...treasurer, description: 'Ours', descriptionCustomized: true };
    expect(submit(custom, ' Ours ', 'uk')).toBe('Ours');
  });
  it('sends the trimmed input for a custom role', () => {
    const role = { description: 'Meters', descriptionCustomized: true };
    expect(submit(role, ' Meters ', 'uk')).toBe('Meters');
  });
  it('sends undefined for blank input', () => expect(submit(undefined, '   ', 'uk')).toBeUndefined());
});
