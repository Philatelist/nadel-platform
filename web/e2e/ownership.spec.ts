// @layer: e2e
// @spec: 007-ownership
// @regression
// Ownership flows in a browser: change of owner, the share total, correction, what the treasurer and the member see,
// the cooperative setting for plot colours and the guards. Plots and members are CREATED per run with unique
// E2E names (ownership rows cannot be deleted by the app, so leftovers are archived and never reused); the demo data
// is only read, and the cooperative setting is restored to "Own plots only" in `finally`.
import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  createPlot,
  kyivDay,
  shortDay,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');
const rows = (page: Page) => main(page).locator('tbody tr');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });
const owners = (page: Page, name: string) => main(page).getByRole('listitem').filter({ hasText: name });
const numbers = (page: Page) => rows(page).locator('td:first-child');

async function pickMember(dialog: Locator, n: number, member: CreatedMember): Promise<void> {
  await dialog.getByLabel(`Member ${n}`, { exact: true }).fill(member.lastName);
  await dialog.getByRole('button', { name: member.name, exact: true }).click();
}

async function openPlot(page: Page, plot: CreatedPlot): Promise<void> {
  await page.goto(`/plots/${plot.id}`);
  await expect(page.getByRole('heading', { level: 1, name: `Plot ${plot.number}` })).toBeVisible();
}

/** Opens the plot with the given number from the list (demo plots are found by number on their street). */
async function openDemoPlot(page: Page, number: string, search: string): Promise<void> {
  await page.goto('/plots');
  await main(page).getByLabel(search, { exact: true }).fill(number);
  // the search reaches the URL after its debounce: wait for it, so a late write cannot cancel the click below
  await expect(page).toHaveURL(new RegExp(`[?&]q=${number}(&|$)`));
  await rows(page).getByRole('link', { name: number, exact: true }).first().click();
  await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
}

interface ListedPlot {
  id: string;
  number: string;
  status?: string;
  areaM2?: number;
}

/** The plots the API lists for the signed-in person (a member has no plots screen, but the API still limits the rows). */
async function listedPlots(page: Page, query: string): Promise<ListedPlot[]> {
  const result = await api(page, 'GET', `/plots?q=${encodeURIComponent(query)}`);
  expect(result.status).toBe(200);
  return (JSON.parse(result.body) as { items: ListedPlot[] }).items;
}

/** Opens a plot of the demo cooperative by address: a member reaches the cards of other plots only this way. */
async function openPlotByNumber(page: Page, number: string): Promise<void> {
  const plot = (await listedPlots(page, number)).find((p) => p.number === number);
  expect(plot, `demo plot ${number} in the list of the API`).toBeDefined();
  await page.goto(`/plots/${plot?.id}`);
  await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
}

/** The audit log is written by the outbox: reload until the row appears. */
async function expectAuditRow(page: Page, plotNumber: string, action: string): Promise<Locator> {
  const row = page.getByRole('row').filter({ hasText: plotNumber }).filter({ hasText: action }).first();
  await expect(async () => {
    await page.reload();
    await expect(row).toBeVisible({ timeout: 1500 });
  }).toPass({ timeout: 20_000 });
  return row;
}

async function memberPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, 'member@demo.local');
  return page;
}

