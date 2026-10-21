// @layer: e2e
// @spec: 005-ui-preferences
// @regression
//
// Acceptance specs of functional-spec.md of "UI Preferences" (005) that complete ui-preferences.spec.ts: the full
// keyboard path through the theme buttons (§2.1 #6), a long cooperative name (§2.2 #3), a person without a
// cooperative (§2.2 #4, §2.1 full-page messages) and the English descriptions of base roles (§2.3 #1). Titles carry
// the criteria as "§2.x #n". None of these specs changes stored data.
import { expect, test, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const DARK_BACKGROUND = 'rgb(15, 21, 18)'; // palette.dark.background #0F1512
const LONG_NAME =
  'Garden Cooperative of the Riverside Meadows and Orchards Named After the Founders of the Settlement';

const themeGroup = (page: Page) => page.getByRole('group', { name: 'Theme' });

async function outlineOf(page: Page, name: string) {
  return themeGroup(page)
    .getByRole('button', { name, exact: true })
    .evaluate((el) => {
      const style = getComputedStyle(el);
      return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) };
    });
}

test.describe('§2.1 #6 keyboard path through the theme buttons', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('Tab moves System, Light, Dark in order, each focused with a visible outline and a pressed state a screen reader announces; Enter selects', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const group = themeGroup(page);
    // the selected button is exposed as pressed (aria-pressed), the others as not pressed
    await expect(group.getByRole('button', { name: 'System', pressed: true })).toBeVisible();
    await expect(group.getByRole('button', { pressed: false })).toHaveCount(2);

    await group.getByRole('button', { name: 'System', exact: true }).focus();
    for (const [index, name] of ['System', 'Light', 'Dark'].entries()) {
      if (index > 0) await page.keyboard.press('Tab');
      await expect(group.getByRole('button', { name, exact: true })).toBeFocused();
      const outline = await outlineOf(page, name);
      expect(outline.style, `${name} outline style`).not.toBe('none');
      expect(outline.width, `${name} outline width`).toBeGreaterThan(0);
    }

    // Dark has focus: the keyboard alone selects it, and the announced state follows
    await page.keyboard.press('Enter');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(group.getByRole('button', { name: 'Dark', pressed: true })).toBeFocused();
    await expect(group.getByRole('button', { pressed: true })).toHaveCount(1);
    await expect(group.getByRole('button', { name: 'System', pressed: false })).toBeVisible();
  });
});

test.describe('§2.2 #3 a long cooperative name', () => {
  async function withLongName(page: Page) {
    // the same /me answer as the server gives, with a very long cooperative name
    await page.route('**/api/v1/me', async (route) => {
      const response = await route.fetch();
      const me = await response.json();
      me.tenant = { ...me.tenant, name: LONG_NAME };
      await route.fulfill({ response, json: me });
    });
    await loginAs(page, 'chairman@demo.local');
  }

  test('on a desktop the name ends with an ellipsis and the full name is offered on pointing, the tab keeps the full name', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 800, height: 800 });
    await withLongName(page);
    const title = page.getByRole('banner').getByRole('link', { name: LONG_NAME, exact: true });
    await expect(title).toBeVisible();
    await expect(title).toHaveAttribute('title', LONG_NAME);
    const metrics = await title.evaluate((el) => {
      const style = getComputedStyle(el);
      return {
        overflow: style.overflow,
        textOverflow: style.textOverflow,
        whiteSpace: style.whiteSpace,
        clipped: el.scrollWidth > el.clientWidth,
      };
    });
    expect(metrics.textOverflow).toBe('ellipsis');
    expect(metrics.whiteSpace).toBe('nowrap');
    expect(metrics.overflow).toBe('hidden');
    expect(metrics.clipped).toBe(true);
    // the page does not grow sideways because of the name
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(page).toHaveTitle(new RegExp(`— ${LONG_NAME}$`));
  });

  test('on a phone the shortened name still shows next to the Menu button', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await withLongName(page);
    const title = page.getByRole('banner').getByRole('link', { name: LONG_NAME, exact: true });
    const menuButton = page.getByRole('button', { name: 'Menu', exact: true });
    await expect(menuButton).toBeVisible();
    const [titleBox, menuBox] = await Promise.all([title.boundingBox(), menuButton.boundingBox()]);
    expect(titleBox!.width).toBeGreaterThanOrEqual(80);
    expect(titleBox!.x + titleBox!.width).toBeLessThanOrEqual(390);
    const titleMiddle = titleBox!.y + titleBox!.height / 2;
    expect(titleMiddle).toBeGreaterThan(menuBox!.y);
    expect(titleMiddle).toBeLessThan(menuBox!.y + menuBox!.height);
    expect(await title.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});

test.describe('§2.2 #4 a person who belongs to no cooperative', () => {
  test('the platform operator sees the product name as the page title, in the chosen theme', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      try {
        window.localStorage.setItem('nadel.theme', 'dark');
      } catch {
        // storage unavailable: the app falls back to System
      }
    });
    await page.emulateMedia({ colorScheme: 'light' });
    await loginAs(page, 'operator@nadel.local', { skipNavigationWait: true });

    await expect(page.getByRole('heading', { name: 'No cooperative' })).toBeVisible();
    await expect(page).toHaveTitle('No cooperative — Nadel');
    expect(await page.title()).not.toContain('СТ «Надія»');
    // the full-page message uses the chosen theme (§2.1), not the device's light setting
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(DARK_BACKGROUND);
  });
});

test.describe('§2.3 #1 English descriptions of base roles', () => {
  test('the Roles page shows the three standard descriptions in English', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await loginAs(page, 'chairman@demo.local');
    await page.locator('#app-menu nav').getByRole('link', { name: 'Roles', exact: true }).click();
    const items = page.getByRole('main').getByRole('listitem');
    await expect(items.filter({ hasText: /^Chairman/ })).toContainText('Full access');
    await expect(items.filter({ hasText: /^Treasurer/ })).toContainText(
      'Read access to members, plots and audit',
    );
    await expect(items.filter({ hasText: /^Member/ })).toContainText('Own data only');
    // none of the Ukrainian catalog texts leaks into the English page
    await expect(page.getByRole('main')).not.toContainText('Повний доступ');
    await expect(page.getByRole('main')).not.toContainText('Лише власні дані');
  });
});
