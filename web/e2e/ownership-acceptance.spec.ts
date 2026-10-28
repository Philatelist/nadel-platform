// @layer: e2e
// @spec: 007-ownership
// @regression
// Acceptance specs of the whole feature "Ownership" (functional-spec.md of 007) that ownership.spec.ts does not cover:
// the Ukrainian wording of the share rule, of "no owner" and of the member guard, the settings page and its
// explanation, the permission offered on the roles page, the "Plots" section of the member card, common land without a
// change action and the demo cooperative as the treasurer reads it. Criteria are named §2.x #n. Plots and members are
// CREATED per run under unique names (ownership rows cannot be deleted by the app, so leftovers are archived); the
// demo data is only read, and the cooperative setting is never changed here.
import { expect, test, type Locator, type Page } from '@playwright/test';
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

/** A calendar day as the Ukrainian UI prints it (short date, no time zone shift). */
const shortDayUk = (isoDay: string) =>
  new Intl.DateTimeFormat('uk', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(isoDay));

async function signInInUkrainian(page: Page, email: string): Promise<void> {
  await page.addInitScript(() => {
    try {
      window.localStorage.setItem('nadel.locale', 'uk');
    } catch {
      // storage unavailable: Ukrainian is the default anyway
    }
  });
  await loginAs(page, email, { keepLocale: true });
}

async function pickMember(dialog: Locator, label: string, member: CreatedMember): Promise<void> {
  await dialog.getByLabel(label, { exact: true }).fill(member.lastName);
  await dialog.getByRole('button', { name: member.name, exact: true }).click();
}

