// @layer: e2e
// @spec: 005-ui-preferences
// @regression
//
// Acceptance specs of functional-spec.md of "UI Preferences" (005): the theme switch, the cooperative name as the
// header title and tab title, and base role descriptions in the Ukrainian interface. Titles carry the criteria as
// "§2.x #n". The spec that changes the Treasurer description restores it in `finally`.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const DARK_BACKGROUND = 'rgb(15, 21, 18)'; // palette.dark.background #0F1512
const LIGHT_BACKGROUND = 'rgb(243, 245, 240)'; // palette.light.background #F3F5F0
const PHONE = { width: 390, height: 844 };

const USE_UKRAINIAN = () => {
  try {
    window.localStorage.setItem('nadel.locale', 'uk');
  } catch {
    // storage unavailable: the default locale (Ukrainian) applies
  }
};

const themeGroup = (page: Page) => page.getByRole('group', { name: 'Theme' });
const themeButton = (page: Page, name: 'System' | 'Light' | 'Dark') =>
  themeGroup(page).getByRole('button', { name, exact: true });
const htmlTheme = (page: Page) => page.evaluate(() => document.documentElement.getAttribute('data-theme'));
const bodyBackground = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor);

async function ukrainianPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'uk-UA' });
  const page = await context.newPage();
  await page.addInitScript(USE_UKRAINIAN);
  await loginAs(page, email, { keepLocale: true });
  return page;
}

test.describe('theme switch (desktop)', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('§2.1 #1, #2: choosing Dark turns the app dark, the choice survives a reload and data-theme is set before the body is parsed', async ({
    page,
  }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await loginAs(page, 'chairman@demo.local');
    await expect(themeButton(page, 'System')).toHaveAttribute('aria-pressed', 'true');
    expect(await bodyBackground(page)).toBe(LIGHT_BACKGROUND);

    await themeButton(page, 'Dark').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(themeButton(page, 'Dark')).toHaveAttribute('aria-pressed', 'true');
    await expect(themeButton(page, 'System')).toHaveAttribute('aria-pressed', 'false');
    await expect.poll(() => bodyBackground(page)).toBe(DARK_BACKGROUND);

    // record data-theme at the moment the parser creates <body>, i.e. before any module script (the app) runs
    await page.addInitScript(() => {
      const observer = new MutationObserver(() => {
        if (document.body) {
          (window as unknown as { __themeAtBody: string | null }).__themeAtBody =
            document.documentElement.getAttribute('data-theme');
          observer.disconnect();
        }
      });
      observer.observe(document, { childList: true, subtree: true });
    });
    await page.reload();
    expect(
      await page.evaluate(() => (window as unknown as { __themeAtBody?: string | null }).__themeAtBody),
    ).toBe('dark');
    await expect(themeButton(page, 'Dark')).toHaveAttribute('aria-pressed', 'true');
    expect(await bodyBackground(page)).toBe(DARK_BACKGROUND);
  });

  test('§2.1 #3: System follows the device again, Light stays light on a dark device', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await loginAs(page, 'chairman@demo.local');
    await expect.poll(() => bodyBackground(page)).toBe(DARK_BACKGROUND);
    expect(await htmlTheme(page)).toBeNull();

    await themeButton(page, 'Light').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await expect.poll(() => bodyBackground(page)).toBe(LIGHT_BACKGROUND);

    await themeButton(page, 'System').click();
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.*/);
    await expect(themeButton(page, 'System')).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => bodyBackground(page)).toBe(DARK_BACKGROUND);

    await page.emulateMedia({ colorScheme: 'light' });
    await expect.poll(() => bodyBackground(page)).toBe(LIGHT_BACKGROUND);
  });

  test('§2.1 #4: with storage blocked the app works without errors and starts in System', async ({
    page,
  }) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.emulateMedia({ colorScheme: 'light' });
    await loginAs(page, 'chairman@demo.local');

    // From now on the app's own preference keys cannot be read or written, like a browser that blocks site data
    // for the app. (The sign-in library keeps its own state in localStorage, so the whole object is not blocked.)
    await page.addInitScript(() => {
      const blocked = (key: string) => key.startsWith('nadel.');
      const getItem = Storage.prototype.getItem;
      const setItem = Storage.prototype.setItem;
      Storage.prototype.getItem = function (key: string) {
        if (this === window.localStorage && blocked(key)) throw new DOMException('blocked', 'SecurityError');
        return getItem.call(this, key);
      };
      Storage.prototype.setItem = function (key: string, value: string) {
        if (this === window.localStorage && blocked(key)) throw new DOMException('blocked', 'SecurityError');
        setItem.call(this, key, value);
      };
    });
    await page.reload();
    await expect(themeButton(page, 'System')).toHaveAttribute('aria-pressed', 'true');
    expect(await htmlTheme(page)).toBeNull();

    await themeButton(page, 'Dark').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.reload();
    await expect(themeButton(page, 'System')).toHaveAttribute('aria-pressed', 'true');
    expect(await htmlTheme(page)).toBeNull();
    expect(errors).toEqual([]);
  });

  test('§2.1 #6: every theme button is reachable with Tab and shows a focus outline', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    const dark = themeButton(page, 'Dark');
    await expect(dark).toHaveAttribute('aria-pressed', 'false');
    await dark.focus();
    await page.keyboard.press('Shift+Tab');
    await expect(themeButton(page, 'Light')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(themeButton(page, 'System')).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(dark).toBeFocused();
    const outline = await dark.evaluate((el) => {
      const style = getComputedStyle(el);
      return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) };
    });
    expect(outline.style).not.toBe('none');
    expect(outline.width).toBeGreaterThan(0);
  });
});

