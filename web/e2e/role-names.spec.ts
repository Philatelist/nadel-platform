// @layer: e2e
// @spec: 004-role-names
// @regression
//
// Acceptance specs of functional-spec.md of "Role Names" (004) in the Ukrainian interface, which is where the standard
// name "Казначей", the on-screen-name warning and the audit rendering of standard names matter. The English rename and
// restore flow lives in acceptance.spec.ts. Titles carry the criteria as "§2.x #n". Every spec that renames restores
// the standard name in `finally`.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const TREASURER_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000002';

const USE_UKRAINIAN = () => {
  try {
    window.localStorage.setItem('nadel.locale', 'uk');
  } catch {
    // storage unavailable: the default locale (Ukrainian) applies
  }
};

async function ukrainianPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'uk-UA' });
  const page = await context.newPage();
  await page.addInitScript(USE_UKRAINIAN);
  await loginAs(page, email, { keepLocale: true });
  return page;
}

const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });
const roleItems = (page: Page) => page.getByRole('main').getByRole('listitem');
const nameField = (page: Page) => page.getByLabel('Назва', { exact: true });

/** The audit page is not live: reload until the entry written by the outbox shows up. */
async function expectAuditRow(page: Page, text: string | RegExp) {
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: text }).first()).toBeVisible({ timeout: 1500 });
  }).toPass({ timeout: 15_000 });
}