test.describe('ownership: acceptance', () => {
  test('§2.1 #1, §2.2 #5 in Ukrainian: a refused save shows "Частки мають разом становити 1", then removing every owner row gives "Без власника"', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await signInInUkrainian(page, 'chairman@demo.local');
    try {
      const plot = await createPlot(page, `E2E-ACC-${stamp}`);
      plots.push(plot);
      const a = await createMember(page, `E2eAccA${stamp}`);
      const b = await createMember(page, `E2eAccB${stamp}`);
      members.push(a, b);
      await page.goto(`/plots/${plot.id}`);
      await expect(page.getByRole('heading', { level: 1, name: `Ділянка ${plot.number}` })).toBeVisible();
      await expect(main(page)).toContainText('Без власника');

      // 1/2 + 1/2 in the form, but the request reaches the server as 1/2 + 1/3: the server refuses the save
      await page.getByRole('button', { name: 'Змінити власника', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Власники з', { exact: true }).fill(kyivDay(-10));
      await pickMember(dialog, 'Член кооперативу 1', a);
      await dialog.getByLabel('Частка (наприклад 1/2) 1', { exact: true }).fill('1/2');
      await dialog.getByRole('button', { name: 'Додати власника', exact: true }).click();
      await pickMember(dialog, 'Член кооперативу 2', b);
      await dialog.getByLabel('Частка (наприклад 1/2) 2', { exact: true }).fill('1/2');
      await expect(dialog.getByRole('status')).toHaveText('Разом: 1');

      await page.route('**/ownership-changes', async (route) => {
        const data = route.request().postDataJSON() as { owners: { share: string }[] };
        const second = data.owners[1];
        if (second) second.share = '1/3';
        await route.continue({ postData: JSON.stringify(data) });
      });
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog.getByRole('alert')).toContainText('Частки мають разом становити 1');
      await page.unroute('**/ownership-changes');
      // nothing changed on the server
      const unchanged = JSON.parse((await api(page, 'GET', `/plots/${plot.id}/ownerships`)).body) as {
        current: unknown[];
      };
      expect(unchanged.current).toHaveLength(0);

      // the same form, sent as typed: saved
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(owners(page, a.name)).toContainText('1/2');
      await expect(owners(page, b.name)).toContainText('1/2');

      // remove every owner row and save: grey "Без власника" from that date, both owners move to the history
      await page.getByRole('button', { name: 'Змінити власника', exact: true }).click();
      await dialog.getByLabel('Власники з', { exact: true }).fill(kyivDay(-5));
      // the dialog opens with one empty owner row: removing it leaves no owner at all
      await dialog.getByRole('button', { name: 'Прибрати власника 1', exact: true }).click();
      await expect(dialog.getByText('Без власників ділянка не матиме власника з цієї дати.')).toBeVisible();
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(main(page)).toContainText('Без власника');
      for (const former of [a, b]) {
        await expect(owners(page, former.name)).toContainText(
          `${shortDayUk(kyivDay(-10))} – ${shortDayUk(kyivDay(-6))}`,
        );
      }
      await expect(main(page).getByRole('heading', { name: 'Історія володіння' })).toBeVisible();
    } finally {
      await page.unroute('**/ownership-changes').catch(() => undefined);
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });

  test('§2.4 #1 in Ukrainian: archiving a member who owns a plot is refused with "Цей член кооперативу є власником ділянки — спочатку змініть власника"', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await signInInUkrainian(page, 'chairman@demo.local');
    try {
      const plot = await createPlot(page, `E2E-ACG-${stamp}`);
      plots.push(plot);
      const owner = await createMember(page, `E2eAccG${stamp}`);
      members.push(owner);
      await changeOwners(page, plot.id, kyivDay(-10), [{ memberId: owner.id, share: '1/1' }]);

      await page.goto(`/members/${owner.id}`);
      await page.getByRole('button', { name: 'Архівувати', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Архівувати', exact: true }).click();
      await expect(
        main(page).getByText('Цей член кооперативу є власником ділянки — спочатку змініть власника'),
      ).toBeVisible();
      await expect(main(page).getByText('В архіві', { exact: true })).toHaveCount(0);
    } finally {
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });

  test('§2.7 in Ukrainian: the chairman finds "Налаштування кооперативу" in the menu with the two choices and the general-meeting explanation', async ({
    page,
  }) => {
    await signInInUkrainian(page, 'chairman@demo.local');
    await menuLink(page, 'Налаштування кооперативу').click();

    await expect(page.getByRole('heading', { level: 1, name: 'Налаштування кооперативу' })).toBeVisible();
    await expect(
      main(page).getByText(
        'Показувати колір боргу всіх ділянок усім членам — лише за рішенням загальних зборів',
      ),
    ).toBeVisible();
    // the default of every cooperative is "own plots only"
    await expect(main(page).getByRole('radio', { name: /^Лише свої ділянки/ })).toBeChecked();
    await expect(main(page).getByRole('radio', { name: /^Усі ділянки/ })).not.toBeChecked();
    // the visibility group has exactly the two choices (the page has other radio groups: losses, thresholds)
    await expect(
      main(page).getByRole('group', { name: 'Хто бачить кольори ділянок' }).getByRole('radio'),
    ).toHaveCount(2);
  });

  test('§2.7 #7: the roles page offers "Manage cooperative settings" as a permission that can be ticked', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await menuLink(page, 'Roles').click();

    // the Treasurer role has no such permission by default; ticking it is possible (and is not saved here)
    await main(page)
      .getByRole('listitem')
      .filter({ hasText: /^Treasurer/ })
      .getByRole('button', { name: 'Edit' })
      .click();
    const permission = page.getByRole('checkbox', { name: /^Manage cooperative settings/ });
    await expect(permission).toBeVisible();
    await expect(permission).toBeEnabled();
    await expect(permission).not.toBeChecked();
    await permission.check();
    await expect(permission).toBeChecked();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(permission).toHaveCount(0);
  });

  test('§2.5 #3: the member card of the chairman view lists the plots the member owns now with the share and owned before with the period', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    await loginAs(page, 'chairman@demo.local');
    try {
      const sold = await createPlot(page, `E2E-ACS-${stamp}`);
      const held = await createPlot(page, `E2E-ACH-${stamp}`);
      plots.push(sold, held);
      const owner = await createMember(page, `E2eAccO${stamp}`);
      const other = await createMember(page, `E2eAccP${stamp}`);
      members.push(owner, other);
      // the member owned `sold` until the day before the sale and owns half of `held`
      await changeOwners(page, sold.id, kyivDay(-30), [{ memberId: owner.id, share: '1/1' }]);
      await changeOwners(page, sold.id, kyivDay(-20), [{ memberId: other.id, share: '1/1' }]);
      await changeOwners(page, held.id, kyivDay(-10), [
        { memberId: owner.id, share: '1/2' },
        { memberId: other.id, share: '1/2' },
      ]);

      await page.goto(`/members/${owner.id}`);
      await expect(main(page).getByRole('heading', { name: 'Plots', exact: true })).toBeVisible();
      const nowItem = main(page)
        .getByRole('listitem')
        .filter({ hasText: `Plot ${held.number}` });
      await expect(nowItem).toContainText('1/2');
      await expect(nowItem).toContainText(`since ${shortDay(kyivDay(-10))}`);
      const before = main(page)
        .getByRole('listitem')
        .filter({ hasText: `Plot ${sold.number}` });
      await expect(before).toContainText(`${shortDay(kyivDay(-30))} – ${shortDay(kyivDay(-21))}`);
      await expect(main(page).getByRole('heading', { name: 'Owns now' })).toBeVisible();
      await expect(main(page).getByRole('heading', { name: 'Owned before' })).toBeVisible();
    } finally {
      await archiveLeftovers(page, plots, members, kyivDay(0));
    }
  });

  test('§2.1 #6: the card of the common-land road offers no "Change owner", and a change sent anyway is refused', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const listed = JSON.parse((await api(page, 'GET', '/plots?q=R-1')).body) as {
      items: { id: string; number: string }[];
    };
    const road = listed.items.find((p) => p.number === 'R-1');
    expect(road).toBeDefined();

    await page.goto(`/plots/${road?.id}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Common land R-1' })).toBeVisible();
    await expect(main(page)).toContainText('Common land has no owners.');
    await expect(page.getByRole('button', { name: /^(Change owner|Correct)/ })).toHaveCount(0);

    const refused = await api(page, 'POST', `/plots/${road?.id}/ownership-changes`, {
      effectiveFrom: kyivDay(-1),
      owners: [],
    });
    expect(refused.status).toBe(409);
    expect(JSON.parse(refused.body)).toMatchObject({ code: 'PLOT_COMMON_LAND' });
  });

  test('§2.8 #1: the treasurer sees the owned plots coloured by debt with owners, a few grey, two names on co-owned plots and the sale in the history of plot 9', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await expect(page).toHaveURL(/\/plots$/);

    // the first page: twenty owned plots, each with an owner name and a debt colour label (the demo ledger holds
    // payments, so some owned plots are paid up and show "No debt")
    await expect(rows(page)).toHaveCount(20);
    await expect(rows(page).filter({ hasText: /Debt above threshold|Has debt|No debt/ })).toHaveCount(20);
    await expect(rows(page).filter({ hasText: 'No owner' })).toHaveCount(0);
    const coOwned = rows(page).filter({ has: page.locator('td:first-child', { hasText: /^3$/ }) });
    await expect(coOwned).toHaveCount(1);
    await expect(coOwned).toContainText(/\S+ \S+, \S+ \S+/);

    // the second page: the last owned plots, then the few grey ones and the road
    await page.goto('/plots?page=1');
    await expect(rows(page)).toHaveCount(11);
    await expect(rows(page).filter({ hasText: /Debt above threshold|Has debt|No debt/ })).toHaveCount(5);
    await expect(rows(page).filter({ hasText: 'No owner' })).toHaveCount(5);
    await expect(rows(page).filter({ hasText: 'Common land' })).toHaveCount(1);

    // plot 9 was sold: the previous owner is in the history with the basis of the new owner's record
    await page.goto('/plots');
    await main(page).getByLabel('Search by number, street or cadastral number', { exact: true }).fill('9');
    // the search reaches the URL after its debounce: wait for it, so a late write cannot cancel the click below
    await expect(page).toHaveURL(/[?&]q=9(&|$)/);
    await rows(page).getByRole('link', { name: '9', exact: true }).first().click();
    await expect(page.getByRole('heading', { level: 1, name: 'Plot 9' })).toBeVisible();
    await expect(main(page).getByRole('heading', { name: 'Ownership history' })).toBeVisible();
    await expect(owners(page, 'Гончаренко Микола')).toContainText('Первісний розподіл');
    await expect(owners(page, 'Литвиненко Світлана')).toContainText('Договір купівлі-продажу від 20.05.2025');
  });
});
