import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { roleDescription } from './roleName';
import { restoreNameDescription } from './roleRestore';

const viewer = {
  baseCode: 'TREASURER',
  description: 'Stored English seed',
  descriptionCustomized: false,
} as const;

describe('restoreNameDescription', () => {
  it('sends the stored description when the standard text is untouched in uk', () => {
    const t = messages('uk');
    expect(
      restoreNameDescription(viewer, roleDescription(viewer, t) ?? '', roleDescription(viewer, t) ?? ''),
    ).toBe('Stored English seed');
  });
  it('sends the edited text', () =>
    expect(restoreNameDescription(viewer, ' Cash desk ', roleDescription(viewer, messages('uk')) ?? '')).toBe(
      'Cash desk',
    ));
});
