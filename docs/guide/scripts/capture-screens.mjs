// Captures the help screenshots from the locally running app (`make up`) with the demo users, in both languages.
// Usage: node docs/guide/scripts/capture-screens.mjs [--page <id>]   (or `make guide-shots`, `make help-shots ID=<id>`)
// The shots are listed in the front matter of the help pages (docs/help/<lang>/<id>.md): the Ukrainian page's list is taken
// in the Ukrainian interface, the English page's list in the English one. Output: web/public/help/img/<lang>/<name>.png.
// Read-only: it only opens pages, previews and dialogs, it never saves anything.
// Strict: a shot that cannot be taken is reported as `<page id>: <shot>: <reason>` and the exit code is 1. No placeholder.
// Demo guard: before any shot it signs in as the chairman and reads GET /api/v1/me; unless the cooperative is the demo
// cooperative of backend/src/main/resources/db/demo/R__demo_data.sql, it takes nothing and exits with 1.
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { helpImgDir, loadHelp, require } from './helpPages.mjs';

const { chromium } = require('@playwright/test');

const BASE = process.env.GUIDE_BASE_URL ?? 'http://localhost:5173';
const PASSWORD = process.env.GUIDE_DEMO_PASSWORD ?? 'nadel';
const DESKTOP = { width: 1280, height: 860 };
const PHONE = { width: 390, height: 844 };
/** The id of the demo cooperative "СТ «Надія»" in R__demo_data.sql. */
const DEMO_TENANT_ID = '11111111-1111-1111-1111-111111111111';

const args = process.argv.slice(2);
const pageFlag = args.indexOf('--page');
const onlyPage = pageFlag >= 0 ? args[pageFlag + 1] : null;
if (pageFlag >= 0 && !onlyPage) {
  console.error('--page needs a page id, for example: --page payments-enter');
  process.exit(1);
}

async function signIn(page, user) {
  await page.goto(BASE + '/');
  const button = page.getByRole('button', { name: /^(Sign in|Увійти)$/ });
  const username = page.locator('#username');
  await button.or(username).first().waitFor({ timeout: 20000 });
  if (await button.isVisible()) await button.click();
  await username.fill(`${user}@demo.local`);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
  await page.waitForURL(BASE + '/**', { timeout: 20000 });
  await page.getByRole('banner').waitFor({ timeout: 20000 });
}

function newContext(browser, lang, phone) {
  return browser.newContext({
    viewport: phone ? PHONE : DESKTOP,
    deviceScaleFactor: 2,
    colorScheme: 'light',
    locale: lang === 'uk' ? 'uk-UA' : 'en-US',
    timezoneId: 'Europe/Kyiv',
  });
}

async function setLocale(context, lang) {
  await context.addInitScript((l) => {
    try {
      window.localStorage.setItem('nadel.locale', l);
    } catch {
      // storage unavailable: the browser locale decides
    }
  }, lang);
}

/** Reads GET /api/v1/me while the chairman signs in; exits when the cooperative is not the demo one. */
async function guardDemo(browser) {
  const context = await newContext(browser, 'en', false);
  try {
    const page = await context.newPage();
    const me = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/me' && r.ok(), { timeout: 30000 });
    await signIn(page, 'chairman');
    const body = await (await me).json();
    if (body?.tenant?.id !== DEMO_TENANT_ID) {
      console.error(
        `Refusing to take screenshots: the app at ${BASE} runs on the cooperative ` +
          `"${body?.tenant?.name ?? 'unknown'}" (${body?.tenant?.id ?? 'no tenant'}), not on the demo data ` +
          `(${DEMO_TENANT_ID}). Start the demo with \`make up\` and a fresh database.`,
      );
      process.exit(1);
    }
  } finally {
    await context.close();
  }
}

/** Opens the record a shot points at (a link of a list), see `open` in the page front matter. */
async function openRecord(page, open) {
  let link = page.locator(open.css);
  // A table row without files (the last cell is 0) is skipped; `rowText` keeps only rows with this text.
  if (open.withFiles)
    link = page
      .locator('main tbody tr')
      .filter({ hasNot: page.locator('td:last-child', { hasText: /^\s*0\s*$/ }) })
      .filter(open.rowText ? { hasText: open.rowText } : {})
      .locator(open.css.replace(/^main /, ''));
  if (open.text)
    link = link.filter({ hasText: new RegExp(`^\\s*${String(open.text).replace(/[/.-]/g, '\\$&')}\\s*$`) });
  await link.first().click({ timeout: 10000 });
  await page.waitForLoadState('networkidle');
}

