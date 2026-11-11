// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// The installable app (feature 021 section 2.1), against the PRODUCTION build served by `vite preview`
// (`make e2e-pwa`; the service worker and the manifest exist only in a build): the manifest and its icons, an active
// worker with scope «/», nothing but static files in the browser caches after a member used the app, the offline
// banner with no member data, the help that opens offline, the data that comes back with the connection, the update
// bar that waits for a dirty form, and the install offer (a synthetic `beforeinstallprompt` and Safari on iPhone).
// The worker's own script is served with one more comment line to simulate «a new version was published».
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api } from '../support/api';
import { loginAs } from '../support/login';

const t = messages('en');

const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1';

/** Waits until a service worker is active for the page's origin; returns its scope. */
async function activeScope(page: Page): Promise<string> {
  return page.evaluate(async () => (await navigator.serviceWorker.ready).scope);
}

/** Every request URL (path) kept in every browser cache, by cache name. */
async function cachedPaths(page: Page): Promise<Record<string, string[]>> {
  return page.evaluate(async () => {
    const result: Record<string, string[]> = {};
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      result[name] = (await cache.keys()).map((request) => new URL(request.url).pathname);
    }
    return result;
  });
}

/** The width and height of a PNG from its IHDR header. */
function pngSize(bytes: Buffer): { width: number; height: number } {
  expect(bytes.subarray(1, 4).toString('latin1')).toBe('PNG');
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

const STATIC_ALLOWED =
  /^\/(index\.html|manifest\.webmanifest|favicon\.svg|apple-touch-icon\.png|icon-[a-z0-9-]+\.png|assets\/[^?]+|help\/.+)$/;

test.describe('the installable app', () => {
  test('the manifest and its icons are served and match what the browser needs for installing', async ({
    page,
    request,
  }) => {
    await page.goto('/');
    await expect(page.locator('link[rel="manifest"]')).toHaveCount(1);
    await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute(
      'href',
      '/apple-touch-icon.png',
    );

    const response = await request.get('/manifest.webmanifest');
    expect(response.status()).toBe(200);
    const manifest = (await response.json()) as {
      name: string;
      short_name: string;
      lang: string;
      id: string;
      start_url: string;
      scope: string;
      display: string;
      background_color: string;
      theme_color: string;
      icons: { src: string; sizes: string; type: string; purpose?: string }[];
    };
    expect(manifest).toMatchObject({
      name: 'Наділ',
      short_name: 'Наділ',
      lang: 'uk',
      id: '/',
      start_url: '/',
      scope: '/',
      display: 'standalone',
      background_color: '#F3F5F0',
      theme_color: '#F3F5F0',
    });
    const purposes = manifest.icons.map((icon) => `${icon.sizes}:${icon.purpose ?? 'any'}`).sort();
    expect(purposes).toEqual(['192x192:any', '512x512:any', '512x512:maskable']);

    for (const icon of [
      ...manifest.icons,
      { src: '/apple-touch-icon.png', sizes: '180x180', type: 'image/png' },
    ]) {
      const file = await request.get(icon.src);
      expect(file.status(), icon.src).toBe(200);
      expect(file.headers()['content-type'], icon.src).toContain('image/png');
      const [width, height] = icon.sizes.split('x').map(Number);
      expect(pngSize(await file.body()), icon.src).toEqual({ width, height });
    }
  });

  test('a service worker with scope / becomes active', async ({ page }) => {
    await page.goto('/');
    expect(await activeScope(page)).toBe('http://localhost:5173/');
    const script = await page.evaluate(async () => (await navigator.serviceWorker.ready).active?.scriptURL);
    expect(script).toBe('http://localhost:5173/sw.js');
  });

  test('after a member used the app the browser caches hold only static files', async ({ page }) => {
    await loginAs(page, 'member@demo.local');
    await activeScope(page);
    await page.goto('/account');
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();

    // an invoice PDF through the app's own button, when the demo member has an invoice
    const download = page.getByRole('button', { name: t.invoices.download, exact: true }).first();
    if (await download.isVisible()) {
      const file = page.waitForEvent('download');
      await download.click();
      await (await file).path();
    }
    // the same calls the app makes for personal data, so a cache rule that caught them would show up
    const me = await api(page, 'GET', '/me');
    expect(me.status).toBe(200);
    await api(page, 'GET', '/me/invoices');

    // a navigation after everything above, so the worker has handled pages and files of the session
    await page.reload();
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();

    const caches = await cachedPaths(page);
    const names = Object.keys(caches);
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) expect(name, 'only the Workbox precache exists').toContain('-precache-');
    const paths = Object.values(caches).flat();
    expect(paths.length).toBeGreaterThan(10);
    for (const path of paths) {
      expect(path, path).not.toMatch(/^\/api\//);
      expect(path, path).not.toMatch(/\.pdf$/);
      expect(path, path).not.toMatch(/photo/);
      expect(path === '/' || STATIC_ALLOWED.test(path), `${path} is a static file`).toBe(true);
    }
  });

  test('offline: the app says so and shows no member data; the connection brings the data back', async ({
    page,
    context,
  }) => {
    await loginAs(page, 'member@demo.local');
    await activeScope(page);
    const me = JSON.parse((await api(page, 'GET', '/me')).body) as { fullName: string };
    await page.reload(); // the worker now serves the pages of this tab

    await context.setOffline(true);
    await page.goto('/account');
    await expect(page.getByText(t.pwa.offline).first()).toBeVisible();
    await expect(page.getByText(me.fullName, { exact: false })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toHaveCount(0);

    await context.setOffline(false);
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();
    await expect(page.getByText(t.pwa.offline)).toHaveCount(0);
  });

  test('offline: the banner shows over a screen that is already open, and the help panel still opens', async ({
    page,
    context,
  }) => {
    await loginAs(page, 'member@demo.local');
    await page.goto('/account');
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();
    await context.setOffline(true);
    await expect(page.getByRole('status').filter({ hasText: t.pwa.offline })).toBeVisible();
    await page.getByRole('button', { name: t.help.open }).click();
    await expect(page.getByRole('link', { name: t.help.openFull })).toBeVisible();
    await context.setOffline(false);
    await expect(page.getByRole('status').filter({ hasText: t.pwa.offline })).toHaveCount(0);
  });

  test('the public help opens with no connection, from the precached app', async ({ page, context }) => {
    await page.addInitScript(() => window.localStorage.setItem('nadel.locale', 'en'));
    await page.goto('/help');
    await expect(page.getByRole('heading', { name: t.help.title, level: 1 })).toBeVisible();
    await activeScope(page);
    await page.reload();
    await context.setOffline(true);
    await page.goto('/help');
    await expect(page.getByRole('heading', { name: t.help.title, level: 1 })).toBeVisible();
    const first = page.getByRole('main').getByRole('link').first();
    await expect(first).toBeVisible();
    await first.click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByText(t.state.error)).toHaveCount(0);
    await context.setOffline(false);
  });
});

const swFile = resolve(process.cwd(), 'dist', 'sw.js');

/**
 * Changes the built worker script on disk by one comment line, the way a new deploy changes it (`vite preview` serves
 * the files as they are), then asks the browser to look for a new version. Returns the function that restores the file.
 */
async function publishNewVersion(page: Page): Promise<() => void> {
  const original = readFileSync(swFile, 'utf8');
  writeFileSync(swFile, `${original}\n// new version ${Date.now()}\n`);
  await page.evaluate(async () => {
    const registration = await navigator.serviceWorker.ready;
    await registration.update();
  });
  return () => writeFileSync(swFile, original);
}

test.describe('the update bar', () => {
  test('waits for a dirty form, never reloads by itself, and updates once on «Update»', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await activeScope(page);
    await page.goto('/settings');
    const own = page.getByRole('radio', { name: t.settings.visibility.OWN_ONLY.label });
    const all = page.getByRole('radio', { name: t.settings.visibility.ALL_PLOTS.label });
    await expect(own).toBeVisible();
    const wasOwn = await own.isChecked();
    const other = wasOwn ? all : own;
    const original = wasOwn ? own : all;

    await page.evaluate(() => {
      (window as unknown as { __kept: number }).__kept = 1;
    });
    await other.check(); // a form with unsaved input

    const restore = await publishNewVersion(page);
    try {
      const bar = page.getByRole('status').filter({ hasText: t.pwa.updateAvailable });
      await expect(bar).toBeVisible({ timeout: 30_000 });
      const update = bar.getByRole('button', { name: t.pwa.update, exact: true });
      await expect(update).toBeDisabled();
      await expect(bar.getByText(t.pwa.updateBlockedDirty)).toBeVisible();

      // not reloaded by itself, and what was typed is still there
      await page.waitForTimeout(1000);
      expect(await page.evaluate(() => (window as unknown as { __kept?: number }).__kept)).toBe(1);
      await expect(other).toBeChecked();

      await original.check(); // back to the saved value: the form is clean
      await expect(update).toBeEnabled();
      const reloaded = page.waitForEvent('load');
      await update.click();
      await reloaded;
      await expect(
        page.getByRole('banner').getByRole('button', { name: t.auth.signOut, exact: true }),
      ).toBeVisible();
      expect(await page.evaluate(() => (window as unknown as { __kept?: number }).__kept)).toBeUndefined();
      await expect(page.getByText(t.pwa.updateAvailable)).toHaveCount(0);
    } finally {
      restore();
    }
  });
});

