// Builds the guide PDFs from docs/guide/<lang>/*.html with Playwright's Chromium.
// Usage: node docs/guide/scripts/build-pdf.mjs   (or `make guide`)
// Dependencies come from web/node_modules (Playwright and the IBM Plex fonts); GUIDE_NODE_MODULES overrides the path.
import { createRequire } from 'node:module';
import { existsSync, readdirSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve, basename } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const guideDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(guideDir, '..', '..');
const nodeModules = process.env.GUIDE_NODE_MODULES ?? join(repoDir, 'web', 'node_modules');
const require = createRequire(join(nodeModules, 'noop.js'));
const { chromium } = require('@playwright/test');

const LANGS = {
  uk: { missing: 'Знімок екрана з’явиться після команди make guide-shots', page: 'Сторінка', of: 'з' },
  en: { missing: 'The screenshot appears after make guide-shots', page: 'Page', of: 'of' },
};

const fontCss = [
  ['ibm-plex-sans', ['400', '400-italic', '600', '700']],
  ['ibm-plex-serif', ['600']],
  ['ibm-plex-mono', ['400']],
]
  .flatMap(([pkg, weights]) => weights.map((w) => join(nodeModules, '@fontsource', pkg, `${w}.css`)))
  .filter((file) => existsSync(file));

const outDir = join(guideDir, 'pdf');
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch(
  process.env.GUIDE_CHROMIUM ? { executablePath: process.env.GUIDE_CHROMIUM } : {},
);
try {
  for (const [lang, text] of Object.entries(LANGS)) {
    const langDir = join(guideDir, lang);
    if (!existsSync(langDir)) continue;
    for (const file of readdirSync(langDir).filter((f) => f.endsWith('.html'))) {
      const page = await browser.newPage();
      await page.goto(pathToFileURL(join(langDir, file)).href, { waitUntil: 'load' });
      for (const css of fontCss) await page.addStyleTag({ url: pathToFileURL(css).href });
      await page.evaluate(async (missing) => {
        await document.fonts.ready;
        for (const img of Array.from(document.images)) {
          if (img.complete && img.naturalWidth > 0) continue;
          const box = document.createElement('div');
          box.className = 'shot-missing';
          box.textContent = missing;
          img.replaceWith(box);
        }
      }, text.missing);
      const title = await page.title();
      // A one-page leaflet (<name>-leaflet.html) has no running footer.
      const leaflet = file.includes('-leaflet');
      const footer = `<div style="width:100%;font:7.5pt 'IBM Plex Sans',sans-serif;color:#5b6a60;padding:0 18mm;display:flex;justify-content:space-between">
        <span>${title}</span><span>${text.page} <span class="pageNumber"></span> ${text.of} <span class="totalPages"></span></span></div>`;
      const out = join(outDir, `${basename(file, '.html')}.${lang}.pdf`);
      await page.pdf({
        path: out,
        format: 'A4',
        printBackground: true,
        preferCSSPageSize: true,
        displayHeaderFooter: !leaflet,
        headerTemplate: '<span></span>',
        footerTemplate: footer,
        tagged: true,
        outline: true,
      });
      console.log(`built ${out.slice(repoDir.length + 1)}`);
      await page.close();
    }
  }
} finally {
  await browser.close();
}
