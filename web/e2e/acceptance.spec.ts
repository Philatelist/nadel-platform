// @layer: e2e
// @spec: 001-members-access
// @regression
//
// Acceptance specs of the whole feature "Members and Access Control" (functional-spec.md §2), driven through the
// browser against the local stack with the demo data. The titles carry the criteria they cover as "§2.x #n".
// Specs that change data restore it (or use uniquely named records that end archived or deleted).
import { expect, test, type Browser, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const CHAIRMAN_ID = 'aaaaaaaa-0000-0000-0000-000000000001';
const ELECTRICIAN_ID = 'aaaaaaaa-0000-0000-0000-000000000004';
const PAVLENKO_ID = 'aaaaaaaa-0000-0000-0000-000000000014'; // has a note in the demo data
const ALL_PERMISSIONS = [
  'View members',
  'Edit members',
  'Manage roles',
  'View audit log',
  'View plots',
  'Edit plots',
  'Edit ownership',
  'Run import',
];

const nav = (page: Page) => page.locator('header nav');
const navLink = (page: Page, name: string) => nav(page).getByRole('link', { name, exact: true });

async function expectNavigation(page: Page, present: string[], absent: string[]) {
  for (const name of present) await expect(navLink(page, name)).toBeVisible();
  for (const name of [...absent, 'Map']) await expect(navLink(page, name)).toHaveCount(0);
}

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

/** The audit page is not live: reload until the entry written by the outbox shows up (spec: within seconds). */
async function expectAuditRow(page: Page, text: string | RegExp) {
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: text }).first()).toBeVisible({ timeout: 1500 });
  }).toPass({ timeout: 15_000 });
}

test.describe('§2.10 / §2.11 landing page and navigation', () => {
  test('§2.10 #1, #2 and §2.11 #2: the treasurer lands on My account and has no Roles or Map item', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');

    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByRole('heading', { level: 1, name: 'My account' })).toBeVisible();
    await expect(page.getByText('Shevchenko Iryna Volodymyrivna')).toBeVisible();
    await expect(page.getByRole('listitem').filter({ hasText: /^Treasurer$/ })).toBeVisible();
    for (const permission of ['View members', 'View plots', 'View audit log']) {
      await expect(page.getByText(permission, { exact: true })).toBeVisible();
    }
    for (const permission of ['Edit members', 'Manage roles']) {
      await expect(page.getByText(permission, { exact: true })).toHaveCount(0);
    }
    await expectNavigation(page, ['My account', 'Members', 'Audit log'], ['Roles']);
  });

  test('§2.11 #1: the chairman sees My account, Members, Roles and Audit log', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');

    await expect(page).toHaveURL(/\/account$/);
    await expectNavigation(page, ['My account', 'Members', 'Roles', 'Audit log'], []);
    for (const permission of ALL_PERMISSIONS) {
      await expect(page.getByText(permission, { exact: true })).toBeVisible();
    }
  });
});

