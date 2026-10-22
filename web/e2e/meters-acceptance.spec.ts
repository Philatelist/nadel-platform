// @layer: e2e
// @spec: context/spec/010-meters-and-readings
// @regression
// Acceptance specs of the whole feature "Meters and readings" (functional-spec.md of 010) that meters.spec.ts does not
// cover, read in the Ukrainian interface (one language at a time; texts are the spec's own). Criteria are named §2.x #n.
// Every test CREATES its own plots (unique street and number), meters, owners and, where needed, a charge type, and
// cleans up in `finally`: counted readings and meters are deleted, a meter that has to stay (pending or rejected
// readings, replacements) is removed with a final reading, the plot is archived and the charge type archived. The demo
// meters and the two demo quarters are only read (a test that moves a fixture meter between the demo quarters closes
// the placement and deletes the meter again). Demo plots are never changed.
import { expect as baseExpect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, archiveLeftovers, expectCreated, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';
import { xlsx } from './support/xlsx';

// The shared dev stack can be slow under load: a longer wait for each assertion and for each spec.
const expect = baseExpect.configure({ timeout: 15_000 });
test.describe.configure({ timeout: 180_000 });

const uk = messages('uk');
const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });
/** A day as the Ukrainian UI prints it: "21.10.2026". */
const dayUk = (iso: string) => iso.split('-').reverse().join('.');

async function signIn(page: Page, email: string): Promise<void> {
  await page.addInitScript(() => {
    try {
      window.localStorage.setItem('nadel.locale', 'uk');
    } catch {
      // storage unavailable: Ukrainian is the default anyway
    }
  });
  await loginAs(page, email, { keepLocale: true });
}

async function otherPage(
  browser: Browser,
  email: string,
  viewport?: { width: number; height: number },
): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US', viewport });
  const page = await context.newPage();
  await signIn(page, email);
  return page;
}

async function memberIdOf(browser: Browser, email: string): Promise<string> {
  const member = await otherPage(browser, email);
  try {
    return expectCreated<{ memberId: string }>(await api(member, 'GET', '/me'), 200).memberId;
  } finally {
    await member.context().close();
  }
}

interface Fixture {
  plot: CreatedPlot;
  street: string;
  serial: string;
  meterId: string;
}

/** A plot on a unique street (optionally owned) with an electricity meter installed `daysAgo` days ago. */
async function createFixture(
  chairman: Page,
  stamp: string,
  options: { ownerId?: string; daysAgo?: number; initial?: string; street?: string; meter?: boolean } = {},
): Promise<Fixture> {
  const street = options.street ?? `E2E Street ${stamp}`;
  const number = `E2E-M-${stamp}`;
  const serial = `E2E-SN-${stamp}`;
  const created = expectCreated<{ id: string }>(
    await api(chairman, 'POST', '/plots', { number, kind: 'PLOT', areaM2: 400, street }),
  );
  const plot = { id: created.id, number };
  try {
    if (options.ownerId) {
      expectCreated(
        await api(chairman, 'POST', `/plots/${plot.id}/ownership-changes`, {
          effectiveFrom: kyivDay(-100),
          owners: [{ memberId: options.ownerId, share: '1/1' }],
        }),
      );
    }
    let meterId = '';
    if (options.meter !== false) {
      meterId = expectCreated<{ id: string }>(
        await api(chairman, 'POST', '/meters', {
          kind: 'PLOT',
          resource: 'ELECTRICITY',
          plotId: plot.id,
          serial,
          installedOn: kyivDay(-(options.daysAgo ?? 30)),
          initialReading: options.initial ?? '100.000',
        }),
      ).id;
    }
    return { plot, street, serial, meterId };
  } catch (error) {
    await archiveLeftovers(chairman, [plot], [], kyivDay(0));
    throw error;
  }
}

interface MeterSummary {
  id: string;
  active: boolean;
  lastReading: { value: string };
}

/**
 * Best effort: for each meter of each plot rejects the pending readings, deletes the readings that can be deleted and
 * the meter; a meter that has to stay is removed with its last reading as the final one; then ends the ownership and
 * archives the plot.
 */
async function cleanup(chairman: Page, plots: CreatedPlot[]): Promise<void> {
  for (const plot of plots) {
    const listed = await api(chairman, 'GET', `/plots/${plot.id}/meters`).catch(() => undefined);
    if (listed?.status !== 200) continue;
    const meters = JSON.parse(listed.body) as MeterSummary[];
    for (const meter of [...meters].reverse()) {
      const history = await api(chairman, 'GET', `/meters/${meter.id}/readings`).catch(() => undefined);
      if (history?.status === 200) {
        const readings = (JSON.parse(history.body) as { readings: { id: string; status: string }[] })
          .readings;
        for (const r of readings) {
          if (r.status === 'PENDING')
            await api(chairman, 'POST', `/readings/${r.id}/reject`, { reason: 'E2E cleanup' }).catch(
              () => undefined,
            );
          if (r.status === 'APPROVED')
            await api(chairman, 'DELETE', `/readings/${r.id}`).catch(() => undefined);
        }
      }
      const deleted = await api(chairman, 'DELETE', `/meters/${meter.id}`).catch(() => undefined);
      if (deleted?.status === 204 || !meter.active) continue;
      const current = await api(chairman, 'GET', `/meters/${meter.id}`).catch(() => undefined);
      const last =
        current?.status === 200 ? (JSON.parse(current.body) as MeterSummary).lastReading.value : '0';
      await api(chairman, 'POST', `/meters/${meter.id}/remove`, {
        removedOn: kyivDay(0),
        finalReading: last,
      }).catch(() => undefined);
    }
  }
  await archiveLeftovers(chairman, plots, [], kyivDay(0));
}

/** Leftovers of an interrupted earlier run would show up in the registers: clean them first. */
async function cleanLeftovers(chairman: Page): Promise<void> {
  const found = expectCreated<{ items: { id: string; number: string }[] }>(
    await api(chairman, 'GET', '/plots?q=E2E-M-'),
    200,
  );
  await cleanup(
    chairman,
    found.items.filter((p) => p.number.startsWith('E2E-M-')),
  );
}

