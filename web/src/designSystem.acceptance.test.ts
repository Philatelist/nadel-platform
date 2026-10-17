// @vitest-environment node
// @layer: unit
// @spec: 003-ui-design-system
// @regression
//
// Feature-level guards for functional-spec.md 2.3, 2.4 and 2.9 that no single component test owns:
// fonts are self-hosted with Cyrillic coverage (web and mobile), the shared shape tokens match the spec,
// and the mobile start screen consumes the shared tokens instead of its own colors.
// Raw hex colors in web screens (2.1, second criterion) are enforced by the ESLint rule
// `no-restricted-syntax` in web/eslint.config.js (run by `pnpm -r lint`), not duplicated here.
import { layout, palette, radius, spacing, typography } from '@nadel/ui-tokens';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');

const indexHtml = read('../index.html');
const mainTsx = read('./main.tsx');
const webPackage = JSON.parse(read('../package.json')) as { dependencies: Record<string, string> };
const mobileApp = read('../../mobile/App.tsx');
const mobileFonts = read('../../mobile/src/fonts.ts');
const mobilePackage = JSON.parse(read('../../mobile/package.json')) as {
  dependencies: Record<string, string>;
};

describe('2.3 fonts are served by Nadel itself', () => {
  it('index.html references no outside font service or stylesheet', () => {
    expect(indexHtml).not.toMatch(/fonts\.googleapis\.com|fonts\.gstatic\.com/);
    expect(indexHtml).not.toMatch(/<link[^>]+rel="stylesheet"/);
  });

  it('web bundles Plex Sans, Serif and Mono with both latin and cyrillic subsets', () => {
    for (const family of ['sans', 'serif', 'mono']) {
      expect(webPackage.dependencies).toHaveProperty([`@fontsource/ibm-plex-${family}`]);
      expect(mainTsx).toMatch(new RegExp(`ibm-plex-${family}/latin-\\d+\\.css`));
      expect(mainTsx).toMatch(new RegExp(`ibm-plex-${family}/cyrillic-\\d+\\.css`));
    }
  });

  it('declares the three Plex families as the typography tokens', () => {
    expect(typography.family.serif).toContain('IBM Plex Serif');
    expect(typography.family.sans).toContain('IBM Plex Sans');
    expect(typography.family.mono).toContain('IBM Plex Mono');
    expect(typography.size.h1).toBe(30);
    expect(typography.size.bodyMember).toBeGreaterThanOrEqual(16);
  });
});

describe('2.4 spacing and shape tokens', () => {
  it('uses the 4 px step scale', () => {
    for (const value of Object.values(spacing)) expect(value % 4).toBe(0);
  });

  it('has 10 px controls, 12 px panels, fully rounded pills and 44 px targets', () => {
    expect(radius.control).toBe(10);
    expect(radius.panel).toBe(12);
    expect(radius.pill).toBeGreaterThanOrEqual(999);
    expect(layout.minTarget).toBe(44);
    expect(layout.menuBreakpoint).toBe(768);
    expect(layout.contentMaxWidth).toBe(1060);
  });
});

describe('2.9 the mobile start screen uses the shared palette and Plex fonts', () => {
  it('imports colors from @nadel/ui-tokens and writes no hex literal of its own', () => {
    expect(mobileApp).toContain("from '@nadel/ui-tokens'");
    expect(mobileApp).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });

  it('loads the IBM Plex fonts through expo-font and uses them for text', () => {
    expect(mobileApp).toContain('useFonts(fontMap)');
    expect(mobileApp).toContain('fontFamily.');
    expect(mobileFonts).toContain('IBMPlexSerif_600SemiBold');
    for (const pkg of ['sans', 'serif', 'mono'])
      expect(mobilePackage.dependencies).toHaveProperty([`@expo-google-fonts/ibm-plex-${pkg}`]);
  });

  it('uses the same accent as the web light theme', () => {
    expect(palette.light.accent.toLowerCase()).toBe('#245a41');
  });
});