test.describe('theme switch (phone)', () => {
  test.use({ viewport: PHONE });

  test('§2.1 #5: the chairman finds the theme choice inside the opened menu, not in the header row', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const menuButton = page.getByRole('button', { name: 'Menu', exact: true });
    await expect(menuButton).toBeVisible();
    await expect(themeGroup(page)).toBeHidden();

    await menuButton.click();
    await expect(page.locator('#app-menu').getByRole('group', { name: 'Theme' })).toBeVisible();
    await expect(page.getByRole('banner').getByRole('group', { name: 'Theme' })).toBeHidden();
    await themeButton(page, 'Dark').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  });

  test('§2.2 #3: on a phone the cooperative name stays readable on the same row as the Menu button', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const title = page.getByRole('banner').getByRole('link', { name: 'СТ «Надія»', exact: true });
    const menuButton = page.getByRole('button', { name: 'Menu', exact: true });
    const titleBox = await title.boundingBox();
    const menuBox = await menuButton.boundingBox();
    expect(titleBox).not.toBeNull();
    expect(menuBox).not.toBeNull();
    expect(titleBox!.width).toBeGreaterThanOrEqual(80);
    const titleMiddle = titleBox!.y + titleBox!.height / 2;
    expect(titleMiddle).toBeGreaterThan(menuBox!.y);
    expect(titleMiddle).toBeLessThan(menuBox!.y + menuBox!.height);
  });

  test('§2.1 #5: a plain member also finds the theme choice inside the opened menu', async ({ page }) => {
    await loginAs(page, 'member@demo.local');
    await page.getByRole('button', { name: 'Menu', exact: true }).click();
    await expect(page.locator('#app-menu').getByRole('group', { name: 'Theme' })).toBeVisible();
    await themeButton(page, 'Dark').click();
    await expect(themeButton(page, 'Dark')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  });
});

test.describe('cooperative name as the title (Ukrainian UI)', () => {
  test('§2.2 #1, #2: header title links home with "Наділ" as a label, the Members tab reads "<title> — <cooperative>"', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'chairman@demo.local');
    try {
      await page.setViewportSize({ width: 1280, height: 800 });
      const header = page.getByRole('banner');
      const title = header.getByRole('link', { name: 'СТ «Надія»', exact: true });
      await expect(title).toHaveAttribute('href', '/');
      await expect(header.getByText('Наділ', { exact: true })).toBeVisible();
      const [titleSize, labelSize] = await Promise.all([
        title.evaluate((el) => parseFloat(getComputedStyle(el).fontSize)),
        header
          .getByText('Наділ', { exact: true })
          .evaluate((el) => parseFloat(getComputedStyle(el).fontSize)),
      ]);
      expect(labelSize).toBeLessThan(titleSize);

      // desktop order: the title first, the product label next to it, the controls on the right
      const [titleBox, labelBox, controlsBox] = await Promise.all([
        title.boundingBox(),
        header.getByText('Наділ', { exact: true }).boundingBox(),
        header.getByRole('group', { name: 'Тема' }).boundingBox(),
      ]);
      expect(titleBox).not.toBeNull();
      expect(labelBox).not.toBeNull();
      expect(controlsBox).not.toBeNull();
      expect(titleBox!.x).toBeLessThan(labelBox!.x);
      expect(titleBox!.x).toBeLessThan(controlsBox!.x);

      await page
        .locator('#app-menu nav')
        .getByRole('link', { name: 'Члени кооперативу', exact: true })
        .click();
      await expect(page).toHaveTitle('Члени кооперативу — СТ «Надія»');
    } finally {
      await page.context().close();
    }
  });
});

