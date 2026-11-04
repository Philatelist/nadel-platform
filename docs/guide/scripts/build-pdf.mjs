// Builds the guide PDFs with Playwright's Chromium.
//  - The board guide (uk, en) is built from the help pages in docs/help (the source of truth), in contents order:
//    a title page with the version, a table of contents, one chapter per page. Screenshots come from
//    web/public/help/img/<lang>/; a missing one is shown as a placeholder (the strict check is the job of
//    `make guide-shots`).
//  - The owner manual and the leaflet are built from docs/guide/<lang>/*.html as before. A board-guide.html left in
//    those folders is ignored: the help is the source now.
// Usage: node docs/guide/scripts/build-pdf.mjs   (or `make guide`)
// Dependencies come from web/node_modules or the hoisted root node_modules (Playwright, Vite, IBM Plex fonts);
// GUIDE_NODE_MODULES overrides the path.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
import { guideDir, helpImgDir, loadHelp, packageDir, repoDir, require } from './helpPages.mjs';

const { chromium } = require('@playwright/test');

const LANGS = {
  uk: {
    missing: 'Знімок екрана з’явиться після команди make guide-shots',
    page: 'Сторінка',
    of: 'з',
    title: 'Наділ — посібник для правління',
    brand: 'облік платежів садового товариства',
    lead: 'Як працювати в системі голові, казначею та електрику: кожен екран, кожна кнопка, з прикладами.',
    version: 'Версія',
    contents: 'Зміст',
  },
  en: {
    missing: 'The screenshot appears after make guide-shots',
    page: 'Page',
    of: 'of',
    title: 'Nadel — board guide',
    brand: 'payment accounting for garden cooperatives',
    lead: 'How the chairman, the treasurer and the electrician work in the system: every screen and every button, with examples.',
    version: 'Version',
    contents: 'Contents',
  },
};

const escapeHtml = (text) =>
  String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** The version of the guide: the last tag, else the date of the last commit. */
