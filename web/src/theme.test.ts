// @vitest-environment node
import { palette, plotStatusColors, plotStatusOnColor } from '@nadel/ui-tokens';
import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('./index.css', import.meta.url), 'utf8');

function kebab(key: string): string {
  return key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

/** Returns the body of the first `{ ... }` block that follows the given opener. */
function blockAfter(opener: string): string {
  const start = css.indexOf(opener);
  if (start < 0) throw new Error(`index.css has no "${opener}" block`);
  const open = css.indexOf('{', start + opener.length - 1);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}' && --depth === 0) return css.slice(open + 1, i);
  }
  throw new Error(`unterminated "${opener}" block`);
}

function colorVars(body: string): Record<string, string> {
  const vars: Record<string, string> = {};
  for (const m of body.matchAll(/(--color-[\w-]+)\s*:\s*([^;]+);/g)) vars[m[1]!] = m[2]!.trim().toLowerCase();
  return vars;
}

function expected(theme: 'light' | 'dark'): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(palette[theme])) out[`--color-${kebab(k)}`] = v.toLowerCase();
  for (const [k, v] of Object.entries(plotStatusColors[theme]))
    out[`--color-status-${k.toLowerCase()}`] = v.toLowerCase();
  for (const [k, v] of Object.entries(plotStatusOnColor[theme]))
    out[`--color-status-on-${k.toLowerCase()}`] = v.toLowerCase();
  return out;
}

describe('index.css matches @nadel/ui-tokens', () => {
  it('light colors in @theme equal the light tokens, with none missing or extra', () => {
    expect(colorVars(blockAfter('@theme {'))).toEqual(expected('light'));
  });

  it('dark colors in the prefers-color-scheme block equal the dark tokens, with none missing or extra', () => {
    expect(colorVars(blockAfter('@media (prefers-color-scheme: dark) {'))).toEqual(expected('dark'));
  });

  it('does not use var() inside @theme values', () => {
    expect(blockAfter('@theme {')).not.toContain('var(');
  });
});

function sourceFiles(dir: URL): URL[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const url = new URL(e.name + (e.isDirectory() ? '/' : ''), dir);
    if (e.isDirectory()) return sourceFiles(url);
    return /\.(ts|tsx|css)$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [url] : [];
  });
}

describe('color variable references', () => {
  it('every --color-* reference in web/src is declared in index.css', () => {
    const declared = new Set(Object.keys(colorVars(css)));
    const stale: string[] = [];
    for (const file of sourceFiles(new URL('./', import.meta.url))) {
      for (const m of readFileSync(file, 'utf8').matchAll(/--color-[a-z-]+/g)) {
        // Dynamic names such as `--color-status-${s}` leave a trailing dash; skip those.
        if (m[0].endsWith('-')) continue;
        if (!declared.has(m[0])) stale.push(`${m[0]} in ${file.pathname.split('/src/')[1]}`);
      }
    }
    expect(stale).toEqual([]);
  });
});
