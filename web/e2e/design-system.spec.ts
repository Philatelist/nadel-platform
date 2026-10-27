// @layer: e2e
// @spec: 003-ui-design-system
// @regression
//
// Acceptance checks of functional-spec.md sections 2.1 to 2.4, 2.6 and 2.8 in a real browser:
// self-hosted fonts, serif/sans families with Cyrillic coverage, palette and dark theme, 44 px targets,
// header contents, the menu toggle, keyboard focus and the plain member's frame.
import { expect, test, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const LIGHT_BACKGROUND = 'rgb(243, 245, 240)'; // #F3F5F0
const LIGHT_ACCENT = 'rgb(36, 90, 65)'; // #245A41
const DARK_BACKGROUND = 'rgb(15, 21, 18)'; // #0F1512
const DARK_ACCENT = 'rgb(127, 203, 156)'; // #7FCB9C

const USE_UKRAINIAN = () => {
  try {
    window.localStorage.setItem('nadel.locale', 'uk');
  } catch {
    // storage unavailable: the default locale applies
  }
};

const computed = (page: Page, selector: string, property: string) =>
  page
    .locator(selector)
    .first()
    .evaluate((el, p) => getComputedStyle(el).getPropertyValue(p), property);

test.describe('fonts (2.3)', () => {
  test('no font request leaves localhost, and the page uses IBM Plex serif for h1 and sans for text in Ukrainian', async ({
    page,
  }) => {
    const fontRequests: string[] = [];
    const outsideRequests: string[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (request.resourceType() === 'font') fontRequests.push(request.url());
      if (/(^|\.)(fonts\.googleapis\.com|fonts\.gstatic\.com)$/.test(url.hostname))
        outsideRequests.push(request.url());
      if (request.resourceType() === 'font' && url.hostname !== 'localhost')
        outsideRequests.push(request.url());
    });
    await page.addInitScript(USE_UKRAINIAN);
    await loginAs(page, 'chairman@demo.local', { keepLocale: true });
    await page.goto('/members');
    const h1 = page.getByRole('heading', { level: 1 });
    await expect(h1).toBeVisible();
    await expect(page.getByRole('main').locator('tbody tr').first()).toBeVisible();
    await page.evaluate(() => document.fonts.ready);

    expect(fontRequests.length, 'the app loads its own font files').toBeGreaterThan(0);
    expect(outsideRequests).toEqual([]);

    // Ukrainian UI: the title is Cyrillic, set in the serif family; body text in the sans family
    await expect(h1).toHaveText(/[А-Яа-яІіЇїЄє]/);
    expect(await h1.evaluate((el) => getComputedStyle(el).fontFamily)).toContain('IBM Plex Serif');
    expect(await computed(page, 'body', 'font-family')).toContain('IBM Plex Sans');
    expect(
      await page
        .locator('main thead th')
        .first()
        .evaluate((el) => getComputedStyle(el).fontFamily),
    ).toContain('IBM Plex Sans');

    // the Cyrillic subset of the serif and sans families was downloaded from Nadel and is usable for Ukrainian letters
    expect(fontRequests.some((url) => /ibm-plex-serif.*cyrillic-600/.test(url))).toBe(true);
    expect(fontRequests.some((url) => /ibm-plex-sans.*cyrillic-(400|500|600)/.test(url))).toBe(true);
    const faces = await page.evaluate(async () => {
      await document.fonts.ready;
      const loaded = (family: string, weight: string) =>
        [...document.fonts].some(
          (face) =>
            face.family.replace(/["']/g, '') === family && face.weight === weight && face.status === 'loaded',
        );
      return {
        serif: loaded('IBM Plex Serif', '600'),
        sans: loaded('IBM Plex Sans', '400'),
        checkSans: document.fonts.check('16px "IBM Plex Sans"', 'Їжак єдиний'),
      };
    });
    expect(faces).toEqual({ serif: true, sans: true, checkSans: true });
  });
});

test.describe('palette and dark theme (2.1, 2.2)', () => {
  test('light: page ground is light green-grey, "Add member" is filled deep green with white text on a white panel', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    const add = page.getByRole('link', { name: 'Add member' });
    await expect(add).toBeVisible();

    expect(await computed(page, 'body', 'background-color')).toBe(LIGHT_BACKGROUND);
    const style = await add.evaluate((el) => {
      const s = getComputedStyle(el);
      return { background: s.backgroundColor, color: s.color };
    });
    expect(style).toEqual({ background: LIGHT_ACCENT, color: 'rgb(255, 255, 255)' });

    // the table sits on a white panel with a thin border
    const panel = page
      .getByRole('main')
      .locator('table')
      .locator('xpath=ancestor::*[contains(@class,"border")][1]');
    const panelStyle = await panel.evaluate((el) => {
      const s = getComputedStyle(el);
      return { background: s.backgroundColor, border: s.borderTopWidth, shadow: s.boxShadow };
    });
    expect(panelStyle.background).toBe('rgb(255, 255, 255)');
    expect(parseFloat(panelStyle.border)).toBeGreaterThan(0);
    expect(parseFloat(panelStyle.border)).toBeLessThanOrEqual(1);
    expect(panelStyle.shadow).toBe('none');
  });

  test('dark appearance switches the page to the dark palette on the Members screen', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    const add = page.getByRole('link', { name: 'Add member' });
    await expect(add).toBeVisible();

    expect(await computed(page, 'body', 'background-color')).toBe(DARK_BACKGROUND);
    expect(await add.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(DARK_ACCENT);
    // text is light on the dark ground (readability ratios are asserted by packages/ui-tokens contrast tests)
    const ink = await computed(page, 'body', 'color');
    const channels = ink.match(/\d+/g)!.slice(0, 3).map(Number);
    expect(channels.reduce((sum, value) => sum + value, 0) / 3).toBeGreaterThan(180);
  });
});

test.describe('target size (2.4)', () => {
  const heights = (page: Page) =>
    page
      .getByRole('main')
      .locator(
        'button, a[class*="min-h-11"], input:not([type="checkbox"]):not([type="radio"]), select, textarea',
      )
      .evaluateAll((els) =>
        els
          .filter((el) => (el as HTMLElement).offsetParent !== null)
          .map((el) => ({
            label: (el.textContent || el.getAttribute('type') || el.tagName).trim().slice(0, 30),
            height: el.getBoundingClientRect().height,
          })),
      );

  test('buttons and inputs on Members and on the Member form are at least 44 px high', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    await expect(page.getByRole('main').locator('tbody tr').first()).toBeVisible();
    const members = await heights(page);
    expect(members.length).toBeGreaterThanOrEqual(2); // search field and Add member at least
    for (const control of members) expect(control.height, control.label).toBeGreaterThanOrEqual(44);

    await page.goto('/members/new');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    const form = await heights(page);
    expect(form.length).toBeGreaterThanOrEqual(5);
    for (const control of form) expect(control.height, control.label).toBeGreaterThanOrEqual(44);

    // controls have rounded corners (10 px)
    const radius = await page
      .getByRole('main')
      .locator('input:not([type="checkbox"])')
      .first()
      .evaluate((el) => getComputedStyle(el).borderTopLeftRadius);
    expect(radius).toBe('10px');
  });
});