test.describe('Role names in Ukrainian', () => {
  test('§2.7 #1, §2.3 #1, #3, §2.4 #1, §2.1 #4, §2.2 #2: standard name "Казначей", hint, no restore button, clash warning, empty name refused', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'chairman@demo.local');
    try {
      await menuLink(page, 'Ролі').click();
      const treasurer = roleItems(page).filter({ hasText: /^Казначей/ });
      await expect(treasurer).toBeVisible();
      await expect(roleItems(page).filter({ hasText: /^Скарбник/ })).toHaveCount(0);

      // the hint shows the standard name, a never-renamed role has no restore button
      await treasurer.getByRole('button', { name: 'Редагувати' }).click();
      await expect(nameField(page)).toHaveValue('Казначей');
      await expect(page.getByText('Базова роль: Казначей')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Повернути стандартну назву' })).toHaveCount(0);
      await page.getByRole('button', { name: 'Скасувати' }).click();

      // base roles cannot be deleted; the Chairman keeps every permission locked
      for (const item of await roleItems(page).filter({ hasText: 'Базова роль' }).all()) {
        await expect(item.getByRole('button', { name: 'Видалити' })).toHaveCount(0);
      }
      await roleItems(page)
        .filter({ hasText: /^Голова/ })
        .getByRole('button', { name: 'Редагувати' })
        .click();
      await expect(nameField(page)).toBeEnabled();
      const boxes = page.getByRole('checkbox');
      await expect(boxes).toHaveCount(8);
      for (let i = 0; i < 8; i++) {
        await expect(boxes.nth(i)).toBeChecked();
        await expect(boxes.nth(i)).toBeDisabled();
      }
      await page.getByRole('button', { name: 'Скасувати' }).click();

      // typing the on-screen name of the Treasurer role as the name of another role warns before saving
      await roleItems(page)
        .filter({ hasText: /^Secretary/ })
        .getByRole('button', { name: 'Редагувати' })
        .click();
      const warning = page.getByText('Інша роль уже показується під такою назвою.');
      await expect(warning).toHaveCount(0);
      await nameField(page).fill('Казначей');
      await expect(warning).toBeVisible();
      await nameField(page).fill('казначей ');
      await expect(warning).toBeVisible();
      await nameField(page).fill('Скарбник');
      await expect(warning).toHaveCount(0);
      await page.getByRole('button', { name: 'Скасувати' }).click();

      // an empty name of a base role is refused under the field and nothing changes
      await treasurer.getByRole('button', { name: 'Редагувати' }).click();
      await nameField(page).fill('');
      await page.getByRole('button', { name: 'Зберегти' }).click();
      await expect(page.getByText('Назва обов’язкова.')).toBeVisible();
      await page.getByRole('button', { name: 'Скасувати' }).click();
      await expect(treasurer).toBeVisible();

      // switching to English shows the English standard name
      await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'en' }).click();
      await expect(roleItems(page).filter({ hasText: /^Treasurer/ })).toBeVisible();
    } finally {
      await page.context().close();
    }
  });

  test('§2.5 #1: the treasurer, who may not manage roles, gets "no access" on the Roles page and has no Roles menu item', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'treasurer@demo.local');
    try {
      await expect(menuLink(page, 'Ролі')).toHaveCount(0);
      await page.goto('/roles');
      await expect(page.getByText('У вас немає доступу до цієї сторінки')).toBeVisible();
      await expect(page.getByLabel('Назва', { exact: true })).toHaveCount(0);
    } finally {
      await page.context().close();
    }
  });

  test('§2.1 #1, §2.6 #1-#3, §2.3 #2: renamed to "Бухгалтер" it is shown everywhere and in the audit log, restored to "Казначей"', async ({
    browser,
  }) => {
    const page = await ukrainianPage(browser, 'chairman@demo.local');
    await menuLink(page, 'Ролі').click();
    const treasurer = roleItems(page).filter({ hasText: /^Казначей/ });
    const bookkeeper = roleItems(page).filter({ hasText: /^Бухгалтер/ });
    let treasurerPage: Page | undefined;

    try {
      await treasurer.getByRole('button', { name: 'Редагувати' }).click();
      await nameField(page).fill('Бухгалтер');
      await page.getByRole('button', { name: 'Зберегти' }).click();
      await expect(page.getByRole('status')).toHaveText('Збережено');
      await expect(bookkeeper).toBeVisible();
      await expect(treasurer).toHaveCount(0);

      // a renamed role shows the same name in English
      await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'en' }).click();
      await expect(roleItems(page).filter({ hasText: /^Бухгалтер/ })).toBeVisible();
      await page.getByRole('group', { name: 'Language' }).getByRole('button', { name: 'uk' }).click();

      // members list and the treasurer's member page, as the chairman sees them
      await menuLink(page, 'Члени кооперативу').click();
      await page.getByRole('searchbox').fill('Shevchenko');
      await expect(
        page.getByRole('row').filter({ hasText: 'Shevchenko' }).filter({ hasText: 'Бухгалтер' }),
      ).toBeVisible();
      await page.goto(`/members/${TREASURER_MEMBER_ID}`);
      await expect(page.getByRole('main')).toContainText('Бухгалтер');

      // the treasurer's header and account page
      treasurerPage = await ukrainianPage(browser, 'treasurer@demo.local');
      await expect(treasurerPage.getByRole('banner')).toContainText('Бухгалтер');
      await treasurerPage.goto('/account');
      await expect(treasurerPage.getByRole('listitem').filter({ hasText: /^Бухгалтер$/ })).toBeVisible();
      await expect(treasurerPage.getByRole('main')).not.toContainText('Казначей');

      // restore through the UI
      await page.goto('/roles');
      await bookkeeper.getByRole('button', { name: 'Редагувати' }).click();
      await page.getByRole('button', { name: 'Повернути стандартну назву' }).click();
      await expect(page.getByRole('status')).toHaveText('Збережено');
      await expect(treasurer).toBeVisible();
      await expect(bookkeeper).toHaveCount(0);
    } finally {
      await treasurerPage?.context().close();
      await page.goto('/roles');
      if ((await bookkeeper.count()) > 0) {
        await bookkeeper.getByRole('button', { name: 'Редагувати' }).click();
        await page.getByRole('button', { name: 'Повернути стандартну назву' }).click();
        await expect(page.getByRole('status')).toHaveText('Збережено');
      }
    }

    // audit log: Ukrainian reader sees "Казначей → Бухгалтер" and the restore, an English reader "Treasurer → Бухгалтер"
    try {
      await menuLink(page, 'Журнал змін').click();
      await page.getByLabel('Вид запису').selectOption({ label: 'Роль' });
      await expectAuditRow(page, /Казначей → Бухгалтер/);
      await expectAuditRow(page, /Бухгалтер → Казначей/);
      expect(await page.getByRole('main').innerText()).not.toContain('base:');
      await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'en' }).click();
      await expect(
        page
          .getByRole('row')
          .filter({ hasText: /Treasurer → Бухгалтер/ })
          .first(),
      ).toBeVisible();
      await expect(
        page
          .getByRole('row')
          .filter({ hasText: /Бухгалтер → Treasurer/ })
          .first(),
      ).toBeVisible();
    } finally {
      await page.context().close();
    }
  });
});
