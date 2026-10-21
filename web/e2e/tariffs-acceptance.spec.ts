// @layer: e2e
// @spec: context/spec/009-charge-types-and-tariffs
// @regression
// Acceptance specs of the whole feature "Charge types and tariffs" (functional-spec.md of 009) that tariffs.spec.ts does
// not cover, read in the Ukrainian interface unless a spec says English. Criteria are named §2.x #n. Charge types,
// tariffs and plots are CREATED per run under unique names; the charge types are archived (which removes their tariffs
// from every plot) and the plots archived in `finally`. Demo tariffs are never counted, changed or asserted on.
import { expect as baseExpect, test, type Browser, type Page } from '@playwright/test';
import { api, archiveLeftovers, expectCreated, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';

// The shared dev stack can be slow under load: a longer wait for each assertion and for each spec.
const expect = baseExpect.configure({ timeout: 15_000 });
test.describe.configure({ timeout: 120_000 });

const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });
/** The amount as the UI prints it: a comma or a point before the kopecks, an optional space inside thousands. */
const money = (amount: string) => {
  const [whole = '', cents = '00'] = amount.split('.');
  const grouped = whole.replace(/(\d)(?=(\d{3})+$)/g, '$1[\\s\\u00a0\\u202f]?');
  return new RegExp(`(?<!\\d)${grouped}[.,]${cents}(?!\\d)`);
};

async function signIn(page: Page, email: string, locale: 'uk' | 'en'): Promise<void> {
  if (locale === 'uk') {
    await page.addInitScript(() => {
      try {
        window.localStorage.setItem('nadel.locale', 'uk');
      } catch {
        // storage unavailable: Ukrainian is the default anyway
      }
    });
    await loginAs(page, email, { keepLocale: true });
  } else {
    await loginAs(page, email);
  }
}

async function otherPage(browser: Browser, email: string, locale: 'uk' | 'en'): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await signIn(page, email, locale);
  return page;
}

interface Version {
  validFrom: string;
  basis: string;
  frequency: string;
  rate: string;
  dueDate?: string;
}

async function newType(page: Page, name: string): Promise<string> {
  return expectCreated<{ id: string }>(await api(page, 'POST', '/charge-types', { name })).id;
}

async function newTariff(
  page: Page,
  chargeTypeId: string,
  name: string,
  firstVersion: Version,
): Promise<string> {
  return expectCreated<{ id: string }>(
    await api(page, 'POST', '/tariffs', { chargeTypeId, name, firstVersion }),
  ).id;
}

async function newPlot(page: Page, number: string, kind = 'PLOT', areaM2 = 600): Promise<CreatedPlot> {
  const created = expectCreated<{ id: string }>(
    await api(page, 'POST', '/plots', {
      number,
      kind,
      areaM2: kind === 'COMMON' ? 0 : areaM2,
      street: 'E2E Lane',
    }),
  );
  return { id: created.id, number };
}

async function typeIdByName(page: Page, name: string): Promise<string | undefined> {
  const types = expectCreated<{ id: string; name: string }[]>(await api(page, 'GET', '/charge-types'), 200);
  return types.find((t) => t.name === name)?.id;
}

