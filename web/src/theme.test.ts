import { palette, plotStatusColors, plotStatusOnColor } from '@nadel/ui-tokens';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

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