function guideVersion() {
  const git = (...args) => {
    try {
      return execFileSync('git', args, { cwd: repoDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    } catch {
      return '';
    }
  };
  return git('describe', '--tags', '--abbrev=0') || git('log', '-1', '--format=%cs') || '';
}

const HEADING = { h2: 'h3', h3: 'h4', h4: 'h5' };

function plainText(nodes) {
  return nodes.map((n) => (n.type === 'text' ? n.text : 'children' in n ? plainText(n.children) : '')).join('');
}

/** Renders the compiled node tree of a help page to static HTML. Everything from the page is escaped. */
function renderNodes(nodes, ctx) {
  return nodes.map((node) => renderNode(node, ctx)).join('');
}

function renderNode(node, ctx) {
  switch (node.type) {
    case 'text':
      return escapeHtml(node.text);
    case 'br':
      return '<br />';
    case 'hr':
      return '<hr />';
    case 'img': {
      const file = join(helpImgDir, ctx.lang, `${node.shot}.png`);
      const phone = ctx.page.shots.find((s) => s.name === node.shot)?.viewport === 'phone';
      const body = existsSync(file)
        ? `<img src="${pathToFileURL(file).href}" alt="${escapeHtml(node.alt)}" />`
        : `<div class="shot-missing">${escapeHtml(ctx.text.missing)}</div>`;
      return `<figure${phone ? ' class="phone"' : ''}>${body}<figcaption>${escapeHtml(node.alt)}</figcaption></figure>`;
    }
    case 'link': {
      // A link between pages is plain text in print; with no text it shows the target page title.
      const text = renderNodes(node.children, ctx);
      return text || escapeHtml(ctx.titles.get(node.pageId) ?? node.pageId);
    }
    case 'a':
      return `${renderNodes(node.children, ctx)} (${escapeHtml(node.href)})`;
    case 'ui':
      return `<span class="ui">${escapeHtml(node.text)}</span>`;
    case 'callout':
      return `<div class="warn"><span class="callout-title">${escapeHtml(ctx.labels.important)}</span>${renderNodes(node.children, ctx)}</div>`;
    default: {
      const tag = HEADING[node.type] ?? node.type;
      return `<${tag}>${renderNodes(node.children, ctx)}</${tag}>`;
    }
  }
}

/** The whole board guide of one language as an HTML document (cover, contents, a chapter per help page). */
function boardGuideHtml(lang, text, pages, labels, version) {
  const titles = new Map(pages.map((p) => [p.id, p.title]));
  const sections = [...new Set(pages.map((p) => p.section))];
  const toc = sections
    .map(
      (section) =>
        `<li class="toc-part"><a href="#s-${section}">${escapeHtml(labels.sections[section] ?? section)}</a></li>` +
        pages
          .filter((p) => p.section === section)
          .map((p) => `<li><a href="#p-${escapeHtml(p.id)}">${escapeHtml(p.title)}</a></li>`)
          .join(''),
    )
    .join('');
  let lastSection = '';
  const chapters = pages
    .map((page) => {
      const marker = page.section !== lastSection ? `<span id="s-${page.section}"></span>` : '';
      lastSection = page.section;
      const ctx = { lang, text, labels, titles, page };
      return `<section class="chapter" id="p-${escapeHtml(page.id)}">${marker}<h2>${escapeHtml(page.title)}</h2>${renderNodes(page.body, ctx)}</section>`;
    })
    .join('\n');
  return `<!doctype html>
<html lang="${lang}"><head><meta charset="utf-8" /><title>${escapeHtml(text.title)}</title>
<link rel="stylesheet" href="${pathToFileURL(join(guideDir, 'guide.css')).href}" /></head>
<body>
<div class="cover"><div class="brand"><b>${lang === 'uk' ? 'Наділ' : 'Nadel'}</b> · ${escapeHtml(text.brand)}</div>
<h1>${escapeHtml(text.title.split('— ')[1] ?? text.title)}</h1><p class="lead">${escapeHtml(text.lead)}</p>
<dl class="meta"><dt>${escapeHtml(text.version)}</dt><dd>${escapeHtml(version)}</dd></dl></div>
<nav class="toc"><h2>${escapeHtml(text.contents)}</h2><ol>${toc}</ol></nav>
${chapters}
</body></html>`;
}

const fontCss = [
  ['ibm-plex-sans', ['400', '400-italic', '600', '700']],
  ['ibm-plex-serif', ['600']],
  ['ibm-plex-mono', ['400']],
]
  .flatMap(([pkg, weights]) =>
    weights.map((w) => {
      const dir = packageDir(`@fontsource/${pkg}`);
      return dir ? join(dir, `${w}.css`) : '';
    }),
  )
  .filter((file) => file && existsSync(file));

const outDir = join(guideDir, 'pdf');
mkdirSync(outDir, { recursive: true });
const workDir = mkdtempSync(join(tmpdir(), 'nadel-guide-'));

const { pages, issues, labels } = await loadHelp();
for (const issue of issues) {
  if (issue.code !== 'MISSING_SHOT')
    console.warn(`help issue ${issue.code}${issue.pageId ? ` (${issue.pageId})` : ''}: ${issue.detail}`);
}
const version = guideVersion();

/** The documents to build: the board guide from the help, the rest from docs/guide/<lang>/*.html. */
const jobs = [];
for (const [lang, text] of Object.entries(LANGS)) {
  if (pages[lang].length > 0) {
    const file = join(workDir, `board-guide.${lang}.html`);
    writeFileSync(file, boardGuideHtml(lang, text, pages[lang], labels[lang], version));
    jobs.push({ lang, text, file, name: 'board-guide' });
  }
  const langDir = join(guideDir, lang);
  if (!existsSync(langDir)) continue;
  for (const f of readdirSync(langDir).filter((n) => n.endsWith('.html') && n !== 'board-guide.html'))
    jobs.push({ lang, text, file: join(langDir, f), name: basename(f, '.html') });
}

const browser = await chromium.launch(
  process.env.GUIDE_CHROMIUM ? { executablePath: process.env.GUIDE_CHROMIUM } : {},
);
try {
  for (const { lang, text, file, name } of jobs) {
    const page = await browser.newPage();
    await page.goto(pathToFileURL(file).href, { waitUntil: 'load' });
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
    const leaflet = name.includes('-leaflet');
    const footer = `<div style="width:100%;font:7.5pt 'IBM Plex Sans',sans-serif;color:#5b6a60;padding:0 18mm;display:flex;justify-content:space-between">
      <span>${escapeHtml(title)}</span><span>${text.page} <span class="pageNumber"></span> ${text.of} <span class="totalPages"></span></span></div>`;
    const out = join(outDir, `${name}.${lang}.pdf`);
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
} finally {
  await browser.close();
  rmSync(workDir, { recursive: true, force: true });
}
