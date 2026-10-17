import { describe, expect, it } from 'vitest';
import { palette, plotStatusColors, plotStatusOnColor } from './index';

const HEX = /^#[0-9A-Fa-f]{6}$/;

describe.each([
  ['palette', palette],
  ['plotStatusColors', plotStatusColors],
  ['plotStatusOnColor', plotStatusOnColor],
])('%s', (_name, tokens) => {
  it('has identical keys in light and dark', () => {
    expect(Object.keys(tokens.dark).sort()).toEqual(Object.keys(tokens.light).sort());
  });

  it('uses #RRGGBB values only', () => {
    for (const theme of [tokens.light, tokens.dark]) {
      for (const value of Object.values(theme)) expect(value).toMatch(HEX);
    }
  });
});
