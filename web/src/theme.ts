import { palette } from '@nadel/ui-tokens';

export const THEMES = ['system', 'light', 'dark'] as const;
export type ThemeChoice = (typeof THEMES)[number];

export const STORAGE_KEY = 'nadel.theme';

function isThemeChoice(value: unknown): value is ThemeChoice {
  return typeof value === 'string' && (THEMES as readonly string[]).includes(value);
}

/** The stored choice; anything missing, invalid or unreadable means `system`. */
export function readTheme(): ThemeChoice {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return isThemeChoice(stored) ? stored : 'system';
  } catch {
    return 'system';
  }
}

export function writeTheme(choice: ThemeChoice): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    // not persisted; the choice still applies for this visit
  }
}

function setThemeColorMetas(doc: Document, metas: readonly { content: string; media?: string }[]): void {
  doc.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.remove());
  for (const { content, media } of metas) {
    const meta = doc.createElement('meta');
    meta.name = 'theme-color';
    meta.content = content;
    if (media) meta.setAttribute('media', media);
    doc.head.append(meta);
  }
}

/** Sets or removes `data-theme` on <html> and keeps the `theme-color` metas in step. */
export function applyTheme(choice: ThemeChoice, doc: Document = document): void {
  if (choice === 'system') {
    doc.documentElement.removeAttribute('data-theme');
    setThemeColorMetas(doc, [
      { content: palette.light.background, media: '(prefers-color-scheme: light)' },
      { content: palette.dark.background, media: '(prefers-color-scheme: dark)' },
    ]);
  } else {
    doc.documentElement.setAttribute('data-theme', choice);
    setThemeColorMetas(doc, [{ content: palette[choice].background }]);
  }
}
