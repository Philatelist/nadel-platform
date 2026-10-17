import { describe, expect, it } from 'vitest';
import { contrastRatio } from './contrast';
import { palette, plotStatusColors, plotStatusOnColor, type ThemeName } from './index';

const themes: ThemeName[] = ['light', 'dark'];

type Pal = (typeof palette)[ThemeName];
type Key = keyof Pal;

const textPairs: [Key[], Key[]][] = [
  [
    ['ink', 'inkMuted', 'inkSubtle'],
    ['background', 'surface', 'accentSoft'],
  ],
  [['accent'], ['surface', 'background', 'accentSoft']],
  [['onAccent'], ['accent', 'accentHover']],
  [['danger'], ['surface', 'background', 'dangerSoft']],
  [['ink', 'warningInk'], ['warningSoft']],
  [['successInk'], ['successSoft']],
  [['infoInk'], ['infoSoft']],
];

describe.each(themes)('contrast (%s)', (theme) => {
  const p = palette[theme];

  for (const [fgs, bgs] of textPairs) {
    for (const fg of fgs) {
      for (const bg of bgs) {
        it(`${fg} on ${bg} is at least 4.5:1`, () => {
          expect(contrastRatio(p[fg], p[bg])).toBeGreaterThanOrEqual(4.5);
        });
      }
    }
  }

  for (const status of Object.keys(plotStatusColors[theme]) as (keyof typeof plotStatusColors.light)[]) {
    it(`status ${status} on-color is at least 3:1`, () => {
      expect(
        contrastRatio(plotStatusOnColor[theme][status], plotStatusColors[theme][status]),
      ).toBeGreaterThanOrEqual(3);
    });
  }
});