test.describe('ownership', () => {
  test('chairman changes the owner to two co-owners, then to one: card, history, audit; 1/2 + 1/3 is blocked with 5/6', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await loginAs(page, 'chairman@demo.local');
    try {
      const plot = await createPlot(page, `E2E-OWN-${stamp}`);
      plots.push(plot);
      const a = await createMember(page, `E2eA${stamp}`);
      const b = await createMember(page, `E2eB${stamp}`);
      const c = await createMember(page, `E2eC${stamp}`);
      members.push(a, b, c);
      await openPlot(page, plot);
      await expect(main(page)).toContainText('No owner');

      // 1/2 + 1/3: the total is 5/6, Save stays disabled until the shares add up to one whole
      await page.getByRole('button', { name: 'Change owner', exact: true }).click();
      const dialog = page.getByRole('dialog');
      const save = dialog.getByRole('button', { name: 'Save', exact: true });
      const firstDay = kyivDay(-10);
      await dialog.getByLabel('Owners from', { exact: true }).fill(firstDay);
      await pickMember(dialog, 1, a);
      await dialog.getByLabel('Share (like 1/2) 1', { exact: true }).fill('1/2');
      await dialog.getByRole('button', { name: 'Add owner', exact: true }).click();
      await pickMember(dialog, 2, b);
      await dialog.getByLabel('Share (like 1/2) 2', { exact: true }).fill('1/3');
      await expect(dialog.getByRole('status')).toHaveText('Total: 5/6 — must be 1');
      await expect(save).toBeDisabled();

      // 1/2 + 1/2: saved, both owners with 1/2 on the card
      await dialog.getByLabel('Share (like 1/2) 2', { exact: true }).fill('2/4');
      await expect(dialog.getByRole('status')).toHaveText('Total: 1');
      await expect(save).toBeEnabled();
      await save.click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(owners(page, a.name)).toContainText('1/2');
      await expect(owners(page, b.name)).toContainText('1/2');
      await expect(owners(page, a.name)).toContainText(`since ${shortDay(firstDay)}`);
      await expect(main(page)).toContainText('Ownership history');

      // change again to one owner: the two previous owners move to the history and end the day before
      await page.getByRole('button', { name: 'Change owner', exact: true }).click();
      const secondDay = kyivDay(-5);
      await dialog.getByLabel('Owners from', { exact: true }).fill(secondDay);
      await pickMember(dialog, 1, c);
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      const period = `${shortDay(firstDay)} – ${shortDay(kyivDay(-6))}`;
      await expect(owners(page, c.name)).toContainText(`since ${shortDay(secondDay)}`);
      for (const previous of [a, b]) {
        await expect(owners(page, previous.name)).toContainText(period);
        await expect(owners(page, previous.name)).toContainText('1/2');
      }

      // the audit log filtered to ownership: one entry per change of this plot
      await menuLink(page, 'Audit log').click();
      await page.getByLabel('Kind of record').selectOption('OWNERSHIP');
      await expectAuditRow(page, plot.number, 'Owners changed');
      await expect(
        page.getByRole('row').filter({ hasText: plot.number }).filter({ hasText: 'Owners changed' }),
      ).toHaveCount(2);
      const latest = page.getByRole('row').filter({ hasText: plot.number }).first();
      await expect(latest).toContainText(c.name);
      await expect(latest).toContainText(shortDay(secondDay));
    } finally {
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });

  test('chairman corrects 1/2 + 1/2 to 2/3 + 1/3 with a reason; the audit log shows the correction', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await loginAs(page, 'chairman@demo.local');
    try {
      const plot = await createPlot(page, `E2E-COR-${stamp}`);
      plots.push(plot);
      const a = await createMember(page, `E2eCA${stamp}`);
      const b = await createMember(page, `E2eCB${stamp}`);
      members.push(a, b);
      await changeOwners(page, plot.id, kyivDay(-10), [
        { memberId: a.id, share: '1/2' },
        { memberId: b.id, share: '1/2' },
      ]);
      await openPlot(page, plot);
      await expect(owners(page, a.name)).toContainText('1/2');

      await page.getByRole('button', { name: `Correct the record of ${a.name}`, exact: true }).click();
      const dialog = page.getByRole('dialog');
      const save = dialog.getByRole('button', { name: 'Save', exact: true });
      await dialog.getByLabel('Share (like 1/2)', { exact: true }).fill('2/3');
      await dialog.getByLabel(`Share of ${b.name} (like 1/2)`, { exact: true }).fill('1/3');
      await expect(dialog.getByRole('status')).toHaveText('Total: 1');
      // a reason is required: without it nothing can be saved
      await expect(save).toBeDisabled();
      await dialog.getByLabel('Reason', { exact: true }).fill('Wrong shares in the contract copy');
      await expect(save).toBeEnabled();
      await save.click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(owners(page, a.name)).toContainText('2/3');
      await expect(owners(page, b.name)).toContainText('1/3');

      await menuLink(page, 'Audit log').click();
      await page.getByLabel('Kind of record').selectOption('OWNERSHIP');
      const row = await expectAuditRow(page, plot.number, 'Ownership corrected');
      await expect(row).toContainText('Wrong shares in the contract copy');
      await expect(row).toContainText('2/3');
    } finally {
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });

  test('treasurer sees the owners column, finds plots by an owner name and reads plot 9 without edit actions', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await expect(page).toHaveURL(/\/plots$/);
    await expect(main(page).locator('thead th', { hasText: 'Owners' })).toBeVisible();
    const search = main(page).getByLabel('Search by number, street or cadastral number', { exact: true });

    // plot 7 lists both co-owners
    await search.fill('Мороз');
    await expect(rows(page).filter({ hasText: 'Бондаренко Андрій' })).toHaveCount(1);
    await expect(numbers(page)).toHaveText(['7']);
    await expect(rows(page).first()).toContainText('Мороз Галина');

    // the last name of a current owner finds plot 11
    await search.fill('Кравченко');
    await expect(numbers(page)).toHaveText(['11']);
    await expect(rows(page).first()).toContainText('Кравченко Дмитро');
    await expect(rows(page).first()).toContainText('Руденко Богдан');

    // plot 9: the current owner and the archived previous owner with the period; nothing to edit
    await openDemoPlot(page, '9', 'Search by number, street or cadastral number');
    await expect(owners(page, 'Литвиненко Світлана')).toContainText(`since ${shortDay('2025-06-01')}`);
    await expect(owners(page, 'Гончаренко Микола')).toContainText(
      `${shortDay('2015-06-01')} – ${shortDay('2025-05-31')}`,
    );
    await expect(page.getByRole('button', { name: /^(Change owner|Correct)/ })).toHaveCount(0);
    await expect(main(page).getByRole('button', { name: /^Correct/ })).toHaveCount(0);
  });

  test('demo member sees My plots, a limited card for plot 9 and neutral marks under "Own plots only"', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');

    await page.goto('/account');
    await expect(page.getByRole('heading', { level: 2, name: 'My plots' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Plot 7', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Plot 15', exact: true })).toBeVisible();
    await expect(page.getByText('My share: 1/2')).toBeVisible();
    await expect(page.getByText('Мороз Галина')).toBeVisible();

    // a plot of someone else: number, street and kind only
    await openPlotByNumber(page, '9');
    for (const hidden of ['Owners', 'Ownership history', 'Area', 'Cadastral number', 'Status']) {
      await expect(main(page).getByText(hidden, { exact: true })).toHaveCount(0);
    }
    await expect(main(page)).not.toContainText('sotka');
    await expect(main(page)).not.toContainText('Литвиненко');
    await expect(main(page)).not.toContainText(/No debt|Has debt|Debt above threshold/);

    // a member has no plots list: /plots lands on the account
    await page.goto('/plots');
    await expect(page).toHaveURL(/\/account$/);
    await expect(menuLink(page, 'Plots')).toHaveCount(0);

    // what the API sends the member: own plots 7 and 15 carry a status (the demo ledger: 7 is paid up, 15 still owes),
    // every other plot none, so the client can only draw a neutral mark; the details stay with the owners
    const listed = await listedPlots(page, '');
    const withStatus = listed.filter((p) => p.status !== undefined);
    expect(withStatus.map((p) => p.number).sort()).toEqual(['15', '7']);
    expect(listed.filter((p) => p.status === undefined)).toHaveLength(18);
    expect(
      listed
        .filter((p) => p.areaM2 !== undefined)
        .map((p) => p.number)
        .sort(),
    ).toEqual(['15', '7']);
  });

  test('chairman switches to "All plots": the member sees labels on other plots but still no area or owners', async ({
    page,
    browser,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const member = await memberPage(browser);
    try {
      await menuLink(page, 'Cooperative settings').click();
      await expect(page.getByRole('heading', { level: 1, name: 'Cooperative settings' })).toBeVisible();
      await expect(page.getByRole('radio', { name: /^Own plots only/ })).toBeChecked();
      await page.getByRole('radio', { name: /^All plots/ }).check();
      await main(page)
        .getByRole('group', { name: 'Who sees plot colours' })
        .locator('xpath=ancestor::form')
        .getByRole('button', { name: 'Save', exact: true })
        .click();
      await expect(main(page).getByText('Settings saved.')).toBeVisible();

      // the member has no plots list; what the API sends is the same the list used to show
      const listed = await listedPlots(member, '');
      expect(listed.length).toBeGreaterThan(0);
      expect(listed.filter((p) => p.status === undefined)).toEqual([]);
      expect(
        listed
          .filter((p) => p.areaM2 !== undefined)
          .map((p) => p.number)
          .sort(),
      ).toEqual(['15', '7']);
      await openPlotByNumber(member, '9');
      await expect(main(member)).toContainText(/Debt above threshold|Has debt/); // plot 9 has a debt in the demo ledger
      await expect(main(member)).not.toContainText('sotka');
      await expect(main(member)).not.toContainText('Литвиненко');
      await expect(main(member).getByText('Owners', { exact: true })).toHaveCount(0);

      // the audit log keeps the old and the new value
      await menuLink(page, 'Audit log').click();
      await page.getByLabel('Kind of record').selectOption('SETTINGS');
      const row = await expectAuditRow(page, 'Cooperative settings', 'Settings changed');
      await expect(row).toContainText('Own plots only → All plots');
    } finally {
      // back to the default, whatever happened above
      await api(page, 'PUT', '/settings', { memberPlotStatusVisibility: 'OWN_ONLY' }).catch(() => undefined);
      await member.context().close();
    }
    const restored = await api(page, 'GET', '/settings');
    expect(JSON.parse(restored.body)).toMatchObject({ memberPlotStatusVisibility: 'OWN_ONLY' });
  });

  for (const email of ['treasurer@demo.local', 'member@demo.local']) {
    test(`${email} has no settings menu item and is denied /settings`, async ({ page }) => {
      await loginAs(page, email);
      await expect(menuLink(page, 'Cooperative settings')).toHaveCount(0);
      await page.goto('/settings');
      await expect(page.getByRole('alert')).toContainText("You don't have access to this page");
      await expect(page.getByRole('radio')).toHaveCount(0);
      const refused = await api(page, 'PUT', '/settings', { memberPlotStatusVisibility: 'ALL_PLOTS' });
      expect(refused.status).toBe(403);
    });
  }

  test('guards: an owning member cannot be archived, an owned plot cannot be archived, a plot with history cannot be deleted', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await loginAs(page, 'chairman@demo.local');
    try {
      const plot = await createPlot(page, `E2E-GRD-${stamp}`);
      plots.push(plot);
      const owner = await createMember(page, `E2eG${stamp}`);
      members.push(owner);
      await changeOwners(page, plot.id, kyivDay(-10), [{ memberId: owner.id, share: '1/1' }]);

      // the member owns a plot: archiving is refused with the guard message and nothing changes
      await page.goto(`/members/${owner.id}`);
      await page.getByRole('button', { name: 'Archive', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Archive', exact: true }).click();
      await expect(
        main(page)
          .getByRole('alert')
          .or(main(page).getByText(/owns a plot/)),
      ).toContainText('This member owns a plot — change the owner first');
      await expect(main(page).getByText('Archived', { exact: true })).toHaveCount(0);

      // the plot has an owner: archiving it is refused too
      await openPlot(page, plot);
      await page.getByRole('button', { name: 'Archive', exact: true }).click();
      await expect(main(page)).toContainText('This plot has an owner — change the owner first');

      // end the ownership (a change with no owners), then the plot has history: deleting it is refused
      await changeOwners(page, plot.id, kyivDay(-5), []);
      await openPlot(page, plot);
      await expect(main(page)).toContainText('No owner');
      await page.getByRole('button', { name: 'Delete', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
      await expect(main(page)).toContainText('A plot with ownership history cannot be deleted');
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${plot.number}` })).toBeVisible();
    } finally {
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });
});
