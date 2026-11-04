import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runnerImport, type Plugin } from 'vite';
import type { CompileResult } from './compile.ts';
import { formatIssues } from './check.ts';
import { buildHelpShells } from './shells.ts';

// This file is bundled by Vite when it loads vite.config.ts and then run by plain Node, which cannot load the workspace
// TypeScript (`@nadel/shared` exports `src/index.ts`). So it holds no value import of the compiler or the catalogs:
// both are loaded through Vite's module runner (`runnerImport`), which transforms TypeScript on the fly.
const here = dirname(fileURLToPath(import.meta.url));
const compilerFile = resolve(here, 'compile.ts');
const defaultHelpDir = resolve(here, '..', '..', 'docs', 'help');
const permissionFile = resolve(
  here,
  '..',
  '..',
  'packages',
  'api-client',
  'src',
  'generated',
  'model',
  'permission.ts',
);
const defaultImgDir = resolve(here, '..', 'public', 'help', 'img');
const catalogsDir = resolve(here, '..', '..', 'packages', 'shared', 'src', 'i18n');

const VIRTUAL_PREFIX = 'virtual:help/';
const RESOLVED_PREFIX = '\0virtual:help/';
const LOCALE_IDS = ['uk', 'en'] as const;

export interface HelpPluginOptions {
  /** Folder with `<lang>/<id>.md`; defaults to `docs/help` of the repository. */
  helpDir?: string;
}

/** Loads and runs the compiler. Exported for the capture and PDF scripts and for tests. */
export async function compileHelpFrom(helpDir: string, imgDir = defaultImgDir): Promise<CompileResult> {
  const { module } = await runnerImport<typeof import('./compile.ts')>(compilerFile, { configFile: false });
  const { module: permission } = await runnerImport<Record<string, unknown>>(permissionFile, {
    configFile: false,
  });
  // Routes and menu are not loaded here: App.tsx pulls in React pages, CSS and the OIDC client, which Node cannot run.
  // Their coverage is checked by web/src/help/helpContent.test.ts; everything else is checked at every build.
  return module.compileHelp(helpDir, {
    routes: null,
    navItems: null,
    permissions: Object.values(permission.Permission as Record<string, string>),
    shotExists: (locale, name) => existsSync(resolve(imgDir, locale, `${name}.png`)),
  });
}

function existsIndex(dir: string): boolean {
  try {
    readFileSync(resolve(dir, 'index.html'));
    return true;
  } catch {
    return false;
  }
}

/** Virtual modules `virtual:help/uk` and `virtual:help/en`: the compiled help of one language each. */
export function helpPlugin(options: HelpPluginOptions = {}): Plugin {
  const helpDir = options.helpDir ?? defaultHelpDir;
  let compiled: Promise<CompileResult> | null = null;
  let reported: CompileResult | null = null;
  let isBuild = false;
  const compile = (): Promise<CompileResult> => (compiled ??= compileHelpFrom(helpDir));

  let outDir = '';
  let siteUrl = '';

  const isSource = (file: string): boolean =>
    file.startsWith(helpDir + sep) || file.startsWith(catalogsDir + sep) || file.startsWith(here + sep);

  return {
    name: 'nadel-help',
    resolveId(id) {
      return id.startsWith(VIRTUAL_PREFIX) &&
        (LOCALE_IDS as readonly string[]).includes(id.slice(VIRTUAL_PREFIX.length))
        ? RESOLVED_PREFIX + id.slice(VIRTUAL_PREFIX.length)
        : null;
    },
    async load(id) {
      if (!id.startsWith(RESOLVED_PREFIX)) return null;
      const locale = id.slice(RESOLVED_PREFIX.length) as (typeof LOCALE_IDS)[number];
      const result = await compile();
      // Once per compile, not once per language module.
      if (reported !== result && result.issues.length > 0) {
        reported = result;
        const message = `help has ${result.issues.length} issue(s):\n${formatIssues(result.issues)}`;
        if (isBuild && process.env.HELP_CHECK !== 'warn') this.error(message);
        else this.warn(message);
      }
      this.addWatchFile(helpDir);
      return `export default ${JSON.stringify(result.bundles[locale])};`;
    },
    configResolved(config) {
      isBuild = config.command === 'build';
      outDir = resolve(config.root, config.build.outDir);
      siteUrl = String(config.env.VITE_SITE_URL ?? 'http://localhost:5173').replace(/\/+$/, '');
    },
    // Static shells of the public help (uk only): /help and /help/<id> carry their own head tags for crawlers.
    async closeBundle() {
      if (!outDir || !existsIndex(outDir)) return;
      const result = await compile();
      const { module } = await runnerImport<typeof import('../../packages/shared/src/i18n/index.ts')>(
        resolve(catalogsDir, 'index.ts'),
        { configFile: false },
      );
      const uk = module.messages('uk');
      const shells = buildHelpShells({
        template: readFileSync(resolve(outDir, 'index.html'), 'utf8'),
        pages: result.bundles.uk.pages,
        siteUrl,
        contents: { title: uk.help.title, description: uk.help.contents },
        lang: 'uk',
      });
      for (const { file, html } of shells) {
        const target = resolve(outDir, file);
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, html);
      }
    },
    configureServer(server) {
      server.watcher.add(helpDir);
      const refresh = (file: string): void => {
        if (!isSource(resolve(file))) return;
        compiled = null;
        for (const locale of LOCALE_IDS) {
          const module = server.moduleGraph.getModuleById(RESOLVED_PREFIX + locale);
          if (module) server.moduleGraph.invalidateModule(module);
        }
        server.ws.send({ type: 'full-reload' });
      };
      server.watcher.on('change', refresh).on('add', refresh).on('unlink', refresh);
    },
  };
}
