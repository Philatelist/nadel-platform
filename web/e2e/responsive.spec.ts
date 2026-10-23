// @layer: e2e
// @spec: 003-ui-design-system
// @regression
//
// Page frame and menu (functional-spec.md 2.6): the side menu collapses behind a Menu button on a phone-sized
// viewport, the members table scrolls inside its panel and the page itself never scrolls sideways.
import { expect, test } from '@playwright/test';
import { kyivDay } from './support/api';
import { loginAs } from './support/login';

const PHONE = { width: 390, height: 844 };

test.describe('responsive page frame', () => {
  test.describe('phone viewport', () => {
    test.use({ viewport: PHONE });

    test('chairman: the menu toggles with aria-expanded, closes on navigation and on Escape', async ({
      page,
    }) => {
      await loginAs(page, 'chairman@demo.local');

      const menuButton = page.getByRole('button', { name: 'Menu', exact: true });
      const membersLink = page.getByRole('link', { name: 'Members', exact: true });

      await expect(menuButton).toBeVisible();
      await expect(menuButton).toHaveAttribute('aria-expanded', 'false');
      await expect(membersLink).toBeHidden();

      await menuButton.click();
      await expect(menuButton).toHaveAttribute('aria-expanded', 'true');
      await expect(membersLink).toBeVisible();
      await expect(page.getByRole('link', { name: 'Roles', exact: true })).toBeVisible();

      await membersLink.click();
      await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible();
      await expect(menuButton).toHaveAttribute('aria-expanded', 'false');
      await expect(membersLink).toBeHidden();

      // the page does not scroll sideways; the table scrolls inside its own wrapper
      await expect(page.getByRole('main').locator('tbody tr').first()).toBeVisible();
      const pageWidth = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));
      expect(pageWidth.scroll).toBeLessThanOrEqual(pageWidth.client);
      const wrapper = page.getByRole('main').locator('table').locator('xpath=..');
      const sizes = await wrapper.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
      expect(sizes.scroll).toBeGreaterThan(sizes.client);

      // Escape closes the open menu and returns focus to the Menu button
      await menuButton.click();
      await expect(menuButton).toHaveAttribute('aria-expanded', 'true');
      await page.keyboard.press('Escape');
      await expect(menuButton).toHaveAttribute('aria-expanded', 'false');
      await expect(menuButton).toBeFocused();
    });

    // feature 011: the charges screens, the plot account and the settings sections fit a phone
    test('chairman: the charges screens, a plot account and the settings never scroll sideways', async ({
      page,
    }) => {
      await loginAs(page, 'chairman@demo.local');
      const noSidewaysScroll = async (where: string) => {
        await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
        await page.waitForLoadState('networkidle');
        const width = await page.evaluate(() => ({
          scroll: document.documentElement.scrollWidth,
          client: document.documentElement.clientWidth,
        }));
        expect(width.scroll, `${where} scrolls sideways`).toBeLessThanOrEqual(width.client);
      };
      for (const path of [
        '/charges',
        `/charges/months/${kyivDay(0).slice(0, 7)}`,
        '/charges/not-charged',
        '/charges/losses',
        '/charges/common',
        '/settings',
      ]) {
        await page.goto(path);
        await noSidewaysScroll(path);
      }

      // a plot card with its account: the charge rows wrap instead of overflowing
      await page.goto('/plots');
      const plotLink = page.getByRole('main').locator('tbody tr a[href^="/plots/"]').first();
      await plotLink.click();
      await expect(page.getByRole('heading', { name: 'Account', exact: true })).toBeVisible();
      await expect(
        page.getByRole('list', { name: 'Plot charges' }).getByRole('listitem').first(),
      ).toBeVisible();
      await noSidewaysScroll('the plot account');

      // the dialog fits too: a charge's calculation
      await page.getByRole('button', { name: 'Details', exact: true }).first().click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('Calculation')).toBeVisible();
      const box = await dialog.boundingBox();
      expect(box?.width ?? 0).toBeLessThanOrEqual(PHONE.width);
      await noSidewaysScroll('the charge details dialog');
    });

    test('plain member: the Menu button opens a menu with only Plots', async ({ page }) => {
      await loginAs(page, 'member@demo.local');

      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const menuButton = page.getByRole('button', { name: 'Menu', exact: true });
      await expect(menuButton).toBeVisible();
      await menuButton.click();
      const nav = page.getByRole('navigation', { name: 'Main menu' });
      await expect(nav.getByRole('link')).toHaveText(['Plots']);
    });
  });

  test('desktop: the current menu item is marked aria-current and there is no Menu button', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await loginAs(page, 'chairman@demo.local');

    await expect(page.getByRole('button', { name: 'Menu', exact: true })).toBeHidden();
    const menu = page.locator('#app-menu nav');
    await menu.getByRole('link', { name: 'Members', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible();
    await expect(menu.getByRole('link', { name: 'Members', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await expect(menu.getByRole('link', { name: 'Roles', exact: true })).not.toHaveAttribute(
      'aria-current',
      'page',
    );
  });
});