/** One step of a shot: fill, check, link, tab, table or click. The names are in the language of the page. */
async function runStep(page, step) {
  if (step.fill) {
    const scope = step.fill.within ? page.getByRole('form', { name: step.fill.within, exact: true }) : page;
    await scope.getByLabel(step.fill.label, { exact: true }).fill(String(step.fill.value));
  } else if (step.link) {
    await page.getByRole('link', { name: step.link, exact: true }).first().click({ timeout: 10000 });
    await page.waitForLoadState('networkidle');
  } else if (step.tab) {
    await page
      .getByRole('tab', { name: step.tab, exact: true })
      .or(page.getByRole('link', { name: step.tab, exact: true }))
      .first()
      .click({ timeout: 10000 });
    await page.waitForLoadState('networkidle');
  } else if (step.table) {
    // Wait for the table, then bring it into the viewport so the shot shows it.
    const table = page.getByRole('table', { name: step.table, exact: true });
    await table.waitFor({ timeout: 15000 });
    await table.scrollIntoViewIfNeeded();
  } else if (step.check) {
    await page.getByLabel(step.check, { exact: true }).click();
    await page.waitForLoadState('networkidle');
  } else if (step.click) {
    await page.getByRole('button', { name: step.click, exact: true }).first().click({ timeout: 10000 });
    await page.waitForLoadState('networkidle');
  } else {
    throw new Error(`unknown step ${JSON.stringify(step)}`);
  }
}

async function takeShot(page, shot, file) {
  await page.goto(BASE + shot.path, { waitUntil: 'networkidle' });
  if (shot.open) await openRecord(page, shot.open);
  for (const step of shot.steps ?? []) await runStep(page, step);
  if (shot.steps?.some((step) => step.click)) await page.waitForTimeout(1200);
  await page.waitForTimeout(400);
  if (shot.element) {
    const target = shot.element.heading
      ? page
          .locator('main section')
          .filter({
            has: page.getByRole('heading', { level: 2, name: shot.element.heading, exact: true }),
          })
          .first()
      : page.locator(shot.element.css).first();
    await target.scrollIntoViewIfNeeded();
    await target.screenshot({ path: file });
  } else {
    await page.screenshot({ path: file });
  }
}

const { pages, issues } = await loadHelp();
for (const issue of issues) {
  // Missing screenshot files are what this script produces; anything else is the author's to fix, reported not hidden.
  if (issue.code !== 'MISSING_SHOT')
    console.warn(`help issue ${issue.code}${issue.pageId ? ` (${issue.pageId})` : ''}: ${issue.detail}`);
}
if (onlyPage && !pages.uk.some((p) => p.id === onlyPage) && !pages.en.some((p) => p.id === onlyPage)) {
  console.error(`No help page "${onlyPage}" in docs/help.`);
  process.exit(1);
}

const browser = await chromium.launch(
  process.env.GUIDE_CHROMIUM ? { executablePath: process.env.GUIDE_CHROMIUM } : {},
);
const failed = [];
try {
  await guardDemo(browser);
  for (const lang of ['uk', 'en']) {
    const outDir = join(helpImgDir, lang);
    mkdirSync(outDir, { recursive: true });
    const items = pages[lang]
      .filter((p) => !onlyPage || p.id === onlyPage)
      .flatMap((p) => p.shots.map((shot) => ({ pageId: p.id, shot })));
    for (const user of [...new Set(items.map((i) => i.shot.user))]) {
      for (const phone of [false, true]) {
        const group = items.filter((i) => i.shot.user === user && (i.shot.viewport === 'phone') === phone);
        if (group.length === 0) continue;
        const context = await newContext(browser, lang, phone);
        await setLocale(context, lang);
        const page = await context.newPage();
        try {
          await signIn(page, user);
        } catch (error) {
          for (const { pageId, shot } of group)
            failed.push(`${pageId}: ${shot.name}: sign-in as ${user} failed (${error.message.split('\n')[0]})`);
          await context.close();
          continue;
        }
        for (const { pageId, shot } of group) {
          try {
            await takeShot(page, shot, join(outDir, `${shot.name}.png`));
            console.log(`${lang}/${shot.name}.png`);
          } catch (error) {
            failed.push(`${pageId}: ${shot.name}: [${lang}] ${error.message.split('\n')[0]}`);
          }
        }
        await context.close();
      }
    }
  }
} finally {
  await browser.close();
}
if (failed.length > 0) {
  console.error(`\nNot captured (${failed.length}):\n  ${failed.join('\n  ')}`);
  process.exitCode = 1;
}
