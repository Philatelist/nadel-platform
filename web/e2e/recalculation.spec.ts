// @layer: e2e
// @spec: 012-retroactive-recalculation
// @regression
// Recalculation in a browser: the chairman corrects a used tariff version and a charged reading through step 2 (preview,
// reason, book) and sees the reversal and the new charge on the plot account; the electrician is refused with the
// hint; the manual page previews, books and then finds nothing to change; the plot 2 adjustment of the demo is on its
// account. Every test CREATES its own plot (and charge type, meter) with a unique stamp, charges only that plot and
// retires everything in `finally`. The demo months are read from the API and never closed here (closing is in
// `zz-closing.spec.ts`, which must run last; this file sorts before it).
import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  createPlot,
  demoMonths,
  expectCreated,
  kyivDay,
  retireMeter,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');

async function signedInPage(browser: Browser, email: string) {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

interface AccountCharge {
  id: string;
  billingMonth: string;
  reversible: boolean;
  kind: string;
  amount: string;
  chargeType: { code?: string; name?: string };
}

async function accountOf(page: Page, plotId: string): Promise<{ charges: AccountCharge[] }> {
  return expectCreated(await api(page, 'GET', `/plots/${plotId}/account`), 200);
}

/** Books the month run (preview, then the digest of that preview) through the API. */
async function bookMonth(page: Page, month: string): Promise<void> {
  const preview = expectCreated<{ digest: string }>(
    await api(page, 'POST', `/billing-months/${month}/run?preview=true`),
    200,
  );
  expectCreated(
    await api(page, 'POST', `/billing-months/${month}/run?preview=false`, { digest: preview.digest }),
    200,
  );
}

/** Best effort: reverses what can be reversed, retires the meter, ends the ownership and archives the plot. */
async function tidy(page: Page, plot: CreatedPlot | undefined, meterId?: string): Promise<void> {
  if (!plot) return;
  const account = await accountOf(page, plot.id).catch(() => undefined);
  for (const charge of account?.charges ?? [])
    if (charge.reversible)
      await api(page, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
        () => undefined,
      );
  if (meterId) await retireMeter(page, meterId);
  await archiveLeftovers(page, [plot], [], kyivDay(0));
}

async function plotIdOf(page: Page, number: string): Promise<string> {
  const found = expectCreated<{ items: { id: string; number: string }[] }>(
    await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`),
    200,
  );
  const plot = found.items.find((p) => p.number === number);
  if (!plot) throw new Error(`plot ${number} not found`);
  return plot.id;
}

test.describe('retroactive recalculation', () => {
  test('the chairman corrects a used tariff version, sees the preview and the change on the plot account @smoke', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E recalc type ${stamp}`;
    const tariffName = `E2E recalc tariff ${stamp}`;
    let chargeTypeId: string | undefined;
    let plot: CreatedPlot | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      const { open } = await demoMonths(page);
      plot = await createPlot(page, `E2E-RC-T${stamp}`);
      chargeTypeId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/charge-types', { name: typeName }),
      ).id;
      const tariff = expectCreated<{ id: string }>(
        await api(page, 'POST', '/tariffs', {
          chargeTypeId,
          name: tariffName,
          firstVersion: {
            validFrom: `${open}-01`,
            basis: 'PER_AREA_SOTKA',
            frequency: 'YEARLY',
            rate: '60',
          },
        }),
      );
      // only this plot gets the new tariff, so the month run charges nothing else
      expectCreated(
        await api(page, 'PUT', `/tariffs/${tariff.id}/scope`, { kind: 'SELECTED_PLOTS', plotIds: [plot.id] }),
        200,
      );
      await bookMonth(page, open);
      const before = (await accountOf(page, plot.id)).charges.filter((c) => c.chargeType.name === typeName);
      expect(before.map((c) => c.amount)).toEqual(['20.00']);

      // the version is used by a charge: the correction opens step 2 with the preview
      await page.goto(`/tariffs/${tariff.id}`);
      const version = main(page)
        .getByRole('listitem')
        .filter({ has: page.getByRole('button', { name: 'Edit', exact: true }) });
      await version.getByRole('button', { name: 'Edit', exact: true }).click();
      const form = main(page).getByRole('form', { name: 'Edit version' });
      await form.getByLabel(/^Rate/).fill('50');
      await form.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(main(page).getByRole('heading', { name: 'Difference by charge type' })).toBeVisible();
      await expect(main(page).getByText('A tariff version changed').first()).toBeVisible();
      await expect(main(page).getByText('Reversal and new charge').first()).toBeVisible();

      // no reason, no booking
      const book = main(page).getByRole('button', { name: 'Book the recalculation', exact: true });
      await book.click();
      await expect(main(page).getByText('Enter a reason')).toBeVisible();
      await main(page).getByLabel('Reason', { exact: true }).fill('E2E: wrong rate');
      await book.click();
      await expect(main(page).getByText('Version corrected, recalculation booked')).toBeVisible();

      // the plot account: the reversal of 20.00 and the new charge of 16.67 (4 sotka, 50 a year)
      await page.goto(`/plots/${plot.id}`);
      const list = main(page).getByRole('list', { name: 'Plot charges' }).getByRole('listitem');
      await expect(
        list
          .filter({ hasText: typeName })
          .filter({ hasText: /16[.,]67/ })
          .first(),
      ).toBeVisible();
      const after = (await accountOf(page, plot.id)).charges.filter((c) => c.chargeType.name === typeName);
      expect(after.map((c) => `${c.kind}:${c.amount}`).sort()).toEqual([
        'REGULAR:16.67',
        'REGULAR:20.00',
        'REVERSAL:-20.00',
      ]);
    } finally {
      await tidy(page, plot);
      if (chargeTypeId)
        await api(page, 'POST', `/charge-types/${chargeTypeId}/archive`).catch(() => undefined);
    }
  });

  test('the chairman corrects a charged reading on the meter card and the electrician is refused with the hint', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    let plot: CreatedPlot | undefined;
    let meterId = '';
    await loginAs(page, 'chairman@demo.local');
    try {
      await demoMonths(page);
      plot = await createPlot(page, `E2E-RC-R${stamp}`);
      const me = await signedInPage(browser, 'member@demo.local');
      let memberId: string;
      try {
        memberId = expectCreated<{ memberId: string }>(await api(me, 'GET', '/me'), 200).memberId;
      } finally {
        await me.context().close();
      }
      expectCreated(
        await api(page, 'POST', `/plots/${plot.id}/ownership-changes`, {
          effectiveFrom: kyivDay(-40),
          owners: [{ memberId, share: '1/1' }],
        }),
      );
      meterId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/meters', {
          kind: 'PLOT',
          resource: 'ELECTRICITY',
          plotId: plot.id,
          serial: `E2E-RC-SN-${stamp}`,
          installedOn: kyivDay(-30),
          initialReading: '100.000',
        }),
      ).id;
      // a counted reading charges electricity in the open month
      const saved = expectCreated<{ rows: { outcome: string }[] }>(
        await api(page, 'POST', '/readings', { rows: [{ meterId, takenOn: kyivDay(0), value: '120' }] }),
        200,
      );
      expect(saved.rows[0]?.outcome).toBe('SAVED');
      const electricity = async () =>
        (await accountOf(page, plot!.id)).charges.filter((c) => c.chargeType.code === 'ELECTRICITY');
      // the charge is booked by an async listener: wait for it
      await expect
        .poll(async () => (await electricity()).filter((c) => c.kind === 'REGULAR').length, {
          timeout: 15_000,
        })
        .toBeGreaterThan(0);

      // the electrician cannot correct a reading a charge uses: the hint, nothing changes
      const electrician = await signedInPage(browser, 'electrician@demo.local');
      try {
        await electrician.goto(`/meters/${meterId}`);
        const row = main(electrician).getByRole('row').filter({ hasText: '120' });
        await row.getByRole('button', { name: 'Correct', exact: true }).click();
        const dialog = electrician.getByRole('dialog');
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('110');
        await dialog.getByRole('button', { name: 'Save the correction', exact: true }).click();
        await expect(
          dialog.getByText('The reading has already been charged — ask the chairman'),
        ).toBeVisible();
      } finally {
        await electrician.context().close();
      }

      // the chairman gets step 2: preview, reason, book
      await page.goto(`/meters/${meterId}`);
      const row = main(page).getByRole('row').filter({ hasText: '120' });
      await row.getByRole('button', { name: 'Correct', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Reading, kWh', { exact: true }).fill('110');
      await dialog.getByRole('button', { name: 'Save the correction', exact: true }).click();
      await expect(dialog.getByRole('heading', { name: 'Difference by charge type' })).toBeVisible();
      await expect(dialog.getByText('A reading changed').first()).toBeVisible();
      await dialog.getByLabel('Reason', { exact: true }).fill('E2E: wrong reading');
      await dialog.getByRole('button', { name: 'Book the recalculation', exact: true }).click();
      await expect(main(page).getByText('Reading corrected, recalculation booked')).toBeVisible();

      // the reading is 110 now and the account has a reversal and a new electricity charge
      await expect(main(page).getByRole('row').filter({ hasText: '110' }).first()).toBeVisible();
      const after = await electricity();
      expect(after.some((c) => c.kind === 'REVERSAL')).toBe(true);
      expect(after.filter((c) => c.kind === 'REGULAR').length).toBeGreaterThan(1);
      await page.goto(`/plots/${plot.id}`);
      await expect(
        main(page).getByRole('list', { name: 'Plot charges' }).getByRole('listitem').first(),
      ).toBeVisible();
    } finally {
      await tidy(page, plot, meterId || undefined);
    }
  });

  test('the manual page previews a changed plot area, books it, and a second preview says there are no changes', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let plot: CreatedPlot | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      const { open } = await demoMonths(page);
      plot = await createPlot(page, `E2E-RC-M${stamp}`);
      await bookMonth(page, open);
      // the area grows after the month was charged: the membership fee should now be higher
      expectCreated(
        await api(page, 'PUT', `/plots/${plot.id}`, {
          number: plot.number,
          kind: 'PLOT',
          areaM2: 800,
          street: 'E2E Lane',
        }),
        200,
      );

      const fillAndPreview = async () => {
        await page.goto('/charges/recalculations/new');
        await main(page).getByLabel('From month', { exact: true }).fill(open);
        await main(page).getByLabel('To month', { exact: true }).fill(open);
        await main(page).getByLabel('Chosen plots', { exact: true }).check();
        await main(page).getByLabel('Find a plot by number', { exact: true }).fill(plot!.number);
        await main(page)
          .getByRole('option', { name: new RegExp(`^${plot!.number}`) })
          .click();
        await main(page).getByRole('button', { name: 'Preview', exact: true }).click();
      };

      await fillAndPreview();
      await expect(main(page).getByRole('heading', { name: 'Difference by charge type' })).toBeVisible();
      await expect(main(page).getByText('The plot area changed').first()).toBeVisible();
      await main(page).getByLabel('Reason', { exact: true }).fill('E2E: new area');
      await main(page).getByRole('button', { name: 'Book the recalculation', exact: true }).click();

      // the detail of the booked recalculation
      await expect(page).toHaveURL(/\/charges\/recalculations\/[0-9a-f-]{36}$/);
      await expect(main(page).getByText('E2E: new area').first()).toBeVisible();

      // the same scope again: nothing to change
      await fillAndPreview();
      await expect(main(page).getByText('No changes', { exact: true })).toBeVisible();
      await expect(main(page).getByRole('button', { name: 'Book the recalculation' })).toHaveCount(0);
    } finally {
      await tidy(page, plot);
    }
  });

  test('the adjustment of the demo recalculation is on the account of plot 2', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    const plotId = await plotIdOf(page, '2');
    await page.goto(`/plots/${plotId}`);
    const list = main(page).getByRole('list', { name: 'Plot charges' });
    const row = list.getByRole('listitem').filter({ hasText: 'Adjustment for' });
    await expect(row).toContainText(/5[.,]00/);
    await expect(row).toContainText('Виправлено ставку членського внеску');
    await expect(row.getByRole('link', { name: 'Recalculation', exact: true })).toBeVisible();

    // the filter keeps the adjustment and hides the regular charges
    await main(page).getByLabel('Adjustments only').click();
    await expect(list.getByRole('listitem').filter({ hasText: 'Adjustment for' }).first()).toBeVisible();
    await expect(list.getByRole('listitem').filter({ hasNotText: 'Adjustment for' })).toHaveCount(0);

    // the log lists the recalculation
    await page.goto('/charges/recalculations');
    await expect(main(page).getByText('Виправлено ставку членського внеску').first()).toBeVisible();
  });
});
