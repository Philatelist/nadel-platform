import { palette } from '@nadel/ui-tokens';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ThemeSwitch } from './components/ThemeSwitch';
import { renderWithApp } from './test/render';
import { applyTheme, readTheme, STORAGE_KEY, writeTheme } from './theme';

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
  applyTheme('system');
});

describe('theme storage', () => {
  it('restores a stored choice', () => {
    window.localStorage.setItem(STORAGE_KEY, 'dark');
    expect(readTheme()).toBe('dark');
  });

  it('treats a missing or invalid value as system', () => {
    expect(readTheme()).toBe('system');
    window.localStorage.setItem(STORAGE_KEY, 'purple');
    expect(readTheme()).toBe('system');
  });

  it('falls back to system without throwing when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readTheme()).toBe('system');
    expect(() => writeTheme('dark')).not.toThrow();
  });
});

describe('applyTheme', () => {
  const metas = () => [...document.querySelectorAll('meta[name="theme-color"]')] as HTMLMetaElement[];

  it('sets data-theme and one forced theme-color for light and dark', () => {
    applyTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(metas().map((m) => [m.content, m.getAttribute('media')])).toEqual([
      [palette.dark.background, null],
    ]);
    applyTheme('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(metas().map((m) => m.content)).toEqual([palette.light.background]);
  });

  it('removes data-theme and restores both media metas for system', () => {
    applyTheme('dark');
    applyTheme('system');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(metas().map((m) => [m.content, m.getAttribute('media')])).toEqual([
      [palette.light.background, '(prefers-color-scheme: light)'],
      [palette.dark.background, '(prefers-color-scheme: dark)'],
    ]);
  });
});

describe('ThemeSwitch', () => {
  it('marks the current choice and writes storage on click', async () => {
    const user = userEvent.setup();
    renderWithApp(<ThemeSwitch />);
    const group = screen.getByRole('group', { name: /./ });
    expect(group).toBeInTheDocument();
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(3);
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'true');
    await user.click(buttons[2]!);
    expect(buttons[2]).toHaveAttribute('aria-pressed', 'true');
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'false');
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