test.describe('target size in the page frame (2.4)', () => {
  test('every visible button in the header, including the language switch, is at least 44 px high', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    await expect(page.getByRole('banner').getByRole('button', { name: 'Sign out' })).toBeVisible();
    const buttons = await page
      .getByRole('banner')
      .getByRole('button')
      .evaluateAll((els) =>
        els
          .filter((el) => (el as HTMLElement).offsetParent !== null)
          .map((el) => ({
            label: (el.textContent ?? '').trim(),
            height: el.getBoundingClientRect().height,
            radius: getComputedStyle(el).borderTopLeftRadius,
          })),
      );
    expect(buttons.length).toBeGreaterThanOrEqual(3);
    for (const button of buttons) {
      expect(button.height, button.label).toBeGreaterThanOrEqual(44);
      expect(button.radius, button.label).toBe('10px');
    }
  });
});

test.describe('page frame (2.6)', () => {
  test('chairman: header shows product link, cooperative, name, role, language switch and Sign out; menu lists the sections', async ({
    page,
  }) => {
    await page.addInitScript(USE_UKRAINIAN);
    await loginAs(page, 'chairman@demo.local', { keepLocale: true });
    await page.setViewportSize({ width: 1280, height: 800 });

    const header = page.getByRole('banner');
    await expect(header.getByRole('link', { name: 'СТ «Надія»', exact: true })).toHaveAttribute('href', '/');
    await expect(header.getByText('Наділ', { exact: true })).toBeVisible();
    await expect(header).toContainText('СТ «Надія»');
    await expect(header).toContainText('Коваленко');
    await expect(header).toContainText('Голова');
    await expect(header.getByRole('button', { name: 'Вийти' })).toBeVisible();
    const language = header.getByRole('group', { name: 'Мова' });
    await expect(language.getByRole('button', { pressed: true })).toHaveText('УКР');
    await expect(language.getByRole('button')).toHaveCount(2);

    const menu = page.locator('#app-menu nav');
    await expect(menu.getByRole('link')).toHaveCount(12); // Plots, Members, Tariffs, Readings, To approve, Meters, Charges, Reports, Import, Roles, Audit log, Cooperative settings
    await page.goto('/members');
    await expect(menu.locator('a[aria-current="page"]')).toHaveCount(1);
    expect(
      await menu.locator('a[aria-current="page"]').evaluate((el) => getComputedStyle(el).backgroundColor),
    ).toBe('rgb(227, 238, 230)'); // accent-soft #E3EEE6
  });

  test.describe('phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('pressing Menu twice opens then closes it, aria-expanded true then false', async ({ page }) => {
      await loginAs(page, 'chairman@demo.local');
      const button = page.getByRole('button', { name: 'Menu', exact: true });
      const menu = page.locator('#app-menu');
      await expect(button).toHaveAttribute('aria-expanded', 'false');
      await expect(menu).toBeHidden();

      await button.click();
      await expect(button).toHaveAttribute('aria-expanded', 'true');
      await expect(menu).toBeVisible();

      await button.click();
      await expect(button).toHaveAttribute('aria-expanded', 'false');
      await expect(menu).toBeHidden();
    });
  });

  test('plain member sees the header and the account page, with a menu holding only Plots @smoke', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');
    await expect(page).toHaveURL(/\/account$/);
    await expect(
      page.getByRole('banner').getByRole('link', { name: 'СТ «Надія»', exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('banner').getByText('Nadel', { exact: true })).toBeVisible();
    await expect(page.getByRole('banner').getByRole('button', { name: 'Sign out' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.locator('#app-menu nav').getByRole('link')).toHaveText(['Plots']);
    // the Menu button is a phone-only control; this is a desktop viewport
    await expect(page.getByRole('button', { name: 'Menu', exact: true })).toBeHidden();
  });
});

test.describe('keyboard (2.8)', () => {
  test('Tab reaches the menu links, the search field and "Add member", each with a visible focus outline', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    await expect(page.getByRole('main').locator('tbody tr').first()).toBeVisible();
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());

    const visited: { name: string; inMenu: boolean; type: string; outline: string; width: number }[] = [];
    for (let i = 0; i < 40; i++) {
      await page.keyboard.press('Tab');
      const info = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement;
        const s = getComputedStyle(el);
        return {
          name: (el.getAttribute('aria-label') || el.textContent || el.getAttribute('type') || '')
            .trim()
            .slice(0, 40),
          inMenu: !!el.closest('#app-menu'),
          type: el.getAttribute('type') ?? el.tagName.toLowerCase(),
          outline: s.outlineStyle,
          width: parseFloat(s.outlineWidth),
        };
      });
      visited.push(info);
      if (visited.some((v) => v.name === 'Add member') && visited.some((v) => v.type === 'search')) break;
    }

    const names = visited.map((v) => v.name);
    expect(names).toContain('Add member');
    expect(visited.filter((v) => v.inMenu).map((v) => v.name)).toEqual([
      'Plots',
      'Members',
      'Tariffs',
      'Readings',
      'To approve',
      'Meters',
      'Charges',
      'Reports',
      'Import',
      'Roles',
      'Audit log',
      'Cooperative settings',
    ]);
    expect(visited.some((v) => v.type === 'search')).toBe(true);
    for (const v of visited) {
      expect(v.outline, `focus outline on "${v.name}"`).not.toBe('none');
      expect(v.width, `focus outline width on "${v.name}"`).toBeGreaterThan(0);
    }
  });
});
