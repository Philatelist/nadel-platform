import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { plotStatusLabel } from './plotStatus';

describe('plotStatusLabel', () => {
  it.each([
    ['uk', 'PLOT', 'Без власника'],
    ['uk', 'COMMON', 'Спільні землі'],
    ['en', 'PLOT', 'No owner'],
    ['en', 'COMMON', 'Common land'],
  ] as const)('labels GREY by kind (%s, %s)', (locale, kind, expected) => {
    expect(plotStatusLabel(messages(locale), { status: 'GREY', kind })).toBe(expected);
  });

  it.each([
    ['uk', 'Є борг'],
    ['en', 'Has debt'],
  ] as const)('ignores kind for other statuses (%s)', (locale, expected) => {
    const t = messages(locale);
    expect(plotStatusLabel(t, { status: 'YELLOW', kind: 'PLOT' })).toBe(expected);
    expect(plotStatusLabel(t, { status: 'YELLOW', kind: 'COMMON' })).toBe(expected);
  });

  it('is undefined when the status is hidden from the viewer', () => {
    expect(plotStatusLabel(messages('en'), { kind: 'PLOT' })).toBeUndefined();
  });
});
