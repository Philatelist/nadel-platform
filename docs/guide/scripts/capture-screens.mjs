// Captures the guide screenshots from the locally running app (`make up`), with the demo users, in both languages.
// Usage: node docs/guide/scripts/capture-screens.mjs   (or `make guide-shots`)
// Writes docs/guide/img/<lang>/<name>.png. A screen that cannot be opened is reported and skipped; the PDF build then
// shows a placeholder for it. Read-only: it only opens pages, it never saves anything.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const guideDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(guideDir, '..', '..');
const nodeModules = process.env.GUIDE_NODE_MODULES ?? join(repoDir, 'web', 'node_modules');
const require = createRequire(join(nodeModules, 'noop.js'));
const { chromium } = require('@playwright/test');

const BASE = process.env.GUIDE_BASE_URL ?? 'http://localhost:5173';
const PASSWORD = process.env.GUIDE_DEMO_PASSWORD ?? 'nadel';
const DESKTOP = { width: 1280, height: 860 };
const PHONE = { width: 390, height: 844 };

/** A text in both languages (the guide screenshots are taken in each). */
const T = (uk, en) => ({ uk, en });
const thisMonth = kyivDay(0).slice(0, 7);
/** The first and the last day of the month `offset` months from now, for a past range the demo ledger covers. */
function monthRange(offset) {
  const [year, month] = thisMonth.split('-').map(Number);
  const first = new Date(Date.UTC(year, month - 1 + offset, 1));
  const last = new Date(Date.UTC(year, month + offset, 0));
  return { from: first.toISOString().slice(0, 10), to: last.toISOString().slice(0, 10) };
}
const augustLike = monthRange(-2);
/** Fills the two dates of a range run page and previews it (a preview writes nothing). */
const previewRange = [
  { fill: { label: T('З', 'From'), value: augustLike.from } },
  { fill: { label: T('По', 'To'), value: augustLike.to } },
  { click: T('Переглянути', 'Preview') },
];

/**
 * name, demo user, path, optional link to follow (CSS selector, optional exact text), optional phone viewport.
 * Also optional: `steps` ({ fill: { label, value } } or { click: button name }, nothing is ever saved) and `element`
 * (screenshot only the panel with this heading, or the element of a CSS selector).
 */
const SHOTS = [
  { name: 'plots', user: 'chairman', path: '/plots' },
  {
    name: 'plot-card',
    user: 'chairman',
    path: '/plots',
    open: { css: 'main a[href^="/plots/"]', text: '9' },
  },
  { name: 'plot-form', user: 'chairman', path: '/plots/new' },
  { name: 'members', user: 'chairman', path: '/members' },
  {
    name: 'member-card',
    user: 'chairman',
    path: '/members',
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' },
  },
  { name: 'roles', user: 'chairman', path: '/roles' },
  { name: 'settings', user: 'chairman', path: '/settings' },
  { name: 'imports', user: 'chairman', path: '/imports' },
  { name: 'tariffs', user: 'chairman', path: '/tariffs' },
  {
    name: 'tariff-card',
    user: 'chairman',
    path: '/tariffs',
    open: { css: 'main a[href^="/tariffs/"]:not([href$="/new"])' },
  },
  { name: 'tariff-form', user: 'chairman', path: '/tariffs/new' },
  { name: 'charge-types', user: 'chairman', path: '/charge-types' },
  { name: 'meters', user: 'chairman', path: '/meters' },
  {
    name: 'meter-card',
    user: 'chairman',
    path: '/meters',
    open: { css: 'main a[href^="/meters/"]', text: 'ЕЛ-9/2' },
  },
  // A range inside the demo readings, so the tree shows consumption and calculated values, not "no reading after".
  {
    name: 'meter-tree',
    user: 'chairman',
    path: '/settings/meter-tree',
    range: { fromDaysAgo: 21, toDaysAgo: 7 },
  },
  { name: 'approvals', user: 'chairman', path: '/readings/approvals' },
  { name: 'audit', user: 'chairman', path: '/audit' },
  { name: 'readings-round', user: 'electrician', path: '/readings' },
  { name: 'readings-round-phone', user: 'electrician', path: '/readings', phone: true },
  { name: 'member-account', user: 'member', path: '/account' },
  { name: 'member-account-phone', user: 'member', path: '/account', phone: true },
  { name: 'treasurer-plots', user: 'treasurer', path: '/plots' },
  // feature 011: charges, the plot account, water, the settings sections
  { name: 'charges', user: 'chairman', path: '/charges' },
  { name: 'charges-month', user: 'chairman', path: `/charges/months/${thisMonth}` },
  {
    name: 'charges-run',
    user: 'chairman',
    path: `/charges/months/${thisMonth}`,
    steps: [{ click: T('Нарахувати місяць', 'Charge the month') }],
  },
  {
    name: 'charges-close',
    user: 'chairman',
    path: `/charges/months/${thisMonth}`,
    steps: [{ click: T('Закрити місяць', 'Close the month') }],
  },
  { name: 'charges-not-charged', user: 'chairman', path: '/charges/not-charged' },
  { name: 'charges-losses', user: 'chairman', path: '/charges/losses', steps: previewRange },
  { name: 'charges-common', user: 'chairman', path: '/charges/common', steps: previewRange },
  {
    name: 'plot-account',
    user: 'chairman',
    path: '/plots',
    open: { css: 'main a[href^="/plots/"]', text: '7' },
    element: { heading: T('Рахунок', 'Account') },
  },
  {
    name: 'plot-account-charge',
    user: 'chairman',
    path: '/plots',
    open: { css: 'main a[href^="/plots/"]', text: '7' },
    steps: [{ click: T('Подробиці', 'Details') }],
  },
  {
    name: 'plot-water',
    user: 'chairman',
    path: '/plots',
    open: { css: 'main a[href^="/plots/"]', text: '7' },
    element: { heading: T('Вода', 'Water') },
  },
  {
    name: 'settings-thresholds',
    user: 'chairman',
    path: '/settings',
    element: { heading: T('Поріг червоного статусу', 'Red status threshold') },
  },
  {
    name: 'settings-losses',
    user: 'chairman',
    path: '/settings',
    element: { heading: T('Втрати', 'Losses') },
  },
  {
    name: 'settings-common',
    user: 'chairman',
    path: '/settings',
    element: { heading: T('Спільні споживачі', 'Common consumers') },
  },
  {
    name: 'settings-water',
    user: 'chairman',
    path: '/settings',
    element: { heading: T('Водопостачання', 'Water supply') },
  },
  { name: 'treasurer-charges', user: 'treasurer', path: `/charges/months/${thisMonth}` },
  {
    name: 'member-charges',
    user: 'member',
    path: '/account',
    element: { css: 'section[aria-labelledby="my-charges"]' },
  },
];