test.describe('§2.5 what an ordinary member sees', () => {
  test('§2.5 #1-#4, §2.1 #2, §2.9 #7: only Member means own read-only card and no admin screens', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');

    // own card: name, phones, email, postal address, channel; no edit buttons, no notes
    await expect(page.getByText('Bondarenko Andrii Mykolaiovych')).toBeVisible();
    await expect(page.getByText('+380501110003')).toBeVisible();
    await expect(page.getByText('member@demo.local')).toBeVisible();
    await expect(page.getByText('Postal address', { exact: true })).toBeVisible();
    await expect(page.getByText('Preferred channel', { exact: true })).toBeVisible();
    await expect(page.getByText('Notes', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /edit|archive|restore/i })).toHaveCount(0);
    await expectNavigation(page, ['My account'], ['Members', 'Roles', 'Audit log']);

    // someone else's card looks exactly like a card that does not exist
    await page.goto(`/members/${CHAIRMAN_ID}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Not found' })).toBeVisible();
    await expect(page.getByText('Kovalenko')).toHaveCount(0);
    await page.goto('/members/00000000-0000-0000-0000-00000000dead');
    await expect(page.getByRole('heading', { level: 1, name: 'Not found' })).toBeVisible();

    // the admin screens are closed by address
    for (const path of ['/members', '/roles', '/audit']) {
      await page.goto(path);
      await expect(page.getByText("You don't have access to this page")).toBeVisible();
      await expect(page.getByText('Kovalenko')).toHaveCount(0);
    }
  });
});

test.describe('§2.7 / §2.8 members list and read-only card', () => {
  test('§2.7 #1, #2, #3, #4 and §2.13 #1: first page of 20 sorted by last name, search, empty result, next page', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await navLink(page, 'Members').click();

    const rows = page.getByRole('main').getByRole('listitem');
    await expect(rows).toHaveCount(20);
    await expect(page.getByText('Page 1 of 2')).toBeVisible();
    const firstRow = rows.first();
    await expect(firstRow).toContainText(/\+380\d{9}|@/); // phones or email
    await expect(firstRow).toContainText(/Email|Telegram|Viber|Paper/);
    await expect(firstRow).toContainText(/Member|Chairman|Treasurer/); // roles

    const lastNames = async () =>
      (await rows.locator('a > span:first-child').allInnerTexts()).map((t) =>
        (t.split(' ')[0] ?? '').toLowerCase(),
      );
    const firstPage = await lastNames();
    expect(firstPage).toEqual([...firstPage].sort((a, b) => a.localeCompare(b)));

    await page.getByRole('button', { name: 'Next' }).click();
    await expect(page.getByText('Page 2 of 2')).toBeVisible();
    const secondPage = await lastNames();
    expect(secondPage.length).toBeGreaterThan(0);
    expect(secondPage.length).toBeLessThan(21);
    expect((secondPage[0] ?? '').localeCompare(firstPage[firstPage.length - 1] ?? '')).toBeGreaterThanOrEqual(
      0,
    );
    await page.getByRole('button', { name: 'Previous' }).click();
    await expect(page.getByText('Page 1 of 2')).toBeVisible();

    await page.getByRole('searchbox').fill('067');
    await expect(async () => {
      const texts = await rows.allInnerTexts();
      expect(texts.length).toBeGreaterThan(0);
      for (const text of texts) expect(text).toContain('067');
    }).toPass();

    await page.getByRole('searchbox').fill('zzzz-nobody');
    await expect(page.getByText('Nothing found')).toBeVisible();
  });

  test('§2.7 #5: a failed load shows an error with a Try again button', async ({ page }) => {
    await loginAs(page, 'treasurer@demo.local');
    const listCall = /\/api\/v1\/members(\?|$)/;
    await page.route(listCall, (route) =>
      route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 400, code: 'MALFORMED_REQUEST' }),
      }),
    );

    await navLink(page, 'Members').click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();

    await page.unroute(listCall);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('main').getByRole('listitem')).toHaveCount(20);
  });

  test('§2.8 #1: the treasurer sees every field including notes and no Edit, Archive or role controls', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await page.goto(`/members/${PAVLENKO_ID}`);

    await expect(page.getByRole('heading', { level: 1, name: /Pavlenko/ })).toBeVisible();
    await expect(page.getByText('Prefers phone calls in the evening')).toBeVisible();
    await expect(page.getByText('+380672000014, +380933000014')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Archive' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Edit roles' })).toHaveCount(0);
  });
});

test.describe('§2.2 / §2.13 roles screen', () => {
  test('§2.2 #1, #2 and §2.13 #2: five roles, base roles are fixed, Chairman has all eight permissions locked', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await navLink(page, 'Roles').click();

    const items = page.getByRole('main').getByRole('listitem');
    for (const role of ['Chairman', 'Treasurer', 'Member', 'Secretary', 'Electrician']) {
      await expect(items.filter({ hasText: new RegExp(`^${role}`) }).first()).toBeVisible();
    }
    for (const role of ['Chairman', 'Treasurer', 'Member']) {
      const item = items.filter({ hasText: new RegExp(`^${role}`) }).first();
      await expect(item.getByText('Base role')).toBeVisible();
      await expect(item.getByRole('button', { name: 'Delete' })).toHaveCount(0);
    }
    await expect(
      items.filter({ hasText: /^Secretary/ }).getByRole('button', { name: 'Delete' }),
    ).toBeVisible();

    // Chairman: name not editable, every permission ticked and locked
    await items
      .filter({ hasText: /^Chairman/ })
      .getByRole('button', { name: 'Edit' })
      .click();
    await expect(page.getByLabel('Name', { exact: true })).toBeDisabled();
    const boxes = page.getByRole('checkbox');
    await expect(boxes).toHaveCount(8);
    for (let i = 0; i < 8; i++) {
      await expect(boxes.nth(i)).toBeChecked();
      await expect(boxes.nth(i)).toBeDisabled();
    }
    await expect(page.getByRole('button', { name: 'Save' })).toBeDisabled();
    await page.getByRole('button', { name: 'Cancel' }).click();

    // Treasurer: name locked, permissions editable
    await items
      .filter({ hasText: /^Treasurer/ })
      .getByRole('button', { name: 'Edit' })
      .click();
    await expect(page.getByLabel('Name', { exact: true })).toBeDisabled();
    await expect(page.getByRole('checkbox', { name: /^View members/ })).toBeEnabled();
  });

  test('§2.1 #1 and §2.11 #3: eight permissions with descriptions in Ukrainian by default, English on switch', async ({
    browser,
  }) => {
    // a browser language that is not supported falls back to the default locale (Ukrainian)
    const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'de-DE' });
    const page = await context.newPage();
    await loginAs(page, 'chairman@demo.local', { keepLocale: true });

    await expect(page.locator('header nav').getByRole('link', { name: 'Мій кабінет' })).toBeVisible();
    await expect(page.locator('header nav').getByRole('link', { name: 'Члени кооперативу' })).toBeVisible();
    await expect(page.locator('header nav').getByRole('link', { name: 'Журнал змін' })).toBeVisible();
    await page.locator('header nav').getByRole('link', { name: 'Ролі' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Ролі' })).toBeVisible();
    await page.getByRole('button', { name: 'Додати роль' }).click();

    const ukrainian = [
      ['Перегляд членів', 'Бачити всіх членів кооперативу, їхні контакти та примітки'],
      ['Редагування членів', 'Додавати, редагувати, архівувати й відновлювати членів'],
      ['Керування ролями', 'Створювати, змінювати й видаляти власні ролі'],
      ['Перегляд журналу змін', 'Читати журнал змін'],
      ['Перегляд ділянок', 'Бачити всі ділянки з подробицями'],
      ['Редагування ділянок', 'Додавати, редагувати й архівувати ділянки'],
      ['Редагування власності', 'Додавати, змінювати й виправляти власників ділянок'],
      ['Запуск імпорту', 'Запускати імпорт з Excel'],
    ];
    await expect(page.getByRole('checkbox')).toHaveCount(8);
    for (const [name, description] of ukrainian as [string, string][]) {
      await expect(page.getByText(name, { exact: true })).toBeVisible();
      await expect(page.getByText(description)).toBeVisible();
    }

    // switching to English turns the permission names, labels and navigation into English
    await page.getByRole('group', { name: 'Мова' }).getByRole('button', { name: 'en' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Roles' })).toBeVisible();
    for (const name of ALL_PERMISSIONS) await expect(page.getByText(name, { exact: true })).toBeVisible();
    await expect(page.getByLabel('Name', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Description', { exact: true })).toBeVisible();
    await expectNavigation(page, ['My account', 'Members', 'Roles', 'Audit log'], []);
    await page.getByRole('link', { name: 'Members' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible();
    await expect(page.getByRole('searchbox')).toHaveAttribute(
      'placeholder',
      'Search by name, phone or email',
    );
    await context.close();
  });
});

test.describe('chairman journeys that change data', () => {
  test('§2.3 #1-#3, #5, #6 and §2.9 #3: custom role lifecycle, with the audit log entries', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const roleName = `Acc ${stamp}`;
    await loginAs(page, 'chairman@demo.local');
    await navLink(page, 'Roles').click();
    const roleItem = page.getByRole('main').getByRole('listitem').filter({ hasText: roleName });

    // validation: empty and duplicate names (any letter case)
    await page.getByRole('button', { name: 'Add role' }).click();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Name is required.')).toBeVisible();
    await page.getByLabel('Name', { exact: true }).fill('treasurer');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('A role with this name already exists.')).toBeVisible();

    // create with two permissions
    await page.getByLabel('Name', { exact: true }).fill(roleName);
    await page.getByRole('checkbox', { name: /^View members/ }).check();
    await page.getByRole('checkbox', { name: /^Edit members/ }).check();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await expect(roleItem).toBeVisible();
    await roleItem.getByRole('button', { name: 'Edit' }).click();
    await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(2);
    await expect(page.getByRole('checkbox', { name: /^View members/ })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: /^Edit members/ })).toBeChecked();
    // edit: add a description
    await page.getByLabel('Description', { exact: true }).fill('Acceptance role');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(roleItem).toContainText('Acceptance role');

    // give the role to a new member: it cannot be deleted while held
    await navLink(page, 'Members').click();
    await page.getByRole('link', { name: 'Add member' }).click();
    await page.getByLabel('Last name').fill(`RoleHolder${stamp}`);
    await page.getByLabel('First name').fill('Test');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('heading', { level: 1, name: `RoleHolder${stamp} Test` })).toBeVisible();
    await page.getByRole('button', { name: 'Edit roles' }).click();
    await page.getByRole('checkbox', { name: roleName }).check();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await expect(page.getByText(roleName, { exact: true })).toBeVisible();
    const holderUrl = page.url();

    await navLink(page, 'Roles').click();
    await roleItem.getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
    // soft: the rest of the journey still runs and cleans up if the message does not arrive
    await expect
      .soft(page.getByText('This role is assigned to members and cannot be deleted.'))
      .toBeVisible();
    await expect(roleItem).toBeVisible();

    // release it, delete it, archive the helper member
    await page.goto(holderUrl);
    await page.getByRole('button', { name: 'Edit roles' }).click();
    await page.getByRole('checkbox', { name: roleName }).uncheck();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await page.getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByText('Archived', { exact: true })).toBeVisible();
    await navLink(page, 'Roles').click();
    await roleItem.getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByRole('status')).toHaveText('Role deleted');
    await expect(roleItem).toHaveCount(0);

    // audit: filter by kind of record shows only roles, with the three entries of this role
    await navLink(page, 'Audit log').click();
    await page.getByLabel('Kind of record').selectOption({ label: 'Role' });
    await expectAuditRow(page, 'Role deleted');
    const entries = page.locator('tbody tr');
    await expect(entries.filter({ hasText: roleName }).filter({ hasText: 'Role created' })).toHaveCount(1);
    await expect(entries.filter({ hasText: roleName }).filter({ hasText: 'Role updated' })).toHaveCount(1);
    await expect(entries.filter({ hasText: roleName }).filter({ hasText: 'Role deleted' })).toHaveCount(1);
    for (const text of await entries.allInnerTexts()) expect(text).not.toContain('Member:');
  });

  test('§2.6, §2.8 #2, #3, §2.9 #1, #2, #4-#6, #8: a member from creation to archive and restore, and its audit trail', async ({
    page,
  }) => {
    // letters only: a search term with digits is a phone search
    const stamp = Date.now()
      .toString(36)
      .replace(/\d/g, (d) => 'abcdefghij'[Number(d)] ?? 'x');
    const lastName = `Acceptance${stamp}`;
    const email = `acc.${stamp}@example.com`;
    await loginAs(page, 'chairman@demo.local');
    await navLink(page, 'Members').click();
    await page.getByRole('link', { name: 'Add member' }).click();

    // no tax, passport or birth-date fields
    for (const label of [/tax/i, /passport/i, /birth/i, /ІПН|паспорт/i]) {
      await expect(page.getByLabel(label)).toHaveCount(0);
    }

    // validation: nothing saved, messages next to the fields
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Last name is required.')).toBeVisible();
    await expect(page).toHaveURL(/\/members\/new$/);
    await page.getByLabel('Last name').fill(lastName);
    await page.getByLabel('First name').fill('Ivan');
    await page.getByRole('button', { name: 'Add phone' }).click();
    await page.getByLabel('Phone 1', { exact: true }).fill('12345');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Enter a phone number like +380671234567 or 0671234567.')).toBeVisible();
    await expect(page).toHaveURL(/\/members\/new$/);
    for (const [channel, message] of [
      ['Telegram', 'Telegram needs a Telegram username.'],
      ['Email', 'Email needs an email.'],
      ['Viber', 'Viber needs a Viber phone.'],
    ] as const) {
      await page.getByLabel('Preferred channel').selectOption({ label: channel });
      await page.getByLabel('Phone 1', { exact: true }).fill('067 123 45 67');
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByText(message)).toBeVisible();
    }

    // no channel chosen: the card says Paper; the phone is normalised
    await page.getByLabel('Preferred channel').selectOption({ label: 'Paper' });
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await expect(page.getByRole('heading', { level: 1, name: `${lastName} Ivan` })).toBeVisible();
    await expect(page.getByText('+380671234567', { exact: true })).toBeVisible();
    const channelRow = page
      .locator('dt', { hasText: 'Preferred channel' })
      .locator('xpath=following-sibling::dd[1]');
    await expect(channelRow).toHaveText('Paper');
    const cardUrl = page.url();

    // the same email in another letter case is refused
    await page.goto('/members/new');
    await page.getByLabel('Last name').fill(`Dup${stamp}`);
    await page.getByLabel('First name').fill('Petro');
    await page.getByLabel('Email', { exact: true }).fill(email.toUpperCase());
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Another active member already uses this email.')).toBeVisible();

    // edit: new address and new phone, "Saved" confirmation
    await page.goto(cardUrl);
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    await page.getByLabel('Postal address').fill('5 Acceptance Street, Kyiv');
    await page.getByLabel('Phone 1', { exact: true }).fill('050 111 22 33');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await expect(page.getByText('5 Acceptance Street, Kyiv')).toBeVisible();
    await expect(page.getByText('+380501112233', { exact: true })).toBeVisible();

    // roles: give Secretary
    await page.getByRole('button', { name: 'Edit roles' }).click();
    await page.getByRole('checkbox', { name: 'Secretary' }).check();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('status')).toHaveText('Saved');
    await expect(
      page.locator('dt', { hasText: 'Roles' }).locator('xpath=following-sibling::dd[1]'),
    ).toHaveText('Secretary');

    // archive: gone from the default list, present with "Show archived", marked archived
    await page.getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByText('Archived', { exact: true })).toBeVisible();
    await page.goto('/members');
    await page.getByRole('searchbox').fill(lastName);
    await expect(page.getByText('Nothing found')).toBeVisible();
    await page.goto('/members');
    await page.getByLabel('Show archived').click(); // controlled by the URL: assert the state, not check()
    await expect(page.getByLabel('Show archived')).toBeChecked();
    await expect(page).toHaveURL(/archived=true/);
    await page.getByRole('searchbox').fill(lastName);
    const row = page.getByRole('main').getByRole('listitem').filter({ hasText: lastName });
    await expect(row).toBeVisible();
    await expect(row.getByText('Archived', { exact: true })).toBeVisible();

    // restore: back in the default list
    await page.goto(cardUrl);
    await page.getByRole('button', { name: 'Restore' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Restore' }).click();
    await expect(page.getByRole('button', { name: 'Archive' })).toBeVisible();
    await page.goto('/members');
    await page.getByRole('searchbox').fill(lastName);
    await expect(page.getByRole('main').getByRole('listitem').filter({ hasText: lastName })).toBeVisible();

    // audit trail from the card: only this member, each step with author, old and new values
    await page.goto(cardUrl);
    await page.getByRole('link', { name: 'Change history' }).click();
    await expect(page).toHaveURL(/entityId=/);
    await expectAuditRow(page, 'Member restored');
    const rows = page.locator('tbody tr');
    for (const action of [
      'Member created',
      'Member updated',
      'Roles changed',
      'Member archived',
      'Member restored',
    ]) {
      await expect(rows.filter({ hasText: action }).first()).toBeVisible();
    }
    for (const text of await rows.allInnerTexts()) {
      expect(text).toContain(`${lastName} Ivan`);
      expect(text).toContain('Kovalenko Oleksandr Petrovych');
    }
    const update = rows.filter({ hasText: 'Member updated' }).first();
    await expect(update).toContainText('+380671234567 → +380501112233');
    await expect(update).toContainText('5 Acceptance Street, Kyiv');
    await expect(rows.filter({ hasText: 'Roles changed' }).first()).toContainText('Secretary');
    // read-only: no edit or delete control anywhere on the log
    await expect(page.getByRole('main').getByRole('button', { name: /^(edit|delete)/i })).toHaveCount(0);

    // date range (inclusive): today's entries are shown, a range in the future shows nothing
    const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
    await page.getByLabel('From').fill(day(-1));
    await page.getByLabel('To').fill(day(1));
    await expect(rows.filter({ hasText: 'Member created' }).first()).toBeVisible();
    await page.getByLabel('From').fill(day(3));
    await page.getByLabel('To').fill(day(4));
    await expect(page.getByText('Nothing found')).toBeVisible();

    // filter by one record: only that member
    await page.goto(`/audit?entityId=${cardUrl.split('/').pop()}`);
    await expect(rows.first()).toBeVisible();
    for (const text of await rows.allInnerTexts()) expect(text).toContain(`${lastName} Ivan`);

    // leave the demo list as it was: the helper member ends archived
    await page.goto(cardUrl);
    await page.getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByText('Archived', { exact: true })).toBeVisible();
  });

  test('§2.2 #3: unticking "View audit log" for Treasurer removes the Audit log item for the treasurer', async ({
    page,
    browser,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await navLink(page, 'Roles').click();
    const treasurer = page
      .getByRole('main')
      .getByRole('listitem')
      .filter({ hasText: /^Treasurer/ });
    const setAuditView = async (ticked: boolean) => {
      await treasurer.getByRole('button', { name: 'Edit' }).click();
      await page.getByRole('checkbox', { name: /^View audit log/ }).setChecked(ticked);
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page.getByRole('status')).toHaveText('Saved');
    };

    await setAuditView(false);
    try {
      const treasurerPage = await signedInPage(browser, 'treasurer@demo.local');
      await expectNavigation(treasurerPage, ['My account', 'Members'], ['Roles', 'Audit log']);
      await treasurerPage.goto('/audit');
      await expect(treasurerPage.getByText("You don't have access to this page")).toBeVisible();
      await treasurerPage.context().close();
    } finally {
      await setAuditView(true);
    }

    const again = await signedInPage(browser, 'treasurer@demo.local');
    await expectNavigation(again, ['My account', 'Members', 'Audit log'], ['Roles']);
    await again.context().close();
  });

  test('§2.4 #3: an archived member is shown the "not linked" message and no cooperative data', async ({
    page,
    browser,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto(`/members/${ELECTRICIAN_ID}`);
    await page.getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByText('Archived', { exact: true })).toBeVisible();
    try {
      const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
      const archived = await context.newPage();
      await loginAs(archived, 'electrician@demo.local', { skipNavigationWait: true });
      await expect(archived.getByRole('heading', { name: 'Account not linked' })).toBeVisible();
      await expect(
        archived.getByText(
          'Your account is not linked to a member of this cooperative. Please contact your chairman.',
        ),
      ).toBeVisible();
      await expect(archived.getByText('Tkachenko')).toHaveCount(0);
      await expect(archived.locator('header nav')).toHaveCount(0);
      await context.close();
    } finally {
      await page.getByRole('button', { name: 'Restore' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Restore' }).click();
      await expect(page.getByRole('button', { name: 'Archive' })).toBeVisible();
    }
  });
});

test.describe('§2.4 / §2.12 other people', () => {
  test('§2.4 #5: the platform operator belongs to no cooperative and sees no members', async ({ page }) => {
    await loginAs(page, 'operator@nadel.local', { skipNavigationWait: true });

    await expect(page.getByRole('heading', { name: 'No cooperative' })).toBeVisible();
    await expect(page.getByText('you are the platform operator', { exact: false })).toBeVisible();
    await expect(page.getByText('Kovalenko')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Members' })).toHaveCount(0);
  });

  test('§2.12 #2 and #1: a person of the other cooperative gets "Not found" for a demo card and sees only their own', async ({
    page,
  }) => {
    await loginAs(page, 'member@other.local');

    await expect(page.getByText('Melnyk Olena Stepanivna')).toBeVisible();
    await expect(page.getByText('Other Cooperative')).toBeVisible();
    await page.goto(`/members/${CHAIRMAN_ID}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Not found' })).toBeVisible();
    await expect(page.getByText('Kovalenko')).toHaveCount(0);
    await page.goto('/members');
    await expect(page.getByText("You don't have access to this page")).toBeVisible();
  });
});

test.describe('§2.7 #2 filters together', () => {
  test('§2.7 #2: "Show archived" can be switched on while a search is active', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/members');
    await page.getByRole('searchbox').fill('Kovalenko');
    await expect(page).toHaveURL(/q=Kovalenko/);
    await page.getByLabel('Show archived').click(); // controlled by the URL: assert the state, not check()
    await expect(page.getByLabel('Show archived')).toBeChecked();
    await expect(page.getByLabel('Show archived')).toBeChecked();
    await expect(page).toHaveURL(/archived=true/);
    await expect(page.getByRole('main').getByRole('listitem').filter({ hasText: 'Kovalenko' })).toBeVisible();
  });
});
