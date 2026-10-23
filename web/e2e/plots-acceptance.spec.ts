// @layer: e2e
// @spec: 006-plots-registry
// @regression
// Acceptance specs of the whole feature "Plots Registry" (functional-spec.md of 006) that plots.spec.ts does not
// cover: the Ukrainian wording of areas and grey labels, the kind filter, paging, the shared states, the demo
// cooperative, the refusals for people without edit rights and the restore conflict. Criteria are named §2.x #n.
import { expect, test, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const search = (page: Page, label = 'Search by number, street or cadastral number') =>
  page.getByRole('main').getByLabel(label, { exact: true });
const rows = (page: Page) => page.getByRole('main').locator('tbody tr');
const field = (page: Page, label: string) => page.getByRole('main').getByLabel(label, { exact: true });
const numbers = (page: Page) => rows(page).locator('td:first-child').allTextContents();

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

interface ApiResult {
  status: number;
  body: string;
}

/** One API call with the signed-in person's token: for set-up, clean-up and the refusals the UI cannot send. */
async function api(page: Page, method: string, path: string, body?: unknown): Promise<ApiResult> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
      const token = key
        ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
        : {};
      const response = await fetch(`/api/v1${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${token.access_token ?? ''}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: response.status, body: await response.text() };
    },
    { method, path, body },
  );
}

/** The plot ids created by a test, removed at the end in an order that always works (restore, then delete). */
class Cleanup {
  private readonly ids: string[] = [];
  track(id: string): void {
    this.ids.push(id);
  }
  /** A failure here is thrown only when the test itself passed, so it never hides the original failure. */
  async run(page: Page, testFailed: boolean): Promise<void> {
    const problems: string[] = [];
    for (const id of [...this.ids].reverse()) {
      await api(page, 'POST', `/plots/${id}/restore`).catch(() => undefined);
      const removed = await api(page, 'DELETE', `/plots/${id}`).catch((e: unknown) => ({
        status: 0,
        body: String(e),
      }));
      if (removed.status !== 204 && removed.status !== 404) problems.push(`${id}: ${removed.status}`);
    }
    if (problems.length > 0) {
      const message = `plots left behind: ${problems.join(', ')}`;
      if (testFailed) console.warn(message);
      else throw new Error(message);
    }
  }
}

/** Clicks Save and returns the id of the plot from the 201 response. */
async function saveNewPlot(page: Page, save: string): Promise<string> {
  const created = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/plots') && r.status() === 201,
  );
  await page.getByRole('button', { name: save, exact: true }).click();
  return ((await (await created).json()) as { id: string }).id;
}

test.describe('plots registry: acceptance', () => {
  test('§2.1 #1, #6 in Ukrainian: 6 sotkas, 600 m² and 6,5 sotkas are shown as "6 соток" and "6,5 сотки" with a grey "Без власника"', async ({
    page,
  }) => {
    const cleanup = new Cleanup();
    const stamp = Date.now();
    let failed = false;
    await signInInUkrainian(page, 'chairman@demo.local');
    try {
      await expect(page).toHaveURL(/\/plots$/);
      await expect(page.getByRole('heading', { level: 1, name: 'Ділянки' })).toBeVisible();

      const cases = [
        { number: `U6-${stamp}`, unit: 'сотки', typed: '6', card: '6 соток (600 м²)', listed: '6' },
        { number: `UM-${stamp}`, unit: 'м²', typed: '600', card: '6 соток (600 м²)', listed: '6' },
        { number: `UH-${stamp}`, unit: 'сотки', typed: '6,5', card: '6,5 сотки (650 м²)', listed: '6,5' },
      ];
      for (const c of cases) {
        await page.getByRole('button', { name: 'Додати ділянку' }).click();
        await expect(page.getByRole('heading', { level: 1, name: 'Нова ділянка' })).toBeVisible();
        await field(page, 'Номер').fill(c.number);
        await field(page, 'Вулиця').fill('Вишнева');
        await field(page, 'Одиниця').selectOption({ label: c.unit }); // choose the unit first: changing it converts the text
        await field(page, 'Площа').fill(c.typed);
        cleanup.track(await saveNewPlot(page, 'Зберегти'));

        await expect(page.getByRole('heading', { level: 1, name: `Ділянка ${c.number}` })).toBeVisible();
        await expect(page.getByRole('main')).toContainText(c.card);
        await expect(page.getByRole('main')).toContainText('Без власника');

        await page.getByRole('link', { name: 'До списку ділянок' }).click();
        await search(page, 'Пошук за номером, вулицею або кадастровим номером').fill(c.number);
        await expect(rows(page)).toHaveCount(1);
        // the unit is in the column header ("Площа, соток"), the cell holds the number of sotkas
        await expect(page.getByRole('main').locator('thead th', { hasText: 'Площа, соток' })).toBeVisible();
        await expect(rows(page).first().locator('td').nth(2)).toHaveText(c.listed);
        await expect(rows(page).first()).toContainText('Без власника');
      }
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      await cleanup.run(page, failed);
    }
  });

  test('§2.1 marks in Ukrainian: a debt label ("Борг понад поріг" or "Є борг", by the age of the debt) on an owned plot, grey "Без власника" on an unowned one and "Спільні землі" on the road, filter by kind', async ({
    page,
  }) => {
    await signInInUkrainian(page, 'treasurer@demo.local');

    await search(page, 'Пошук за номером, вулицею або кадастровим номером').fill('R-1');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first().locator('td').nth(4)).toHaveText('Спільні землі'); // kind
    await expect(rows(page).first().locator('td').nth(5)).toContainText('Спільні землі'); // grey status label
    await search(page, 'Пошук за номером, вулицею або кадастровим номером').fill('');
    // plot 1 is owned and has a debt in the demo ledger (red or yellow, by where the anchor day falls); plot 27 has
    // no owner (grey)
    await expect(rows(page).first().locator('td').nth(5)).toContainText(/Борг понад поріг|Є борг/);
    await search(page, 'Пошук за номером, вулицею або кадастровим номером').fill('27');
    const unowned = rows(page).filter({ has: page.getByRole('link', { name: '27', exact: true }) });
    await expect(unowned).toHaveCount(1);
    await expect(unowned.locator('td').nth(5)).toContainText('Без власника');
    await search(page, 'Пошук за номером, вулицею або кадастровим номером').fill('');

    // §2.2 #3: the kind filter lists only the road
    await page.getByRole('main').getByLabel('Вид', { exact: true }).selectOption({ label: 'Спільні землі' });
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('R-1');
  });

  test('§2.2 #3, #5, #6, §2.6 #1: kind filter, paging in the same order, nothing found, error with Try again, demo cooperative', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await expect(page).toHaveURL(/\/plots$/);
    await expect(rows(page)).toHaveCount(20);

    // §2.6 #1 and §2.2 #5: 31 records over two pages of 20; the next page continues the order
    const first = await numbers(page);
    await expect(page.getByText('Page 1 of 2')).toBeVisible();
    expect(first.slice(0, 3)).toEqual(['1', '2', '3']);
    expect(first.indexOf('12')).toBeLessThan(first.indexOf('12а'));
    expect(first.at(-1)).toBe('19');
    const firstPageRows = await rows(page).allInnerTexts();
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(page.getByText('Page 2 of 2')).toBeVisible();
    await expect(rows(page)).toHaveCount(11);
    const second = await numbers(page);
    expect(second[0]).toBe('20');
    expect(second.at(-1)).toBe('R-1');
    expect(new Set([...first, ...second]).size).toBe(31);
    const secondPageRows = await rows(page).allInnerTexts();
    const everyRow = [...firstPageRows, ...secondPageRows];
    // about 30 plots on two streets; the road (common land) lies on one of them
    const plotRows = everyRow.filter((r) => !r.startsWith('R-1'));
    expect(plotRows).toHaveLength(30);
    expect(plotRows.every((r) => r.includes('вул. Садова') || r.includes('вул. Вишнева'))).toBe(true);
    expect(plotRows.filter((r) => r.includes('вул. Садова')).length).toBeGreaterThan(10);
    expect(plotRows.filter((r) => r.includes('вул. Вишнева')).length).toBeGreaterThan(10);
    // the demo ledger gives every one of the 25 owned plots a debt, so each is red or yellow and none green (how many
    // are red depends on the day the demo was loaded); plots 25-29 have "No owner" and the road is "Common land"
    const red = everyRow.filter((r) => r.includes('Debt above threshold')).length;
    const yellow = everyRow.filter((r) => r.includes('Has debt')).length;
    expect(red + yellow).toBe(25);
    expect(everyRow.filter((r) => r.includes('No debt')).length).toBe(0);
    expect(everyRow.filter((r) => r.includes('No owner')).length).toBe(5);
    const road = everyRow.filter((r) => r.startsWith('R-1'));
    expect(road).toHaveLength(1);
    expect(road[0]).not.toContain('No owner');
    expect(road[0]).not.toContain('No debt');
    expect(road[0]).not.toMatch(/debt/i);

    // §2.2 #3: the filter by kind "Common land" lists the road only (and resets paging)
    await page.getByRole('main').getByLabel('Kind', { exact: true }).selectOption({ label: 'Common land' });
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('R-1');
    await expect(page.getByText(/Page \d+ of \d+/)).toHaveCount(0);
    await page.getByRole('main').getByLabel('Kind', { exact: true }).selectOption({ label: 'Plot' });
    await expect(page.getByText('Page 1 of 2')).toBeVisible();
    await page.getByRole('main').getByLabel('Kind', { exact: true }).selectOption({ label: 'All kinds' });

    // §2.2 #6: nothing matches the search -> the shared "nothing found" state
    await search(page).fill('no-such-plot-zzz');
    await expect(page.getByRole('main').getByText('Nothing found')).toBeVisible();
    await expect(rows(page)).toHaveCount(0);
    await search(page).fill('');
    await expect(rows(page).first()).toBeVisible();

    // §2.2 #6: loading fails -> the shared error state with "Try again"; trying again brings the list back
    await page.route('**/api/v1/plots?*', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Internal Server Error', status: 500, code: 'INTERNAL_ERROR' }),
      }),
    );
    await page.goto('/plots?q=1');
    const alert = page.getByRole('main').getByRole('alert');
    await expect(alert).toBeVisible({ timeout: 15_000 });
    await expect(alert.getByRole('button', { name: 'Try again' })).toBeVisible();
    await expect(rows(page)).toHaveCount(0);
    await page.unroute('**/api/v1/plots?*');
    await alert.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page).first()).toBeVisible();
    await expect(alert).toHaveCount(0);
  });

  test('§2.2 #4, §2.3 #1: an ordinary member never sees the note of a plot; §2.3 #6 nobody without edit rights gets an action', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');
    await page.goto('/plots');
    await expect(page.getByRole('button', { name: 'Add plot' })).toHaveCount(0);
    await search(page, 'Search by number or street').fill('9');
    await rows(page)
      .filter({ hasText: 'вул. Садова' })
      .first()
      .getByRole('link', { name: '9', exact: true })
      .click();
    await expect(page.getByRole('heading', { level: 1, name: 'Plot 9' })).toBeVisible();
    await expect(page.getByRole('main')).not.toContainText("Кутова ділянка біля в'їзних воріт");
    await expect(page.getByRole('main')).not.toContainText('sotka');
    await expect(page.getByRole('main')).not.toContainText('4810136300');
    // the edit form is closed to them and the server refuses the same change
    const id = /\/plots\/([0-9a-f-]{36})$/.exec(page.url())?.[1] ?? '';
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    await page.goto(`/plots/${id}/edit`);
    await expect(page.getByRole('alert')).toContainText("You don't have access to this page");
    expect((await api(page, 'POST', `/plots/${id}/archive`)).status).toBe(403);
    expect((await api(page, 'DELETE', `/plots/${id}`)).status).toBe(403);
  });

  test('§2.3 #6: a treasurer sees no Add plot, no form, and every change is refused with PERMISSION_DENIED', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    await expect(rows(page).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add plot' })).toHaveCount(0);

    await page.goto('/plots/new');
    await expect(page.getByRole('alert')).toContainText("You don't have access to this page");
    await expect(page.getByRole('heading', { level: 1, name: 'New plot' })).toHaveCount(0);

    // a change sent anyway is refused and the plot is untouched
    await page.goto('/plots');
    await rows(page).first().getByRole('link').first().click();
    await expect(page.getByRole('heading', { level: 1, name: /^Plot / })).toBeVisible();
    const id = /\/plots\/([0-9a-f-]{36})$/.exec(page.url())?.[1] ?? '';
    for (const [method, path] of [
      ['POST', `/plots/${id}/archive`],
      ['POST', `/plots/${id}/restore`],
      ['DELETE', `/plots/${id}`],
    ] as const) {
      const refused = await api(page, method, path);
      expect(refused.status).toBe(403);
      expect(refused.body).toContain('PERMISSION_DENIED');
    }
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: /^Plot / })).toBeVisible();
    await expect(page.locator('main').getByText('Archived', { exact: true })).toHaveCount(0);
  });

  test('§2.3 #3: restoring an archived plot whose number was taken meanwhile is refused with the number message', async ({
    page,
  }) => {
    const cleanup = new Cleanup();
    const number = `RC-${Date.now()}`;
    let failed = false;
    await loginAs(page, 'chairman@demo.local');
    try {
      await page.getByRole('button', { name: 'Add plot' }).click();
      await field(page, 'Number').fill(number);
      await field(page, 'Area').fill('4');
      const firstId = await saveNewPlot(page, 'Save');
      cleanup.track(firstId);
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
      await page.getByRole('button', { name: 'Archive', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Restore', exact: true })).toBeVisible();

      // §2.1 #3: the number of the archived plot is free again
      const second = await api(page, 'POST', '/plots', { number, kind: 'PLOT', areaM2: 400 });
      expect(second.status).toBe(201);
      cleanup.track((JSON.parse(second.body) as { id: string }).id);

      await page.getByRole('button', { name: 'Restore', exact: true }).click();
      await expect(
        page.getByRole('main').getByText('Another active plot already has this number.'),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: 'Restore', exact: true })).toBeVisible();
      await expect(page.getByRole('main').getByText('Archived', { exact: true })).toBeVisible();
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      await cleanup.run(page, failed);
    }
  });
});