test.describe('base role descriptions (Ukrainian UI)', () => {
  const menuLink = (page: Page, name: string) =>
    page.locator('#app-menu nav').getByRole('link', { name, exact: true });
  const roleItems = (page: Page) => page.getByRole('main').getByRole('listitem');
  const STANDARD = 'Перегляд членів, ділянок і журналу змін';

  // Opens the Treasurer form and restores the standard description if any custom text is stored.
  async function restoreTreasurerDescription(page: Page) {
    await page.goto('/roles');
    const treasurer = roleItems(page).filter({ hasText: /^Казначей/ });
    await expect(treasurer).toBeVisible();
    await treasurer.getByRole('button', { name: 'Редагувати' }).click();
    await expect(page.getByRole('button', { name: 'Зберегти' })).toBeVisible();
    const restore = page.getByRole('button', { name: 'Повернути стандартний опис' });
    if (await restore.isVisible()) {
      await restore.click();
      await expect(page.getByRole('status')).toHaveText('Збережено');
    } else {
      await page.getByRole('button', { name: 'Скасувати' }).click();
    }
  }

  async function expectAuditRow(page: Page, text: string | RegExp) {
    await expect(async () => {
      await page.reload();
      await expect(page.getByRole('row').filter({ hasText: text }).first()).toBeVisible({ timeout: 1500 });
    }).toPass({ timeout: 15_000 });
  }

  test('§2.3 #1-#3, #5: standard descriptions, a changed Treasurer description with restore, and the audit entry', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'chairman@demo.local');
    await page.setViewportSize({ width: 1280, height: 800 });
    await menuLink(page, 'Ролі').click();
    const treasurer = roleItems(page).filter({ hasText: /^Казначей/ });
    const restore = page.getByRole('button', { name: 'Повернути стандартний опис' });

    try {
      try {
        await expect(roleItems(page).filter({ hasText: /^Голова/ })).toContainText('Повний доступ');
        await expect(treasurer).toContainText(STANDARD);
        await expect(roleItems(page).filter({ hasText: /^Член кооперативу/ })).toContainText(
          'Лише власні дані',
        );

        await treasurer.getByRole('button', { name: 'Редагувати' }).click();
        await expect(restore).toHaveCount(0);
        await page.getByLabel('Опис', { exact: true }).fill('Веде касу');
        await page.getByRole('button', { name: 'Зберегти' }).click();
        await expect(page.getByRole('status')).toHaveText('Збережено');
        await expect(treasurer).toContainText('Веде касу');
        await expect(treasurer).not.toContainText(STANDARD);

        // the changed text is shown in English too
        await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'English' }).click();
        await expect(roleItems(page).filter({ hasText: /^Treasurer/ })).toContainText('Веде касу');
        await page
          .getByRole('group', { name: 'Language' })
          .getByRole('button', { name: 'Українська' })
          .click();

        await treasurer.getByRole('button', { name: 'Редагувати' }).click();
        await expect(restore).toBeVisible();
        await restore.click();
        await expect(page.getByRole('status')).toHaveText('Збережено');
        await expect(treasurer).toContainText(STANDARD);
        await expect(treasurer).not.toContainText('Веде касу');
        await treasurer.getByRole('button', { name: 'Редагувати' }).click();
        await expect(restore).toHaveCount(0);
        await page.getByRole('button', { name: 'Скасувати' }).click();
      } finally {
        await restoreTreasurerDescription(page);
      }

      await menuLink(page, 'Журнал змін').click();
      await page.getByLabel('Вид запису').selectOption({ label: 'Роль' });
      await expectAuditRow(page, `${STANDARD} → Веде касу`);
    } finally {
      await page.context().close();
    }
  });

  test('§2.3 #4: saving only the permissions of the Treasurer keeps the description following the language', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'chairman@demo.local');
    await page.setViewportSize({ width: 1280, height: 800 });
    try {
      await menuLink(page, 'Ролі').click();
      const treasurer = roleItems(page).filter({ hasText: /^Казначей/ });
      await treasurer.getByRole('button', { name: 'Редагувати' }).click();
      // save without touching anything: the standard description must not become a custom one
      await page.getByRole('button', { name: 'Зберегти' }).click();
      await expect(page.getByRole('status')).toHaveText('Збережено');
      await expect(treasurer).toContainText(STANDARD);
      await treasurer.getByRole('button', { name: 'Редагувати' }).click();
      await expect(page.getByRole('button', { name: 'Повернути стандартний опис' })).toHaveCount(0);
      await page.getByRole('button', { name: 'Скасувати' }).click();
      await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'English' }).click();
      await expect(roleItems(page).filter({ hasText: /^Treasurer/ })).toContainText(
        'Read access to members, plots and audit',
      );
    } finally {
      try {
        await restoreTreasurerDescription(page);
      } finally {
        await page.context().close();
      }
    }
  });
});