test.describe('the install offer', () => {
  test('Chrome: the install button calls the browser prompt; «Do not show again» sticks on this device', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');
    await page.goto('/account');
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: t.pwa.install, exact: true })).toHaveCount(0);

    // the browser's event, as Chrome fires it; the app has been listening since its module loaded
    await page.evaluate(() => {
      const event = new Event('beforeinstallprompt', { cancelable: true });
      Object.assign(event, {
        prompt: () => {
          (window as unknown as { __prompted: boolean }).__prompted = true;
          return Promise.resolve();
        },
        userChoice: Promise.resolve({ outcome: 'accepted' }),
      });
      window.dispatchEvent(event);
    });
    const install = page.getByRole('button', { name: t.pwa.install, exact: true });
    await expect(install).toBeVisible();
    await install.click();
    expect(await page.evaluate(() => (window as unknown as { __prompted?: boolean }).__prompted)).toBe(true);

    await page.evaluate(() => {
      const event = new Event('beforeinstallprompt', { cancelable: true });
      Object.assign(event, {
        prompt: () => Promise.resolve(),
        userChoice: Promise.resolve({ outcome: 'dismissed' }),
      });
      window.dispatchEvent(event);
    });
    await page.getByRole('button', { name: t.pwa.dismiss, exact: true }).click();
    await expect(page.getByRole('button', { name: t.pwa.install, exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => window.localStorage.getItem('nadel.install.v1'))).not.toBeNull();

    await page.reload();
    await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();
    await page.evaluate(() => {
      const event = new Event('beforeinstallprompt', { cancelable: true });
      Object.assign(event, {
        prompt: () => Promise.resolve(),
        userChoice: Promise.resolve({ outcome: 'dismissed' }),
      });
      window.dispatchEvent(event);
    });
    await expect(page.getByRole('button', { name: t.pwa.install, exact: true })).toHaveCount(0);
  });

  test('Safari on iPhone: the card shows the steps; Chrome on iPhone shows nothing', async ({ browser }) => {
    const safari = await browser.newContext({
      userAgent: SAFARI_IPHONE,
      baseURL: 'http://localhost:5173',
      locale: 'en-US',
    });
    try {
      const page = await safari.newPage();
      await loginAs(page, 'member@demo.local');
      await page.goto('/account');
      await expect(page.getByText(t.pwa.iosSteps)).toBeVisible();
      await page.getByRole('button', { name: t.pwa.dismiss, exact: true }).click();
      await expect(page.getByText(t.pwa.iosSteps)).toHaveCount(0);
    } finally {
      await safari.close();
    }
    const chrome = await browser.newContext({
      userAgent: SAFARI_IPHONE.replace('Version/17.4', 'CriOS/124.0'),
      baseURL: 'http://localhost:5173',
      locale: 'en-US',
    });
    try {
      const page = await chrome.newPage();
      await loginAs(page, 'member@demo.local');
      await page.goto('/account');
      await expect(page.getByRole('heading', { name: t.account.title, level: 1 })).toBeVisible();
      await expect(page.getByText(t.pwa.iosSteps)).toHaveCount(0);
    } finally {
      await chrome.close();
    }
  });
});
