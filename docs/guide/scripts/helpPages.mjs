// Shared by the guide scripts: finds the hoisted dev dependencies and loads the compiled help pages.
// The help compiler is TypeScript, so it is loaded the way the Vite plugin loads it: through Vite's module runner
// (`runnerImport` of web/help/vitePlugin.ts, which exports `compileHelpFrom`). No build step, no copy of the parser.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const guideDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const repoDir = resolve(guideDir, '..', '..');
export const helpDir = join(repoDir, 'docs', 'help');
export const helpImgDir = join(repoDir, 'web', 'public', 'help', 'img');

const nodeModules = process.env.GUIDE_NODE_MODULES ?? join(repoDir, 'web', 'node_modules');
// Resolution walks up from web/node_modules, so a hoisted root node_modules is found too.
export const require = createRequire(join(nodeModules, 'noop.js'));

/** The folder of an installed package (it may be hoisted to the repository root), or null. */
export function packageDir(name) {
  try {
    return dirname(require.resolve(`${name}/package.json`));
  } catch {
    return null;
  }
}

/** Compiles docs/help and returns { pages: { uk, en }, issues, labels: { uk, en } } (pages in contents order). */
export async function loadHelp() {
  const viteDir = packageDir('vite');
  if (!viteDir) throw new Error('vite is not installed: run `make install`');
  const manifest = JSON.parse(readFileSync(join(viteDir, 'package.json'), 'utf8'));
  const entry = typeof manifest.exports['.'] === 'string' ? manifest.exports['.'] : manifest.exports['.'].import;
  const entryFile = typeof entry === 'string' ? entry : entry.default;
  const { runnerImport } = await import(pathToFileURL(join(viteDir, entryFile)).href);
  const { module } = await runnerImport(join(repoDir, 'web', 'help', 'vitePlugin.ts'), { configFile: false });
  const result = await module.compileHelpFrom(helpDir, helpImgDir);
  // The labels of the help (section names, the «Важливо» title) come from the same catalogs as the application.
  const { module: i18n } = await runnerImport(join(repoDir, 'packages', 'shared', 'src', 'i18n', 'index.ts'), {
    configFile: false,
  });
  const labels = { uk: i18n.messages('uk').help, en: i18n.messages('en').help };
  return { pages: result.pages, issues: result.issues, labels };
}
