import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runnerImport, type Plugin } from 'vite';
import type { CompileResult } from './compile.ts';

// This file is bundled by Vite when it loads vite.config.ts and then run by plain Node, which cannot load the workspace
// TypeScript (`@nadel/shared` exports `src/index.ts`). So it holds no value import of the compiler or the catalogs:
// both are loaded through Vite's module runner (`runnerImport`), which transforms TypeScript on the fly.
const here = dirname(fileURLToPath(import.meta.url));
const compilerFile = resolve(here, 'compile.ts');
const defaultHelpDir = resolve(here, '..', '..', 'docs', 'help');
const catalogsDir = resolve(here, '..', '..', 'packages', 'shared', 'src', 'i18n');

const VIRTUAL_PREFIX = 'virtual:help/';
const RESOLVED_PREFIX = '\0virtual:help/';
const LOCALE_IDS = ['uk', 'en'] as const;

export interface HelpPluginOptions {
  /** Folder with `<lang>/<id>.md`; defaults to `docs/help` of the repository. */
  helpDir?: string;
}

/** Loads and runs the compiler. Exported for the capture and PDF scripts and for tests. */
export async function compileHelpFrom(helpDir: string): Promise<CompileResult> {
  const { module } = await runnerImport<typeof import('./compile.ts')>(compilerFile, { configFile: false });
  return module.compileHelp(helpDir);
}

/** Virtual modules `virtual:help/uk` and `virtual:help/en`: the compiled help of one language each. */
export function helpPlugin(options: HelpPluginOptions = {}): Plugin {
  const helpDir = options.helpDir ?? defaultHelpDir;
  let compiled: Promise<CompileResult> | null = null;
  const compile = (): Promise<CompileResult> => (compiled ??= compileHelpFrom(helpDir));

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
      for (const issue of result.issues) {
        this.warn(
          `help ${issue.code}${issue.pageId ? ` ${issue.locale ?? ''}/${issue.pageId}` : ''}: ${issue.detail}`,
        );
      }
      this.addWatchFile(helpDir);
      return `export default ${JSON.stringify(result.bundles[locale])};`;
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