test.describe('tariffs: acceptance', () => {
  test('§2.1 #1 the four starting types in Ukrainian and in English, Water inactive', async ({
    page,
    browser,
  }) => {
    await signIn(page, 'chairman@demo.local', 'uk');
    await page.goto('/charge-types');
    const ukRows = main(page).getByRole('listitem');
    for (const name of ['Членський внесок', 'Електроенергія', 'Земельний податок', 'Вода']) {
      await expect(ukRows.filter({ hasText: name }).first()).toBeVisible();
    }
    await expect(ukRows.filter({ hasText: 'Вода' }).first()).toContainText('Неактивний');

    const english = await otherPage(browser, 'chairman@demo.local', 'en');
    try {
      await english.goto('/charge-types');
      const enRows = main(english).getByRole('listitem');
      for (const name of ['Membership fee', 'Electricity', 'Land tax', 'Water']) {
        await expect(enRows.filter({ hasText: name }).first()).toBeVisible();
      }
      await expect(enRows.filter({ hasText: 'Water' }).first()).toContainText('Inactive');
    } finally {
      await english.context().close();
    }
  });

  test('§2.1 #2, #4, #5, #6 add, duplicate refused, move up kept after reload, archive', async ({ page }) => {
    const stamp = Date.now().toString(36);
    const first = `E2E тип ${stamp} A`;
    const second = `E2E тип ${stamp} B`;
    const ids: string[] = [];
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      await page.goto('/charge-types');
      for (const name of [first, second]) {
        await main(page).getByRole('button', { name: 'Додати вид нарахування', exact: true }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByLabel('Назва', { exact: true }).fill(name);
        await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
        await expect(page.getByRole('dialog')).toHaveCount(0);
        const id = await typeIdByName(page, name);
        expect(id).toBeTruthy();
        ids.push(id!);
      }
      const rows = main(page).getByRole('listitem');
      // #2 both are at the end, active, in the order added
      await expect(rows.last()).toContainText(second);
      await expect(rows.last()).toContainText('Активний');

      // #4 the same name again, in other letter case: refused
      await main(page).getByRole('button', { name: 'Додати вид нарахування', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Назва', { exact: true }).fill(first.toUpperCase());
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog.getByText('Вид нарахування з такою назвою вже є')).toBeVisible();
      await dialog.getByRole('button', { name: 'Скасувати', exact: true }).click();

      // #5 the second moves up and stays above the first after reloading
      await rows.filter({ hasText: second }).getByRole('button', { name: 'Вгору', exact: true }).click();
      await expect(rows.nth(-2)).toContainText(second);
      await page.reload();
      await expect(rows.nth(-2)).toContainText(second);
      await expect(rows.last()).toContainText(first);

      // #6 archive with confirmation: gone from the list and not offered for a new tariff
      await rows.filter({ hasText: first }).getByRole('button', { name: 'Архівувати', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Архівувати', exact: true }).click();
      await expect(rows.filter({ hasText: first })).toHaveCount(0);
      await page.goto('/tariffs/new');
      await expect(main(page).getByLabel('Вид нарахування', { exact: true })).toBeVisible();
      await expect(main(page).getByRole('option', { name: first, exact: true })).toHaveCount(0);
      await expect(main(page).getByRole('option', { name: second, exact: true })).toHaveCount(1);
    } finally {
      for (const id of ids) await api(page, 'POST', `/charge-types/${id}/archive`).catch(() => undefined);
    }
  });

  test('§2.2 #2, #3, #4, #5, #6 version form: past-date warning, duplicate date, bad rate, fixed amount needs a due date', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E тип ${stamp}`;
    const tariffName = `E2E тариф ${stamp}`;
    let typeId: string | undefined;
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      typeId = await newType(page, typeName);
      const tariff = await newTariff(page, typeId, tariffName, {
        validFrom: '2027-01-01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'YEARLY',
        rate: '50',
      });
      await page.goto(`/tariffs/${tariff}`);
      await expect(page.getByRole('heading', { level: 1, name: tariffName })).toBeVisible();
      const form = (label: string | RegExp) => main(page).getByLabel(label);

      // #2 a version from 01.07.2027 at 60: newest first, the first one valid until 30.06.2027
      await form('Діє з').fill('2027-07-01');
      await form(/^Ставка/).fill('60');
      await main(page).getByRole('button', { name: 'Додати версію', exact: true }).click();
      const versions = main(page).locator('ol').getByRole('listitem');
      await expect(versions).toHaveCount(2);
      await expect(versions.first()).toContainText(/з 01\.07\.2027/);
      await expect(versions.first()).not.toContainText('по');
      await expect(versions.last()).toContainText(/по 30\.06\.2027/);

      // #4 the same date as an existing version
      await form('Діє з').fill('2027-07-01');
      await main(page).getByRole('button', { name: 'Додати версію', exact: true }).click();
      await expect(main(page).getByText('Версія з такою датою вже є')).toBeVisible();
      await expect(versions).toHaveCount(2);

      // #5 a rate with more than four decimals is refused
      await form('Діє з').fill('2027-09-01');
      await form(/^Ставка/).fill('4,32001');
      await main(page).getByRole('button', { name: 'Додати версію', exact: true }).click();
      await expect(
        main(page).getByText(/Ставка — невід.ємне число з не більше ніж 4 знаками після коми/),
      ).toBeVisible();
      await expect(versions).toHaveCount(2);

      // #6 a fixed one-time amount: one-off frequency that cannot be changed, no due date refused
      await form('Підстава нарахування').selectOption({ label: 'Фіксована разова сума' });
      await expect(form('Періодичність')).toBeDisabled();
      await expect(form('Періодичність')).toHaveValue('ONE_OFF');
      await form(/^Ставка/).fill('1200');
      await main(page).getByRole('button', { name: 'Додати версію', exact: true }).click();
      await expect(main(page).getByText('Вкажіть дату сплати')).toBeVisible();
      await expect(versions).toHaveCount(2);

      // #3 a version dated yesterday shows the warning before saving and can still be saved
      await form('Підстава нарахування').selectOption({ label: 'З ділянки' });
      await form('Періодичність').selectOption({ label: 'Щомісяця' });
      await form('Діє з').fill(kyivDay(-1));
      await expect(main(page).getByText('Нарахування за минулі періоди буде перераховано')).toBeVisible();
      await main(page).getByRole('button', { name: 'Додати версію', exact: true }).click();
      await expect(versions).toHaveCount(3);
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
    }
  });

  test('§2.3 #1-#4, §2.4 #5 scope editor: reason required, exclusion shown on the card, two selected plots, common land never offered', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E тип ${stamp}`;
    const tariffName = `E2E тариф ${stamp}`;
    const reason = 'звільнено рішенням загальних зборів';
    const plots: CreatedPlot[] = [];
    let typeId: string | undefined;
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      const a = await newPlot(page, `E2E-A-${stamp}`);
      const b = await newPlot(page, `E2E-B-${stamp}`);
      const c = await newPlot(page, `E2E-C-${stamp}`);
      const road = await newPlot(page, `E2E-R-${stamp}`, 'COMMON');
      plots.push(a, b, c, road);
      typeId = await newType(page, typeName);
      const tariff = await newTariff(page, typeId, tariffName, {
        validFrom: kyivDay(0).slice(0, 8) + '01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'YEARLY',
        rate: '50',
      });
      await page.goto(`/tariffs/${tariff}`);
      await main(page).getByRole('button', { name: 'Змінити', exact: true }).click();

      // #4 common land is not offered by the search; the unarchived ordinary plot is
      await main(page).getByLabel('Додати виняток').fill(road.number);
      await expect(main(page).getByText('Ділянок не знайдено')).toBeVisible();
      await main(page).getByLabel('Додати виняток').fill(a.number);
      await main(page)
        .getByRole('button', { name: new RegExp(`^${a.number}`) })
        .click();
      await expect(main(page).getByLabel('Причина')).toBeVisible();

      // #2 no reason: refused with "Вкажіть причину"
      await main(page).getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(main(page).getByText('Вкажіть причину').first()).toBeVisible();

      // #1 with a reason: saved, and the card of the plot shows "не нараховується" with the reason
      await main(page).getByLabel('Причина').fill(reason);
      await main(page).getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(main(page).getByText('Область дії збережено.')).toBeVisible();
      await page.goto(`/plots/${a.id}`);
      const onCard = main(page).getByRole('listitem').filter({ hasText: tariffName });
      await expect(onCard).toContainText('не нараховується');
      await expect(onCard).toContainText(reason);
      await page.goto(`/plots/${b.id}`);
      await expect(main(page).getByRole('listitem').filter({ hasText: tariffName })).toContainText(
        money('25.00'),
      );

      // #3 selected plots a and b: "2 ділянки" in the list, only those two show the tariff
      await page.goto(`/tariffs/${tariff}`);
      await main(page).getByRole('button', { name: 'Змінити', exact: true }).click();
      await main(page).getByText('Вибрані ділянки', { exact: true }).click();
      for (const plot of [a, b]) {
        await main(page).getByLabel('Додати ділянку').fill(plot.number);
        await main(page)
          .getByRole('button', { name: new RegExp(`^${plot.number}`) })
          .click();
      }
      await main(page).getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(main(page).getByText('Область дії збережено.')).toBeVisible();
      await page.goto('/tariffs');
      await expect(main(page).getByRole('listitem').filter({ hasText: tariffName }).last()).toContainText(
        '2 ділянки',
      );
      for (const plot of [a, b]) {
        await page.goto(`/plots/${plot.id}`);
        await expect(main(page).getByRole('listitem').filter({ hasText: tariffName })).toBeVisible();
      }
      await page.goto(`/plots/${c.id}`);
      await expect(main(page).getByRole('heading', { level: 1, name: `Ділянка ${c.number}` })).toBeVisible();
      await expect(main(page).getByText(tariffName)).toHaveCount(0);

      // §2.4 #5 common land: no tariffs section and no amounts
      await page.goto(`/plots/${road.id}`);
      await expect(
        main(page).getByRole('heading', { level: 1, name: `Спільні землі ${road.number}` }),
      ).toBeVisible();
      await expect(main(page).getByText(tariffName)).toHaveCount(0);
      await expect(main(page).getByText('Разом')).toHaveCount(0);
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
      await archiveLeftovers(page, plots, [], kyivDay(0));
    }
  });

  test('§2.1 empty list text and §2.4 #2, #3, #4, #6, §2.6 #2 the month picker changes the amounts', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E тип ${stamp}`;
    const plots: CreatedPlot[] = [];
    let typeId: string | undefined;
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      typeId = await newType(page, typeName);
      // §2.6 #1 a charge type without tariffs reads "Тарифів ще немає" and offers "Новий тариф"
      await page.goto('/tariffs');
      const panel = main(page).getByRole('listitem').filter({ hasText: typeName }).first();
      await expect(panel).toContainText('Тарифів ще немає');
      await expect(main(page).getByRole('link', { name: 'Новий тариф', exact: true })).toBeVisible();

      const members = await newTariff(page, typeId, `E2E внески ${stamp}`, {
        validFrom: '2026-01-01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'YEARLY',
        rate: '50',
      });
      expectCreated(
        await api(page, 'POST', `/tariffs/${members}/versions`, {
          validFrom: '2026-11-16',
          basis: 'PER_AREA_SOTKA',
          frequency: 'YEARLY',
          rate: '60',
        }),
      );
      await newTariff(page, typeId, `E2E дорога ${stamp}`, {
        validFrom: '2026-01-01',
        basis: 'FIXED_ONE_TIME',
        frequency: 'ONE_OFF',
        rate: '1200',
        dueDate: '2026-05-01',
      });
      await newTariff(page, typeId, `E2E лічильник ${stamp}`, {
        validFrom: '2026-01-01',
        basis: 'PER_KWH',
        frequency: 'MONTHLY',
        rate: '4.32',
      });
      const plot = await newPlot(page, `E2E-M-${stamp}`);
      plots.push(plot);

      await page.goto(`/plots/${plot.id}`);
      const month = main(page).getByLabel('Місяць');
      const row = (name: string) =>
        main(page)
          .getByRole('listitem')
          .filter({ hasText: `${name} ${stamp}` });
      // §2.4 #2 November 2026: 12.50 + 15.00 = 27.50
      await month.fill('2026-11');
      await expect(row('E2E внески')).toContainText(money('27.50'));
      // §2.6 #2 December 2026: the new rate for the whole month, 30.00
      await month.fill('2026-12');
      await expect(row('E2E внески')).toContainText(money('30.00'));
      // §2.4 #3 the road-repair style fee is 1 200.00 in May and 0.00 in June
      await month.fill('2026-05');
      await expect(row('E2E дорога')).toContainText(money('1200.00'));
      await month.fill('2026-06');
      await expect(row('E2E дорога')).toContainText(money('0.00'));
      // §2.4 #4 a per-kWh tariff reads "за показаннями" without an amount
      await expect(row('E2E лічильник')).toContainText('за показаннями');
      // §2.4 #6 before any version starts the tariff is not listed
      await month.fill('2025-12');
      await expect(row('E2E внески')).toHaveCount(0);
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
      await archiveLeftovers(page, plots, [], kyivDay(0));
    }
  });

  test('§2.5 #1-#5 in Ukrainian: the treasurer reads only, a member has no menu item and no page, "Що мені нараховуватимуть" shows own plots', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E тип ${stamp}`;
    let typeId: string | undefined;
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      typeId = await newType(page, typeName);
      const tariffName = `E2E тариф ${stamp}`;
      await newTariff(page, typeId, tariffName, {
        validFrom: '2026-01-01',
        basis: 'PER_PLOT',
        frequency: 'MONTHLY',
        rate: '10',
      });
      const foreign = await newPlot(page, `E2E-F-${stamp}`);
      plots.push(foreign);

      const treasurer = await otherPage(browser, 'treasurer@demo.local', 'uk');
      try {
        await treasurer.goto('/tariffs');
        await expect(treasurer.getByRole('heading', { level: 1, name: 'Тарифи' })).toBeVisible();
        await expect(main(treasurer).getByRole('link', { name: tariffName, exact: true })).toBeVisible();
        await expect(main(treasurer).getByRole('link', { name: 'Новий тариф', exact: true })).toHaveCount(0);
        await expect(main(treasurer).getByRole('link', { name: 'Види нарахувань', exact: true })).toHaveCount(
          0,
        );
        // a change sent anyway is refused and nothing changes
        const denied = await api(treasurer, 'POST', '/charge-types', { name: `E2E denied ${stamp}` });
        expect(denied.status).toBe(403);
        expect(await typeIdByName(page, `E2E denied ${stamp}`)).toBeUndefined();
      } finally {
        await treasurer.context().close();
      }

      const member = await otherPage(browser, 'member@demo.local', 'uk');
      try {
        await expect(menuLink(member, 'Тарифи')).toHaveCount(0);
        await member.goto('/tariffs');
        await expect(member.getByText(/немає доступу до цієї сторінки/i)).toBeVisible();
        await member.goto('/account');
        const mine = member.getByRole('region', { name: 'Що мені нараховуватимуть' });
        await expect(mine).toBeVisible();
        await expect(mine.getByRole('heading', { level: 2, name: /^Ділянка \S+$/ }).first()).toBeVisible();
        await expect(mine).not.toContainText(foreign.number);
        expect((await api(member, 'GET', `/plots/${foreign.id}/tariffs`)).status).toBe(404);
      } finally {
        await member.context().close();
      }
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
      await archiveLeftovers(page, plots, [], kyivDay(0));
    }
  });

  test('§2.7 #1 the audit log shows the rename, the new version and the exclusion with the author, old and new values and links', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const oldName = `E2E тип ${stamp}`;
    const newName = `E2E тип ${stamp} нова назва`;
    const tariffName = `E2E тариф ${stamp}`;
    const reason = `звільнено ${stamp}`;
    const plots: CreatedPlot[] = [];
    let typeId: string | undefined;
    await signIn(page, 'chairman@demo.local', 'uk');
    try {
      typeId = await newType(page, oldName);
      const tariff = await newTariff(page, typeId, tariffName, {
        validFrom: '2027-01-01',
        basis: 'PER_AREA_SOTKA',
        frequency: 'YEARLY',
        rate: '50',
      });
      const plot = await newPlot(page, `E2E-AU-${stamp}`);
      plots.push(plot);
      const me = expectCreated<{ fullName: string }>(await api(page, 'GET', '/me'), 200);

      expect(
        (await api(page, 'PUT', `/charge-types/${typeId}`, { name: newName, active: true })).status,
      ).toBe(200);
      expectCreated(
        await api(page, 'POST', `/tariffs/${tariff}/versions`, {
          validFrom: '2027-07-01',
          basis: 'PER_AREA_SOTKA',
          frequency: 'YEARLY',
          rate: '60',
        }),
      );
      expect(
        (
          await api(page, 'PUT', `/tariffs/${tariff}/scope`, {
            kind: 'ALL_PLOTS',
            exclusions: [{ plotId: plot.id, reason }],
          })
        ).status,
      ).toBe(200);

      await page.goto('/audit');
      const rows = main(page).getByRole('row');
      const renamed = rows.filter({ hasText: newName }).filter({ hasText: 'Вид нарахування змінено' });
      await expect(renamed).toHaveCount(1);
      await expect(renamed).toContainText(me.fullName);
      await expect(renamed).toContainText(`${oldName} → ${newName}`);
      await expect(renamed.getByRole('link')).toHaveAttribute('href', '/charge-types');

      const version = rows.filter({ hasText: tariffName }).filter({ hasText: 'Версію тарифу додано' });
      await expect(version).toHaveCount(1);
      await expect(version).toContainText(me.fullName);
      await expect(version).toContainText('01.07.2027');
      await expect(version.getByRole('link')).toHaveAttribute('href', `/tariffs/${tariff}`);

      const excluded = rows.filter({ hasText: tariffName }).filter({ hasText: 'Виняток з тарифу додано' });
      await expect(excluded).toHaveCount(1);
      await expect(excluded).toContainText(me.fullName);
      await expect(excluded).toContainText(plot.number);
      await expect(excluded).toContainText(reason);
      await expect(excluded.getByRole('link')).toHaveAttribute('href', `/tariffs/${tariff}`);
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
      await archiveLeftovers(page, plots, [], kyivDay(0));
    }
  });
});