test.describe('meters and readings: acceptance', () => {
  test.beforeAll(async ({ browser }) => {
    const chairman = await otherPage(browser, 'chairman@demo.local');
    try {
      await cleanLeftovers(chairman);
    } finally {
      await chairman.context().close();
    }
  });

  test('§2.6 #1, §2.4 #1 #2, §2.7 #1: the electrician sees only "Покази" and "Лічильники", the hint names today, one row has its own date, the phone needs no sideways scroll', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const street = `E2E Street ${stamp}`;
    await signIn(page, 'chairman@demo.local');
    try {
      const first = await createFixture(page, `${stamp}a`, { street });
      plots.push(first.plot);
      const second = await createFixture(page, `${stamp}b`, { street });
      plots.push(second.plot);
      const today = kyivDay(0);

      const electrician = await otherPage(browser, 'electrician@demo.local');
      try {
        await expect(electrician).toHaveURL(/\/readings$/);
        await expect(electrician.getByRole('heading', { level: 1, name: 'Покази' })).toBeVisible();
        await expect(menuLink(electrician, 'Покази')).toBeVisible();
        await expect(menuLink(electrician, 'Лічильники')).toBeVisible();
        const links = await electrician.locator('#app-menu nav').getByRole('link').allTextContents();
        expect(links.map((l) => l.trim())).toEqual(['Покази', 'Лічильники']);

        // the quiet hint, not a dialog
        await expect(
          main(electrician).getByText(
            `Дата показу — сьогодні, ${dayUk(today)}. Змініть, якщо знімали в інший день`,
          ),
        ).toBeVisible();
        await expect(electrician.getByRole('dialog')).toHaveCount(0);
        await expect(main(electrician).getByLabel('Дата обходу')).toHaveValue(today);

        await main(electrician).getByLabel('Вулиця', { exact: true }).fill(street);
        const cards = main(electrician)
          .getByRole('list', { name: 'Лічильники обходу' })
          .getByRole('listitem');
        await expect(cards).toHaveCount(2);
        const rowOf = (f: Fixture) => cards.filter({ hasText: `Ділянка ${f.plot.number}` });

        // the first row keeps the round date and is saved with today's date
        await rowOf(first).getByLabel('Показ, кВт·год', { exact: true }).fill('130');
        await rowOf(first).getByRole('button', { name: 'Зберегти', exact: true }).click();
        await expect(rowOf(first).getByText('Збережено', { exact: true })).toBeVisible();
        await expect(rowOf(first)).toContainText(
          `Інтервал ${dayUk(kyivDay(-30))} – ${dayUk(today)}: 30 кВт·год за 30 дн.`,
        );
        // the second row gets its own date: only that reading differs
        const eighteen = kyivDay(-3);
        await rowOf(second).getByLabel('Дата показу цього лічильника').fill(eighteen);
        await rowOf(second).getByLabel('Показ, кВт·год', { exact: true }).fill('120');
        await rowOf(second).getByRole('button', { name: 'Зберегти', exact: true }).click();
        await expect(rowOf(second).getByText('Збережено', { exact: true })).toBeVisible();

        const dates = async (f: Fixture) =>
          expectCreated<{ readings: { takenOn: string; status: string }[] }>(
            await api(page, 'GET', `/meters/${f.meterId}/readings`),
            200,
          )
            .readings.filter((r) => r.status === 'APPROVED')
            .map((r) => r.takenOn);
        expect(await dates(first)).toEqual([today]);
        expect(await dates(second)).toEqual([eighteen]);
      } finally {
        await electrician.context().close();
      }

      // §2.7 #1 at phone width every row can be read and filled without scrolling sideways
      const phone = await otherPage(browser, 'electrician@demo.local', { width: 375, height: 800 });
      try {
        await phone.goto(`/readings?street=${encodeURIComponent(street)}`);
        const card = main(phone)
          .getByRole('list', { name: 'Лічильники обходу' })
          .getByRole('listitem')
          .first();
        await expect(card).toBeVisible();
        const overflow = await phone.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        );
        expect(overflow, 'horizontal overflow in px').toBeLessThanOrEqual(0);
        for (const control of [
          card.getByLabel('Показ, кВт·год', { exact: true }),
          card.getByLabel('Дата показу цього лічильника'),
          card.getByRole('button', { name: 'Зберегти', exact: true }),
        ]) {
          await expect(control).toBeVisible();
          const box = await control.boundingBox();
          expect(box && box.x >= 0 && box.x + box.width <= 375, 'the control is inside the viewport').toBe(
            true,
          );
        }
      } finally {
        await phone.context().close();
      }
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.1 #1 #2 #4 #5 #6: register a plot meter, a second one is refused, a common consumer is listed by name, 4 decimals are refused, only a meter without readings is deleted', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local');
    let consumerId: string | undefined;
    try {
      const fixture = await createFixture(page, stamp, { meter: false });
      plots.push(fixture.plot);
      const serial = `E2E-ЕМ-${stamp}`;
      const register = async (resource?: string) => {
        await main(page).getByRole('button', { name: 'Зареєструвати лічильник' }).click();
        const dialog = page.getByRole('dialog', { name: 'Реєстрація лічильника' });
        if (resource) await dialog.getByLabel('Ресурс', { exact: true }).selectOption({ label: resource });
        await dialog.getByLabel('Знайдіть ділянку за номером').fill(fixture.plot.number);
        await dialog.getByRole('button', { name: new RegExp(fixture.plot.number) }).click();
        return dialog;
      };

      await page.goto('/meters');
      let dialog = await register();
      await dialog.getByLabel('Серійний номер', { exact: true }).fill(serial);
      await dialog.getByLabel('Дата встановлення').fill(kyivDay(-30));
      await dialog.getByLabel('Початковий показ', { exact: true }).fill('8000');
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog).toHaveCount(0);

      // the register lists it under the plot as active
      await page.goto(`/meters?street=${encodeURIComponent(fixture.street)}`);
      const row = main(page).locator('tbody tr').filter({ hasText: serial });
      await expect(row).toContainText(fixture.plot.number);
      await expect(row).toContainText('Працює');
      await expect(row).toContainText('Електроенергія');
      await expect(row).toContainText(/8\s?000 кВт·год/);

      // §2.1 #2 a second active electricity meter on the plot is refused
      dialog = await register();
      await dialog.getByLabel('Серійний номер', { exact: true }).fill(`${serial}-2`);
      await dialog.getByLabel('Початковий показ', { exact: true }).fill('1');
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog).toContainText('На ділянці вже є активний лічильник цього ресурсу');
      // §2.1 #5 a reading with four digits after the decimal point is refused before anything is sent
      await dialog.getByLabel('Початковий показ', { exact: true }).fill('8000,1234');
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog).toContainText('Показ — невід’ємне число з не більше ніж 3 знаками після коми');
      await dialog.getByRole('button', { name: 'Скасувати' }).click();
      const onPlot = expectCreated<unknown[]>(
        await api(page, 'GET', `/plots/${fixture.plot.id}/meters`),
        200,
      );
      expect(onPlot).toHaveLength(1);

      // §2.1 #4 a common consumer appears with its name
      const name = `E2E Свердловина ${stamp}`;
      await main(page).getByRole('button', { name: 'Зареєструвати лічильник' }).click();
      dialog = page.getByRole('dialog', { name: 'Реєстрація лічильника' });
      await dialog.getByLabel('Вид лічильника').selectOption({ label: 'Спільний споживач' });
      await dialog.getByLabel('Серійний номер', { exact: true }).fill(`E2E-CC-${stamp}`);
      await dialog.getByLabel('Назва', { exact: true }).fill(name);
      await dialog.getByLabel('Початковий показ', { exact: true }).fill('10');
      await dialog.getByRole('button', { name: 'Зберегти', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await page.goto('/meters?kind=COMMON_CONSUMER');
      await expect(main(page).locator('tbody tr').filter({ hasText: name })).toContainText(
        'Спільний споживач',
      );
      consumerId = (
        JSON.parse((await api(page, 'GET', '/meters?kind=COMMON_CONSUMER')).body) as {
          id: string;
          name?: string;
        }[]
      ).find((m) => m.name === name)?.id;

      // §2.1 #6 a meter without readings is deleted after a confirmation ...
      await main(page).getByRole('link', { name }).click();
      await main(page).getByRole('button', { name: 'Видалити', exact: true }).click();
      await page
        .getByRole('dialog', { name: 'Видалити лічильник?' })
        .getByRole('button', { name: 'Видалити' })
        .click();
      await expect(page).toHaveURL(/\/meters$/);
      await expect(main(page).locator('tbody tr').filter({ hasText: name })).toHaveCount(0);
      consumerId = undefined;

      // ... and one with readings has no delete action and a delete request is refused
      const meterId = onPlot[0] as { id: string } | undefined;
      expect(meterId).toBeDefined();
      expectCreated(
        await api(page, 'POST', '/readings', {
          rows: [{ meterId: (meterId as { id: string }).id, takenOn: kyivDay(-5), value: '8010' }],
        }),
        200,
      );
      await page.goto(`/meters/${(meterId as { id: string }).id}`);
      await expect(main(page).getByText(serial).first()).toBeVisible();
      await expect(main(page).getByRole('button', { name: 'Видалити', exact: true })).toHaveCount(0);
      const refused = await api(page, 'DELETE', `/meters/${(meterId as { id: string }).id}`);
      expect(refused.status).toBe(409);
      expect((JSON.parse(refused.body) as { code: string }).code).toBe('METER_HAS_READINGS');
      expect(uk.problem.METER_HAS_READINGS).toBe('Лічильник має покази — зніміть його замість видалення');
    } finally {
      if (consumerId) await api(page, 'DELETE', `/meters/${consumerId}`).catch(() => undefined);
      await cleanup(page, plots);
    }
  });

  test('§2.1 #7 #8, §2.3 #1 #2: removal keeps the readings and frees the plot, a plot with a meter is not archived, a replacement and a wrap-around are one action', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local');
    try {
      // §2.1 #8 a plot with an active meter is not archived
      const removed = await createFixture(page, `${stamp}r`);
      plots.push(removed.plot);
      expectCreated(
        await api(page, 'POST', '/readings', {
          rows: [{ meterId: removed.meterId, takenOn: kyivDay(-10), value: '150' }],
        }),
        200,
      );
      await page.goto(`/plots/${removed.plot.id}`);
      await main(page).getByRole('button', { name: 'Архівувати', exact: true }).click();
      await expect(main(page)).toContainText('Спершу зніміть лічильник ділянки');

      // §2.1 #7 removed with a final reading: shown as removed, keeps its readings, the plot gets a new meter
      await page.goto(`/meters/${removed.meterId}`);
      await main(page).getByRole('button', { name: 'Зняти', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Зняти лічильник' });
      await dialog.getByLabel('Дата зняття').fill(kyivDay(-1));
      await dialog.getByLabel('Кінцевий показ, кВт·год').fill('420');
      await dialog.getByRole('button', { name: 'Зняти лічильник' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(main(page).getByText('Знято', { exact: true }).first()).toBeVisible();
      await expect(main(page)).toContainText(dayUk(kyivDay(-1)));
      await expect(main(page).getByRole('table').getByText('враховано')).toBeVisible();
      expectCreated(
        await api(page, 'POST', '/meters', {
          kind: 'PLOT',
          resource: 'ELECTRICITY',
          plotId: removed.plot.id,
          serial: `${removed.serial}-new`,
          installedOn: kyivDay(0),
          initialReading: '0',
        }),
      );
      await page.goto(`/plots/${removed.plot.id}`);
      const panel = main(page).getByRole('region', { name: 'Лічильники' }).or(main(page));
      await expect(panel).toContainText(`Працював ${dayUk(kyivDay(-30))} – ${dayUk(kyivDay(-1))}`);
      await expect(panel).toContainText(`Працює з ${dayUk(kyivDay(0))}`);

      // §2.3 #1 one action: the old meter is removed on the date, the new one active from it, both in the plot card
      const replaced = await createFixture(page, `${stamp}p`);
      plots.push(replaced.plot);
      expectCreated(
        await api(page, 'POST', '/readings', {
          rows: [{ meterId: replaced.meterId, takenOn: kyivDay(-20), value: '140' }],
        }),
        200,
      );
      await page.goto(`/meters/${replaced.meterId}`);
      await main(page).getByRole('button', { name: 'Замінити лічильник', exact: true }).click();
      const replace = page.getByRole('dialog', { name: 'Заміна лічильника' });
      await replace.getByLabel('Дата заміни').fill(kyivDay(-10));
      await replace.getByLabel('Кінцевий показ старого лічильника, кВт·год').fill('205');
      await replace.getByLabel('Серійний номер нового лічильника').fill(`${replaced.serial}-new`);
      await replace.getByLabel('Початковий показ нового лічильника, кВт·год').fill('3');
      await replace.getByRole('button', { name: 'Замінити', exact: true }).click();
      await expect(page).toHaveURL(/\/meters\/[0-9a-f-]+$/);
      await expect(page).not.toHaveURL(new RegExp(replaced.meterId));
      await expect(main(page).getByText(`${replaced.serial}-new`).first()).toBeVisible();
      await page.goto(`/plots/${replaced.plot.id}`);
      await expect(main(page)).toContainText(`Працював ${dayUk(kyivDay(-30))} – ${dayUk(kyivDay(-10))}`);
      await expect(main(page)).toContainText(`Працює з ${dayUk(kyivDay(-10))}`);
      await expect(main(page)).toContainText('Замінив попередній лічильник');

      // §2.3 #2 a wrap-around is a replacement of the meter by itself: same serial, final 99 999, initial 0
      const wrapped = await createFixture(page, `${stamp}w`, { initial: '99000' });
      plots.push(wrapped.plot);
      await page.goto(`/meters/${wrapped.meterId}`);
      await main(page).getByRole('button', { name: 'Замінити лічильник', exact: true }).click();
      const wrap = page.getByRole('dialog', { name: 'Заміна лічильника' });
      await wrap.getByRole('button', { name: 'Заповнити як прокрутку' }).click();
      await expect(wrap.getByLabel('Серійний номер нового лічильника')).toHaveValue(wrapped.serial);
      await wrap.getByLabel('Дата заміни').fill(kyivDay(-5));
      await wrap.getByLabel('Кінцевий показ старого лічильника, кВт·год').fill('99999');
      await expect(wrap.getByLabel('Початковий показ нового лічильника, кВт·год')).toHaveValue('0');
      await wrap.getByRole('button', { name: 'Замінити', exact: true }).click();
      await expect(page).not.toHaveURL(new RegExp(wrapped.meterId));
      await expect(page).toHaveURL(/\/meters\/[0-9a-f-]+$/);
      const both = expectCreated<{ serial: string; active: boolean; initialReading: string }[]>(
        await api(page, 'GET', `/plots/${wrapped.plot.id}/meters`),
        200,
      );
      expect(both.map((m) => [m.serial, m.active, m.initialReading])).toEqual([
        [wrapped.serial, true, '0.000'],
        [wrapped.serial, false, '99000.000'],
      ]);
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.2 #1 #2 #3 #4, §2.5: the meter tree moves a plot between the demo quarters on a date, splits the range, marks calculated values and shows "немає показу після"', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    let meterId: string | undefined;
    await signIn(page, 'chairman@demo.local');
    try {
      const fixture = await createFixture(page, stamp, { daysAgo: 30, initial: '100' });
      plots.push(fixture.plot);
      meterId = fixture.meterId;
      // 10 kWh a day between the two readings: the value on the day of the move is exactly 300, calculated
      for (const [takenOn, value] of [
        [kyivDay(-20), '200'],
        [kyivDay(-2), '380'],
      ] as const)
        expectCreated(
          await api(page, 'POST', '/readings', { rows: [{ meterId: fixture.meterId, takenOn, value }] }),
          200,
        );

      await page.goto('/settings/meter-tree');
      await expect(page.getByRole('heading', { level: 1, name: 'Дерево лічильників' })).toBeVisible();
      await main(page).getByLabel('З', { exact: true }).fill(kyivDay(-20));
      await main(page).getByLabel('По', { exact: true }).fill(kyivDay(0));
      // the plot's meter as shown under each of its places in the tree (removed meters of earlier runs may share dates)
      const places = main(page)
        .getByRole('listitem')
        .filter({ hasText: fixture.serial })
        .filter({ hasNotText: 'Квартал' });

      // §2.2 #1 the plot's meter goes under "Квартал А" from its installation date
      const move = async (target: string, from: string) => {
        await places.last().getByRole('button', { name: 'Перемістити' }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByLabel('Батьківський лічильник').selectOption({ label: target });
        await dialog.getByLabel('Діє з').fill(from);
        await dialog.getByRole('button', { name: 'Перемістити', exact: true }).click();
        await expect(dialog).toHaveCount(0);
      };
      await move('Квартал А', kyivDay(-30));
      const quarterOf = (name: string) =>
        main(page).getByRole('listitem').filter({ hasText: name }).filter({ hasText: fixture.serial }).last();
      await expect(quarterOf('Квартал А')).toContainText(`Ділянка ${fixture.plot.number}`);

      // §2.2 #2 a quarter meter is refused under another quarter meter
      await main(page)
        .getByRole('listitem')
        .filter({ hasText: 'Квартал Б' })
        .filter({ hasNotText: fixture.serial })
        .last()
        .getByRole('button', { name: 'Перемістити' })
        .first()
        .click();
      const refusal = page.getByRole('dialog');
      await refusal.getByLabel('Батьківський лічильник').selectOption({ label: 'Квартал А' });
      await refusal.getByRole('button', { name: 'Перемістити', exact: true }).click();
      await expect(refusal).toContainText('Квартальний лічильник не може бути під іншим квартальним');
      await refusal.getByRole('button', { name: 'Скасувати' }).click();

      // §2.2 #3 moved to "Квартал Б" ten days ago: each part of the range is under its quarter. The part under
      // "Квартал А" ends on the day of the move between two readings, so it is calculated and marked
      await move('Квартал Б', kyivDay(-10));
      await expect(places).toHaveCount(2);
      const underA = places.first();
      await expect(underA).toContainText(`${dayUk(kyivDay(-20))} – ${dayUk(kyivDay(-10))}:`);
      await expect(underA).toContainText('Споживання: 100 кВт·год');
      await expect(underA).toContainText('розрахунковий');
      // the part under "Квартал Б" ends after the last reading: no number, only where the readings end
      const underB = places.last();
      await expect(underB).toContainText(`${dayUk(kyivDay(-10))} – ${dayUk(kyivDay(0))}:`);
      await expect(underB).toContainText(`немає показу після ${dayUk(kyivDay(-2))}`);
      await expect(underB).not.toContainText('Споживання:');

      // §2.2 #4 a past range before the move shows the plot under "Квартал А" only
      await main(page).getByLabel('По', { exact: true }).fill(kyivDay(-11));
      await expect(places).toHaveCount(1);
      await expect(quarterOf('Квартал А')).toContainText(`Ділянка ${fixture.plot.number}`);
      await expect(
        main(page).getByRole('listitem').filter({ hasText: 'Квартал Б' }).last(),
      ).not.toContainText(fixture.serial);

      // §2.5 a range that ends on a reading: 300 (calculated on the day of the move) to 380 (a reading) is 80
      await main(page).getByLabel('По', { exact: true }).fill(kyivDay(-2));
      await expect(places).toHaveCount(2);
      await expect(places.last()).toContainText('Споживання: 80 кВт·год');
    } finally {
      if (meterId) {
        // closes the placement in the tree, then the meter can be cleaned like the others
        await api(page, 'PUT', `/meters/${meterId}/parent`, { effectiveFrom: kyivDay(0) }).catch(
          () => undefined,
        );
      }
      await cleanup(page, plots);
    }
  });

  test('§2.4 #9 #10, §2.6 #4: an owner submits a reading (pending, not counted), the chairman approves it and rejects another with a reason the owner sees', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local');
    try {
      const fixture = await createFixture(page, stamp, {
        ownerId: await memberIdOf(browser, 'member@demo.local'),
        daysAgo: 40,
        initial: '100',
      });
      plots.push(fixture.plot);
      const today = kyivDay(0);
      const member = await otherPage(browser, 'member@demo.local');
      try {
        await member.goto('/account');
        const mine = main(member).locator('section[aria-labelledby="my-meters"]');
        await expect(mine.getByRole('heading', { level: 2, name: 'Мої лічильники' })).toBeVisible();
        const card = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
        await card.getByRole('button', { name: 'Передати показ', exact: true }).click();
        const dialog = member.getByRole('dialog');
        // the date is filled with today and carries the same quiet hint
        await expect(dialog.getByLabel('Дата показу', { exact: true })).toHaveValue(today);
        await expect(
          dialog.getByText(`Дата показу — сьогодні, ${dayUk(today)}. Змініть, якщо знімали в інший день`),
        ).toBeVisible();
        await dialog.getByLabel('Показ, кВт·год').fill('120');
        await dialog.getByRole('button', { name: 'Передати показ', exact: true }).click();
        await expect(dialog).toHaveCount(0);
        await expect(card.getByText('Показ передано, він очікує підтвердження')).toBeVisible();
        await expect(card.getByText('на підтвердженні', { exact: true })).toBeVisible();

        // the consumption does not change until it is approved
        const before = expectCreated<{ intervals: unknown[] }>(
          await api(page, 'GET', `/meters/${fixture.meterId}/readings`),
          200,
        );
        expect(before.intervals).toHaveLength(0);

        await page.goto('/readings/approvals');
        const item = main(page)
          .getByRole('list', { name: 'Покази, передані власниками' })
          .getByRole('listitem')
          .filter({ hasText: `Ділянка ${fixture.plot.number}` });
        await expect(item).toContainText(`Показ 120 кВт·год на ${dayUk(today)}`);
        await expect(item).toContainText('Після підтвердження: 20 кВт·год за 40 дн.');
        await item.getByRole('button', { name: 'Підтвердити', exact: true }).click();
        await expect(item).toHaveCount(0);
        const after = expectCreated<{ intervals: { consumption: string; to: string }[] }>(
          await api(page, 'GET', `/meters/${fixture.meterId}/readings`),
          200,
        );
        expect(after.intervals.map((i) => [i.to, i.consumption])).toEqual([[today, '20.000']]);

        // a second submission is rejected with a reason, which the owner sees as "відхилено"
        await member.reload();
        const again = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
        await again.getByRole('button', { name: 'Передати показ', exact: true }).click();
        await member.getByRole('dialog').getByLabel('Дата показу', { exact: true }).fill(kyivDay(-1));
        await member.getByRole('dialog').getByLabel('Показ, кВт·год').fill('119');
        await member.getByRole('dialog').getByRole('button', { name: 'Передати показ', exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);

        await page.goto('/readings/approvals');
        const second = main(page)
          .getByRole('list', { name: 'Покази, передані власниками' })
          .getByRole('listitem')
          .filter({ hasText: `Ділянка ${fixture.plot.number}` });
        await second.getByRole('button', { name: 'Відхилити', exact: true }).click();
        const reject = page.getByRole('dialog', { name: 'Відхилення показу' });
        await reject.getByRole('button', { name: 'Відхилити показ' }).click();
        await expect(reject).toContainText('Вкажіть причину');
        await reject.getByLabel('Причина').fill('Фото нечітке, передайте ще раз');
        await reject.getByRole('button', { name: 'Відхилити показ' }).click();
        await expect(second).toHaveCount(0);

        await member.reload();
        const rejected = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
        await expect(rejected.getByText('відхилено', { exact: true })).toBeVisible();
        await expect(rejected).toContainText('Причина відхилення: Фото нечітке, передайте ще раз');
      } finally {
        await member.context().close();
      }
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.6 #2 #3 #5: the treasurer reads without actions and is refused with the permission text, a member sees only own meters, a user of another cooperative has no access', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local');
    try {
      const own = await createFixture(page, `${stamp}a`, {
        ownerId: await memberIdOf(browser, 'member@demo.local'),
      });
      plots.push(own.plot);
      const other = await createFixture(page, `${stamp}b`);
      plots.push(other.plot);

      const treasurer = await otherPage(browser, 'treasurer@demo.local');
      try {
        await menuLink(treasurer, 'Лічильники').click();
        await expect(treasurer.getByRole('heading', { level: 1, name: 'Лічильники' })).toBeVisible();
        await expect(main(treasurer).getByRole('button', { name: 'Зареєструвати лічильник' })).toHaveCount(0);
        await main(treasurer).getByLabel('Вулиця', { exact: true }).fill(other.street);
        await main(treasurer).getByRole('link', { name: other.serial }).click();
        await expect(main(treasurer).getByText(other.serial).first()).toBeVisible();
        for (const action of ['Редагувати', 'Замінити лічильник', 'Зняти', 'Видалити'])
          await expect(main(treasurer).getByRole('button', { name: action, exact: true })).toHaveCount(0);
        await treasurer.goto('/settings/meter-tree');
        await expect(treasurer.getByRole('alert')).toContainText('У вас немає доступу до цієї сторінки');
        // changes are refused with the permission text
        const refused = await api(treasurer, 'POST', `/meters/${other.meterId}/remove`, {
          removedOn: kyivDay(0),
          finalReading: '100',
        });
        expect(refused.status).toBe(403);
        expect((JSON.parse(refused.body) as { code: string }).code).toBe('PERMISSION_DENIED');
        expect(uk.problem.PERMISSION_DENIED).toBe('У вас немає дозволу на цю дію.');
        expect((await api(treasurer, 'GET', `/meters/${other.meterId}`)).status).toBe(200);
      } finally {
        await treasurer.context().close();
      }

      const member = await otherPage(browser, 'member@demo.local');
      try {
        await member.goto('/account');
        const mine = main(member).locator('section[aria-labelledby="my-meters"]');
        await expect(mine.getByRole('heading', { level: 2, name: 'Мої лічильники' })).toBeVisible();
        await expect(mine.getByText(own.serial)).toBeVisible();
        await expect(mine.getByText(other.serial)).toHaveCount(0);
        // another plot's meters are "not found" for a member
        const notFound = await api(member, 'GET', `/plots/${other.plot.id}/meters`);
        expect(notFound.status).toBe(404);
        expect((JSON.parse(notFound.body) as { code: string }).code).toBe('PLOT_NOT_FOUND');
        expect(uk.problem.PLOT_NOT_FOUND).toBe('Ділянку не знайдено.');
        expect((await api(member, 'GET', `/plots/${own.plot.id}/meters`)).status).toBe(200);
      } finally {
        await member.context().close();
      }

      // §2.6 #5 a user of another cooperative gets "not found" for this cooperative's meter
      const foreign = await otherPage(browser, 'member@other.local');
      try {
        expect((await api(foreign, 'GET', `/meters/${own.meterId}`)).status).toBe(403);
        expect((await api(foreign, 'GET', `/plots/${own.plot.id}/meters`)).status).toBe(404);
        expect((await api(foreign, 'GET', `/meters/${own.meterId}/readings`)).status).toBe(404);
      } finally {
        await foreign.context().close();
      }
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.7 #2 #3: "Без показу з" lists only meters not read for 60 days and never a plot without a meter; a per-kWh tariff reads "за показаннями" with a meter and "немає лічильника" without', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    const street = `E2E Street ${stamp}`;
    let typeId: string | undefined;
    await signIn(page, 'chairman@demo.local');
    try {
      const stale = await createFixture(page, `${stamp}s`, { street, daysAgo: 80 });
      plots.push(stale.plot);
      const fresh = await createFixture(page, `${stamp}f`, { street, daysAgo: 30 });
      plots.push(fresh.plot);
      const bare = await createFixture(page, `${stamp}n`, { street, meter: false });
      plots.push(bare.plot);
      for (const [f, takenOn] of [
        [stale, kyivDay(-75)],
        [fresh, kyivDay(-10)],
      ] as const)
        expectCreated(
          await api(page, 'POST', '/readings', { rows: [{ meterId: f.meterId, takenOn, value: '150' }] }),
          200,
        );

      const electrician = await otherPage(browser, 'electrician@demo.local');
      try {
        await electrician.goto(`/readings?street=${encodeURIComponent(street)}`);
        const cards = main(electrician)
          .getByRole('list', { name: 'Лічильники обходу' })
          .getByRole('listitem');
        await expect(cards).toHaveCount(2);
        await expect(main(electrician)).not.toContainText(`Ділянка ${bare.plot.number}`);
        await main(electrician).getByLabel('Без показу з', { exact: true }).click();
        await expect(main(electrician).getByLabel('Без показу з', { exact: true })).toBeChecked();
        await expect(main(electrician).getByLabel('Дата, з якої немає показу')).toHaveValue(kyivDay(-60));
        await expect(cards).toHaveCount(1);
        await expect(cards.first()).toContainText(`Ділянка ${stale.plot.number}`);
        await expect(main(electrician)).not.toContainText(`Ділянка ${bare.plot.number}`);
      } finally {
        await electrician.context().close();
      }

      // §2.7 #3 the tariff of a type made for this run (archiving the type removes the tariff from every plot)
      typeId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/charge-types', { name: `E2E тип ${stamp}` }),
      ).id;
      expectCreated(
        await api(page, 'POST', '/tariffs', {
          chargeTypeId: typeId,
          name: `E2E лічильник ${stamp}`,
          firstVersion: { validFrom: '2025-01-01', basis: 'PER_KWH', frequency: 'MONTHLY', rate: '4.32' },
        }),
      );
      const month = kyivDay(0).slice(0, 7);
      for (const [f, expected] of [
        [fresh, 'за показаннями'],
        [bare, 'немає лічильника'],
      ] as const) {
        await page.goto(`/plots/${f.plot.id}`);
        await main(page).getByLabel('Місяць').fill(month);
        const row = main(page)
          .getByRole('listitem')
          .filter({ hasText: `E2E лічильник ${stamp}` });
        await expect(row).toContainText(expected);
      }
    } finally {
      if (typeId) await api(page, 'POST', `/charge-types/${typeId}/archive`).catch(() => undefined);
      await cleanup(page, plots);
    }
  });

  test('§2.8 #1 #2 #3: the "Лічильники" sheet is previewed and imported, the same file is unchanged, another meter for the plot is a problem', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const number = `E2E-M-IMP-${stamp}`;
    const serial = `E2E-IMP-${stamp}`;
    const plots: CreatedPlot[] = [];
    const file = (plotRow: string[], meterSerial: string) =>
      xlsx([
        { name: 'Plots', rows: [['number', 'kind', 'area_m2', 'street'], plotRow] },
        {
          name: 'Лічильники',
          rows: [
            ['plot_number', 'resource', 'serial', 'installed_on', 'initial_reading'],
            [number, 'електроенергія', meterSerial, '01.06.2026', '8000'],
          ],
        },
      ]);
    const countsRow = (label: string) =>
      main(page)
        .getByRole('table', { name: 'Що зробить імпорт' })
        .getByRole('row')
        .filter({ has: page.getByRole('cell', { name: label, exact: true }) });
    const check = async (buffer: Buffer) => {
      await page.goto('/imports');
      await page.locator('input[type="file"]').setInputFiles({
        name: `meters-${stamp}.xlsx`,
        mimeType: XLSX_TYPE,
        buffer,
      });
      await page.getByRole('button', { name: 'Перевірити файл' }).click();
    };
    await signIn(page, 'chairman@demo.local');
    try {
      const plotRow = [number, 'PLOT', '600', `E2E Street ${stamp}`];
      await check(file(plotRow, serial));
      await expect(main(page).getByText('У файлі немає проблем. Можна імпортувати.')).toBeVisible();
      await expect(countsRow('Лічильники').getByRole('cell')).toHaveText(['Лічильники', '1', '0', '0']);
      await main(page).getByRole('button', { name: 'Імпортувати', exact: true }).click();
      await expect(main(page).getByRole('heading', { name: 'Імпорт завершено' })).toBeVisible();

      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`),
        200,
      ).items.filter((p) => p.number === number);
      expect(found).toHaveLength(1);
      plots.push({ id: found[0]!.id, number });
      // the meter is in the register
      await page.goto(`/meters?street=${encodeURIComponent(`E2E Street ${stamp}`)}`);
      await expect(main(page).locator('tbody tr').filter({ hasText: serial })).toContainText(number);

      // the same file again: unchanged
      await check(file(plotRow, serial));
      await expect(countsRow('Лічильники').getByRole('cell')).toHaveText(['Лічильники', '0', '0', '1']);

      // another meter for the plot is a problem pointing to the register
      await check(file(plotRow, `${serial}-2`));
      await expect(main(page).getByRole('table', { name: 'Проблеми у файлі' })).toContainText(
        'На ділянці вже є інший активний лічильник — замініть його в реєстрі',
      );
      await expect(page.getByRole('button', { name: 'Імпортувати', exact: true })).toBeDisabled();
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.9 #1: the audit log shows a replacement and an approval with the chairman and the old and new values, in Ukrainian', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    await signIn(page, 'chairman@demo.local');
    try {
      const me = expectCreated<{ memberId: string }>(await api(page, 'GET', '/me'), 200);
      const members = expectCreated<{ items: { id: string; lastName: string; firstName: string }[] }>(
        await api(page, 'GET', '/members?size=100'),
        200,
      );
      const chairman = members.items.find((m) => m.id === me.memberId);
      const fixture = await createFixture(page, stamp, {
        ownerId: await memberIdOf(browser, 'member@demo.local'),
        daysAgo: 60,
        initial: '8000',
      });
      plots.push(fixture.plot);
      expectCreated(
        await api(page, 'POST', '/readings', {
          rows: [{ meterId: fixture.meterId, takenOn: kyivDay(-40), value: '8140' }],
        }),
        200,
      );
      const member = await otherPage(browser, 'member@demo.local');
      let pending = '';
      try {
        pending = expectCreated<{ reading: { id: string } }>(
          await api(member, 'POST', `/plots/${fixture.plot.id}/readings`, {
            meterId: fixture.meterId,
            takenOn: kyivDay(-30),
            value: '8150',
          }),
        ).reading.id;
      } finally {
        await member.context().close();
      }
      expectCreated(await api(page, 'POST', `/readings/${pending}/approve`), 200);
      expectCreated(
        await api(page, 'POST', `/meters/${fixture.meterId}/replace`, {
          replacedOn: kyivDay(-20),
          finalReading: '8205',
          serial: `${fixture.serial}-new`,
          initialReading: '3',
        }),
      );

      await page.goto(`/audit?entityId=${fixture.meterId}`);
      const table = main(page).getByRole('table');
      const replaced = table.getByRole('row').filter({ hasText: 'Лічильник замінено' });
      await expect(replaced).toHaveCount(1);
      await expect(replaced).toContainText(/Кінцевий показ:\s*—\s*→\s*8\s?205/);
      await expect(replaced).toContainText(dayUk(kyivDay(-20)));
      await expect(replaced).toContainText(`${fixture.serial}-new`);
      const approved = table.getByRole('row').filter({ hasText: 'Показ підтверджено' });
      await expect(approved).toHaveCount(1);
      await expect(approved).toContainText(/8\s?150/);
      if (chairman) {
        await expect(replaced).toContainText(chairman.lastName);
        await expect(approved).toContainText(chairman.lastName);
      }
      // one language at a time: no English action label or field name next to the Ukrainian ones
      await expect(table).not.toContainText(/Meter replaced|Reading approved|Final reading/);
    } finally {
      await cleanup(page, plots);
    }
  });

  test('§2.10 #1: the demo chairman sees the substation, two quarters, the well pump, the street lighting and plot meters, and "На підтвердження" has one reading', async ({
    page,
  }) => {
    await signIn(page, 'chairman@demo.local');
    await menuLink(page, 'Лічильники').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Лічильники' })).toBeVisible();
    const table = main(page).locator('tbody');
    await expect(table.getByRole('row').filter({ hasText: 'Підстанція' }).first()).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: 'Квартал А' })).toHaveCount(1);
    await expect(table.getByRole('row').filter({ hasText: 'Квартал Б' })).toHaveCount(1);
    await expect(table.getByRole('row').filter({ hasText: 'Свердловина №1' })).toHaveCount(1);
    await expect(table.getByRole('row').filter({ hasText: 'Освітлення вул. Вишнева' })).toHaveCount(1);
    await expect(table.getByRole('row').filter({ hasText: 'Лічильник ділянки' }).first()).toBeVisible();

    await menuLink(page, 'На підтвердження').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Покази на підтвердження' })).toBeVisible();
    // exactly one pending demo reading (plot 7); other specs' per-run plots are named E2E-M-…
    const pending = main(page)
      .getByRole('list', { name: 'Покази, передані власниками' })
      .getByRole('listitem');
    await expect(pending.filter({ hasText: /^Ділянка 7\b/ })).toHaveCount(1);
    await expect(pending.filter({ hasText: /^Ділянка \d+(?!\d|-)/ })).toHaveCount(1);
  });
  test('review: a resource icon names electricity and water, a plot with two meters is one round card, the resource filter is remembered', async ({
    page,
  }) => {
    await signIn(page, 'chairman@demo.local');
    // demo plot 7 has an electricity and a water meter
    await page.goto('/readings');
    const list = main(page).getByRole('list', { name: 'Лічильники обходу' });
    const card = list.getByRole('listitem').filter({ hasText: /^Ділянка 7\b/ });
    await expect(card).toHaveCount(1);
    await expect(card.getByRole('button', { name: 'Зберегти' })).toHaveCount(2);
    await expect(card.getByRole('img', { name: 'Електроенергія' })).toHaveCount(1);
    await expect(card.getByRole('img', { name: 'Вода' })).toHaveCount(1);
    await expect(card.getByRole('img', { name: 'Вода' })).toHaveAttribute('title', 'Вода');

    // the filter is remembered per browser: it survives a reload of the bare address
    await main(page).getByLabel('Ресурс', { exact: true }).selectOption('WATER');
    await expect(page).toHaveURL(/resource=WATER/);
    await expect(card.getByRole('img', { name: 'Електроенергія' })).toHaveCount(0);
    await page.goto('/readings');
    await expect(main(page).getByLabel('Ресурс', { exact: true })).toHaveValue('WATER');
    await expect(card.getByRole('img', { name: 'Вода' })).toHaveCount(1);
    await expect(card.getByRole('img', { name: 'Електроенергія' })).toHaveCount(0);
    // an address with its own choice wins; "all" clears the memory
    await page.goto('/readings?resource=ELECTRICITY');
    await expect(main(page).getByLabel('Ресурс', { exact: true })).toHaveValue('ELECTRICITY');
    await main(page).getByLabel('Ресурс', { exact: true }).selectOption('');
    await page.goto('/readings');
    await expect(main(page).getByLabel('Ресурс', { exact: true })).toHaveValue('');

    // the register shows the icons too
    await page.goto('/meters');
    await expect(main(page).getByRole('img', { name: 'Вода' }).first()).toBeVisible();
    await expect(main(page).getByRole('img', { name: 'Електроенергія' }).first()).toBeVisible();
  });

  test('review: a calculated value in the meter tree is shown to whole kWh, a real one as it is', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const plots: CreatedPlot[] = [];
    let meterId: string | undefined;
    await signIn(page, 'chairman@demo.local');
    try {
      const fixture = await createFixture(page, stamp, { daysAgo: 30, initial: '100' });
      plots.push(fixture.plot);
      meterId = fixture.meterId;
      // 185 kWh over 18 days: the value 10 days ago is 302.777..., so the range ends calculated at 102.777... kWh
      for (const [takenOn, value] of [
        [kyivDay(-20), '200'],
        [kyivDay(-2), '385'],
      ] as const)
        expectCreated(
          await api(page, 'POST', '/readings', { rows: [{ meterId: fixture.meterId, takenOn, value }] }),
          200,
        );
      await page.goto('/settings/meter-tree');
      await main(page).getByLabel('З', { exact: true }).fill(kyivDay(-20));
      await main(page).getByLabel('По', { exact: true }).fill(kyivDay(-10));
      const places = main(page)
        .getByRole('listitem')
        .filter({ hasText: fixture.serial })
        .filter({ hasNotText: 'Квартал' });
      await places.last().getByRole('button', { name: 'Перемістити' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Батьківський лічильник').selectOption({ label: 'Квартал А' });
      await dialog.getByLabel('Діє з').fill(kyivDay(-30));
      await dialog.getByRole('button', { name: 'Перемістити', exact: true }).click();
      await expect(dialog).toHaveCount(0);

      const part = places.first();
      await expect(part).toContainText('Споживання: 103 кВт·год');
      await expect(part).toContainText('розрахунковий');
      await expect(part).not.toContainText('102,7');
      // a range that starts and ends on readings is real, shown as it is
      await main(page).getByLabel('По', { exact: true }).fill(kyivDay(-2));
      await expect(places.first()).toContainText('Споживання: 185 кВт·год');
    } finally {
      if (meterId) {
        await api(page, 'PUT', `/meters/${meterId}/parent`, { effectiveFrom: kyivDay(0) }).catch(
          () => undefined,
        );
      }
      await cleanup(page, plots);
    }
  });
  test('review: the replacement chain of demo plot 9 is on both meter cards and on the plot card, with links', async ({
    page,
  }) => {
    await signIn(page, 'chairman@demo.local');
    const found = expectCreated<{ items: { id: string; number: string }[] }>(
      await api(page, 'GET', '/plots?q=9'),
      200,
    );
    const plot = found.items.find((p) => p.number === '9');
    expect(plot, 'demo plot 9').toBeDefined();
    const removed = dayUk(kyivDay(-30));
    // old meter "ЕЛ-9" (final reading 3340) was replaced by "ЕЛ-9/2"
    const replacedLine = new RegExp(`^Замінено на ЕЛ-9/2: знято ${removed}, кінцевий показ 3\\s340 кВт·год$`);
    const replacesLine = new RegExp(
      `^Замінив лічильник ЕЛ-9: знято ${removed}, кінцевий показ 3\\s340 кВт·год$`,
    );

    // the plot card lists both meters with the chain lines
    await page.goto(`/plots/${plot!.id}`);
    await expect(main(page).getByTestId('replaced-by')).toHaveText(replacedLine);
    await expect(main(page).getByTestId('replaces')).toHaveText(replacesLine);

    // the new meter's card links back to the old one
    await main(page).getByTestId('replaces').getByRole('link', { name: 'ЕЛ-9', exact: true }).click();
    await expect(page).toHaveURL(/\/meters\//);
    await expect(main(page).getByTestId('replaced-by')).toHaveText(replacedLine);
    await expect(main(page).getByTestId('replaces')).toHaveCount(0);

    // and the old meter's card links forward to the new one
    await main(page).getByTestId('replaced-by').getByRole('link', { name: 'ЕЛ-9/2' }).click();
    await expect(page).toHaveURL(/\/meters\//);
    await expect(main(page).getByTestId('replaces')).toHaveText(replacesLine);
    await expect(main(page).getByTestId('replaced-by')).toHaveCount(0);
  });
});