/** A calendar day in Kyiv as YYYY-MM-DD, shifted by offsetDays. */
function kyivDay(offsetDays) {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Kyiv' }).format(new Date());
  const day = new Date(`${today}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + offsetDays);
  return day.toISOString().slice(0, 10);
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

const browser = await chromium.launch(
  process.env.GUIDE_CHROMIUM ? { executablePath: process.env.GUIDE_CHROMIUM } : {},
);
const failed = [];
try {
  for (const lang of ['uk', 'en']) {
    const outDir = join(guideDir, 'img', lang);
    mkdirSync(outDir, { recursive: true });
    for (const user of [...new Set(SHOTS.map((s) => s.user))]) {
      for (const phone of [false, true]) {
        const shots = SHOTS.filter((s) => s.user === user && Boolean(s.phone) === phone);
        if (shots.length === 0) continue;
        const context = await browser.newContext({
          viewport: phone ? PHONE : DESKTOP,
          deviceScaleFactor: 2,
          colorScheme: 'light',
          locale: lang === 'uk' ? 'uk-UA' : 'en-US',
          timezoneId: 'Europe/Kyiv',
        });
        await context.addInitScript((l) => {
          try {
            window.localStorage.setItem('nadel.locale', l);
          } catch {
            // storage unavailable: the browser locale decides
          }
        }, lang);
        const page = await context.newPage();
        try {
          await signIn(page, user);
        } catch (error) {
          failed.push(`${lang}/${user}: sign-in failed (${error.message.split('\n')[0]})`);
          await context.close();
          continue;
        }
        for (const shot of shots) {
          try {
            await page.goto(BASE + shot.path, { waitUntil: 'networkidle' });
            if (shot.open) {
              let link = page.locator(shot.open.css);
              if (shot.open.text)
                link = link.filter({
                  hasText: new RegExp(`^\\s*${shot.open.text.replace(/[/.-]/g, '\\$&')}\\s*$`),
                });
              await link.first().click({ timeout: 10000 });
              await page.waitForLoadState('networkidle');
            }
            if (shot.range) {
              const dates = page.locator('input[type="date"]');
              await dates.nth(0).fill(kyivDay(-shot.range.fromDaysAgo));
              await dates.nth(1).fill(kyivDay(-shot.range.toDaysAgo));
              await page.waitForLoadState('networkidle');
            }
            for (const step of shot.steps ?? []) {
              if (step.fill) {
                await page.getByLabel(step.fill.label[lang], { exact: true }).fill(step.fill.value);
              } else {
                await page
                  .getByRole('button', { name: step.click[lang], exact: true })
                  .first()
                  .click({ timeout: 10000 });
                await page.waitForLoadState('networkidle');
              }
            }
            if (shot.steps?.some((step) => step.click)) await page.waitForTimeout(1200);
            await page.waitForTimeout(400);
            const file = join(outDir, `${shot.name}.png`);
            if (shot.element) {
              const target = shot.element.heading
                ? page
                    .locator('main section')
                    .filter({
                      has: page.getByRole('heading', {
                        level: 2,
                        name: shot.element.heading[lang],
                        exact: true,
                      }),
                    })
                    .first()
                : page.locator(shot.element.css).first();
              await target.scrollIntoViewIfNeeded();
              await target.screenshot({ path: file });
            } else {
              await page.screenshot({ path: file });
            }
            console.log(`${lang}/${shot.name}.png`);
          } catch (error) {
            failed.push(`${lang}/${shot.name}: ${error.message.split('\n')[0]}`);
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
  console.warn(`\nNot captured (${failed.length}):\n  ${failed.join('\n  ')}`);
  process.exitCode = 1;
}
