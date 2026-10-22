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

/** name, demo user, path, optional link to follow (CSS selector, optional exact text), optional phone viewport. */
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
  { name: 'meter-tree', user: 'chairman', path: '/settings/meter-tree' },
  { name: 'approvals', user: 'chairman', path: '/readings/approvals' },
  { name: 'audit', user: 'chairman', path: '/audit' },
  { name: 'readings-round', user: 'electrician', path: '/readings' },
  { name: 'readings-round-phone', user: 'electrician', path: '/readings', phone: true },
  { name: 'member-account', user: 'member', path: '/account' },
  { name: 'member-account-phone', user: 'member', path: '/account', phone: true },
  { name: 'treasurer-plots', user: 'treasurer', path: '/plots' },
];

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
            await page.waitForTimeout(400);
            await page.screenshot({ path: join(outDir, `${shot.name}.png`) });
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
