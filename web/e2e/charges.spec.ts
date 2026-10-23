// @layer: e2e
// @spec: 011-ledger-charges-period-closing
// @regression
// Charges and period closing in a browser, on the demo ledger (the previous month closed, the current one open and already run):
// the chairman runs the open month for a plot added after the run, approves an owner's reading and sees the electricity
// charge, runs and reverses a losses run with a warning node, retries a "not charged" item, reverses a charge and
// the treasurer sees no actions; the member sees only the own plot. Every test CREATES its own plots (unique
// number) and archives them; the losses run and the charges it books are reversed in `finally`. Closing a month
// is in `zz-closing.spec.ts`, which must run last.
import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  createPlot,
  expectCreated,
  demoMonths,
  kyivDay,
  monthLabel,
  pastRange,
  retireMeter,
  shortDay,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });

async function signedInPage(browser: Browser, email: string) {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

/** The id of a demo plot by its number. */
async function plotIdOf(page: Page, number: string): Promise<string> {
  const found = expectCreated<{ items: { id: string; number: string }[] }>(
    await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`),
    200,
  );
  const plot = found.items.find((p) => p.number === number);
  if (!plot) throw new Error(`plot ${number} not found`);
  return plot.id;
}

interface AccountCharge {
  id: string;
  billingMonth: string;
  reversible: boolean;
  kind: string;
  amount: string;
  chargeType: { code?: string };
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

/** Best effort: reverses every reversible charge of the plot, then ends its ownership and archives it. */
async function tidyPlot(page: Page, plot: CreatedPlot): Promise<void> {
  const account = await accountOf(page, plot.id).catch(() => undefined);
  for (const charge of account?.charges ?? [])
    if (charge.reversible)
      await api(page, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
        () => undefined,
      );
  await archiveLeftovers(page, [plot], [], kyivDay(0));
}

test.describe('charges and period closing', () => {
  // plots left active by an interrupted earlier run would be charged by the next month run: archive them
  test.beforeAll(async ({ browser }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(chairman, 'GET', '/plots?q=E2E-C-'),
        200,
      );
      for (const plot of found.items.filter((p) => p.number.startsWith('E2E-C-')))
        await tidyPlot(chairman, plot);
    } finally {
      await chairman.context().close();
    }
  });

  test('the chairman sees the months, runs the open month for a plot added after the run and a rerun adds nothing @smoke', async ({
    page,
  }) => {
    const plot = { id: '', number: `E2E-C-R${Date.now().toString(36)}` };
    await loginAs(page, 'chairman@demo.local');
    const { open, closed } = await demoMonths(page);
    const openLabel = monthLabel(open);
    try {
      await menuLink(page, 'Charges').click();
      await expect(page.getByRole('heading', { level: 1, name: 'Charges' })).toBeVisible();
      const months = main(page).getByRole('list', { name: 'Billing months' }).getByRole('listitem');
      await expect(months.filter({ hasText: openLabel })).toContainText('Open');
      await expect(months.filter({ hasText: monthLabel(closed) })).toContainText('Closed');
      await expect(months.filter({ hasText: openLabel })).toContainText('Membership fee');

      // the open month is already run: the preview has nothing new and the check list says so
      await months.filter({ hasText: openLabel }).getByRole('link', { name: openLabel }).click();
      await expect(page.getByRole('heading', { level: 1, name: openLabel })).toBeVisible();
      await expect(main(page).getByText('The month run is done')).toBeVisible();
      await main(page).getByRole('button', { name: 'Charge the month', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('Plots that get new charges: 0')).toBeVisible();
      await expect(dialog.getByText('Nothing new to charge')).toBeVisible();
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();

      // a plot added after the run is the only thing the rerun charges
      plot.id = (await createPlot(page, plot.number)).id;
      await main(page).getByRole('button', { name: 'Charge the month', exact: true }).click();
      await expect(dialog.getByText('Plots that get new charges: 1')).toBeVisible();
      await expect(dialog.getByRole('heading', { name: 'New charges' })).toBeVisible();
      await expect(dialog.getByText('Membership fee')).toBeVisible();
      await dialog.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(dialog.getByText(/^Charges created: [1-9]/)).toBeVisible();
      await dialog.getByRole('button', { name: 'Close', exact: true }).click();

      // the charges are on the plot's account, and a second rerun adds nothing
      const account = await accountOf(page, plot.id);
      expect(account.charges.some((c) => c.chargeType.code === 'MEMBERSHIP')).toBe(true);
      await main(page).getByRole('button', { name: 'Charge the month', exact: true }).click();
      await expect(dialog.getByText('Nothing new to charge')).toBeVisible();
    } finally {
      await tidyPlot(page, plot.id ? plot : { id: '', number: plot.number });
    }
  });

  test('the chairman approves an owner reading and sees the electricity charge with its calculation @smoke', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const plot = { id: '', number: `E2E-C-E${stamp}` };
    let meterId = '';
    await loginAs(page, 'chairman@demo.local');
    try {
      const me = await signedInPage(browser, 'member@demo.local');
      let memberId: string;
      try {
        memberId = expectCreated<{ memberId: string }>(await api(me, 'GET', '/me'), 200).memberId;
      } finally {
        await me.context().close();
      }
      plot.id = (await createPlot(page, plot.number)).id;
      expectCreated(
        await api(page, 'POST', `/plots/${plot.id}/ownership-changes`, {
          effectiveFrom: kyivDay(-40),
          owners: [{ memberId, share: '1/1' }],
        }),
      );
      const serial = `E2E-SN-${stamp}`;
      meterId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/meters', {
          kind: 'PLOT',
          resource: 'ELECTRICITY',
          plotId: plot.id,
          serial,
          installedOn: kyivDay(-30),
          initialReading: '100.000',
        }),
      ).id;

      // the owner submits 120 kWh; nothing is charged until the board approves
      const member = await signedInPage(browser, 'member@demo.local');
      try {
        await member.goto('/account');
        const meterCard = main(member)
          .locator('section[aria-labelledby="my-meters"]')
          .getByRole('listitem')
          .filter({ hasText: serial })
          .first();
        await meterCard.getByRole('button', { name: 'Submit a reading', exact: true }).click();
        const dialog = member.getByRole('dialog');
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByRole('button', { name: 'Submit a reading', exact: true }).click();
        await expect(meterCard.getByText('The reading is submitted and waits for approval')).toBeVisible();
      } finally {
        await member.context().close();
      }
      expect((await accountOf(page, plot.id)).charges).toHaveLength(0);

      await menuLink(page, 'To approve').click();
      const item = main(page)
        .getByRole('list', { name: 'Readings submitted by owners' })
        .getByRole('listitem')
        .filter({ hasText: `Plot ${plot.number}` });
      await item.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect(item).toHaveCount(0);

      // the electricity charge is on the plot's account and shows how it was calculated
      await page.goto(`/plots/${plot.id}`);
      const panel = main(page).locator('section', {
        has: page.getByRole('heading', { name: 'Account', exact: true }),
      });
      const row = panel
        .getByRole('list', { name: 'Plot charges' })
        .getByRole('listitem')
        .filter({ hasText: 'Electricity' })
        .first();
      await expect(row).toBeVisible();
      await row.getByRole('button', { name: 'Details', exact: true }).click();
      const detail = page.getByRole('dialog');
      await expect(detail.getByText('Calculation')).toBeVisible();
      await expect(detail).toContainText('20 kWh');
      await expect(detail).toContainText(shortDay(kyivDay(0)));
      await detail.getByRole('button', { name: 'Close', exact: true }).first().click();
      await expect(panel.getByText('Total debt')).toBeVisible();
    } finally {
      if (plot.id) {
        // reverse the charge, delete the readings and the meter, and only then archive (an active meter blocks it)
        const account = await accountOf(page, plot.id).catch(() => undefined);
        for (const charge of account?.charges ?? [])
          if (charge.reversible)
            await api(page, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
              () => undefined,
            );
        if (meterId) await retireMeter(page, meterId);
        await archiveLeftovers(page, [plot], [], kyivDay(0));
      }
    }
  });

  test('the chairman runs losses for a past range: a quarter with an unknown value is a warning, the rest is booked and the run is reversed', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const range = await pastRange(page);
    const rangeText = `${shortDay(range.from)} – ${shortDay(range.to)}`;
    const demoRun = expectCreated<{ from: string; to: string; status: string }[]>(
      await api(page, 'GET', '/losses-runs'),
      200,
    ).find((r) => r.status === 'BOOKED' && r.from !== range.from);
    if (!demoRun) throw new Error('the demo ledger has no booked losses run');
    const reverseBooked = async () => {
      const runs = await api(page, 'GET', '/losses-runs');
      if (runs.status !== 200) return;
      for (const run of JSON.parse(runs.body) as { id: string; from: string; to: string; status: string }[])
        if (run.status === 'BOOKED' && run.from === range.from && run.to === range.to)
          await api(page, 'POST', `/losses-runs/${run.id}/reverse`, { reason: 'E2E clean-up' }).catch(
            () => undefined,
          );
    };
    await reverseBooked();
    try {
      await menuLink(page, 'Charges').click();
      await main(page).getByRole('link', { name: 'Losses', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Losses' })).toBeVisible();

      // the past run of the demo is in the history, booked
      const history = main(page).getByRole('list', { name: 'Past runs' });
      await expect(
        history
          .getByRole('listitem')
          .filter({ hasText: `${shortDay(demoRun.from)} – ${shortDay(demoRun.to)}` }),
      ).toContainText('Booked');

      await main(page).getByLabel('From', { exact: true }).fill(range.from);
      await main(page).getByLabel('To', { exact: true }).fill(range.to);
      await main(page).getByRole('button', { name: 'Preview', exact: true }).click();
      const preview = main(page).locator('section', { hasText: 'Preview for' });
      await expect(preview).toContainText(rangeText);
      const nodes = preview.getByRole('table', { name: 'Losses per node' });
      await expect(nodes.getByRole('row').filter({ hasText: 'Квартал А' })).toContainText('Charged');
      await expect(nodes.getByRole('row').filter({ hasText: 'Квартал Б' })).toContainText('Unknown value');
      await expect(
        preview.getByText(/Квартал Б is skipped: .* has no reading after the end of the range/),
      ).toBeVisible();

      await preview.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(main(page).getByText(/^Charges created: [1-9]/)).toBeVisible();
      // (earlier runs of these days that were reversed stay in the history)
      const run = history.getByRole('listitem').filter({ hasText: rangeText, hasNotText: 'Reversed' });
      await expect(run).toContainText('Booked');
      await run.getByRole('button', { name: 'Details', exact: true }).click();
      await expect(run.getByText('Charges of the run')).toBeVisible();

      // the same days again: the quarter is already charged, so the whole run cannot be booked
      await main(page).getByLabel('From', { exact: true }).fill(range.from);
      await main(page).getByRole('button', { name: 'Preview', exact: true }).click();
      await expect(preview.getByText(/already been charged|charged for these days/i).first()).toBeVisible();

      // reversing the run needs a reason and cancels every charge of it
      await run.getByRole('button', { name: 'Reverse the run', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('button', { name: 'Reverse the run', exact: true }).click();
      await expect(dialog.getByText('Enter a reason')).toBeVisible();
      await dialog.getByLabel('Reason', { exact: true }).fill('E2E reversal');
      await dialog.getByRole('button', { name: 'Reverse the run', exact: true }).click();
      await expect(run).toHaveCount(0);
      await expect(history.getByRole('listitem').filter({ hasText: rangeText }).first()).toContainText(
        'Reversed',
      );
    } finally {
      await reverseBooked();
    }
  });

  test('the board sees what could not be charged and retries it', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await menuLink(page, 'Charges').click();
    await main(page).getByRole('link', { name: 'Not charged', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Not charged' })).toBeVisible();
    const table = main(page).getByRole('table', { name: 'Items not charged' });
    const plot25 = table
      .getByRole('row')
      .filter({ has: page.getByRole('link', { name: '25', exact: true }) });
    await expect(plot25).toContainText('No tariff for every day');
    await expect(
      table.getByRole('row').filter({ hasText: 'A meter value of the period is unknown' }).first(),
    ).toBeVisible();
    // the cause is not fixed, so the retry reports it and the item stays
    await plot25.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(plot25.getByText('Still not charged')).toBeVisible();
    await expect(plot25).toBeVisible();
  });

  test('the chairman enters an opening balance, reverses a charge with a reason and cannot reverse it twice', async ({
    page,
  }) => {
    const plot = { id: '', number: `E2E-C-V${Date.now().toString(36)}` };
    await loginAs(page, 'chairman@demo.local');
    try {
      plot.id = (await createPlot(page, plot.number)).id;
      await bookMonth(page, (await demoMonths(page)).open);
      await page.goto(`/plots/${plot.id}`);
      const panel = main(page).locator('section', {
        has: page.getByRole('heading', { name: 'Account', exact: true }),
      });

      // an opening balance is a charge of its own kind
      await panel.getByRole('button', { name: 'Enter an opening balance', exact: true }).click();
      const balance = page.getByRole('dialog');
      await balance.getByLabel('Debt amount, ₴', { exact: true }).fill('1250.50');
      await balance.getByLabel('Reason or document reference', { exact: true }).fill('E2E carried over');
      await balance.getByRole('button', { name: 'Add', exact: true }).click();
      await expect(panel.getByText('Opening balance added')).toBeVisible();
      await expect(panel.getByRole('listitem').filter({ hasText: 'Opening balance' }).first()).toContainText(
        '₴1,250.50',
      );

      // reverse the membership charge: a reason is required
      // (the regular charge, not the opening balance of the same charge type)
      const membership = panel
        .getByRole('list', { name: 'Plot charges' })
        .getByRole('listitem')
        .filter({ hasText: 'Membership fee', hasNotText: /Opening balance|Reversal/ });
      await expect(membership).toHaveCount(1);
      await membership.getByRole('button', { name: 'Reverse', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('button', { name: 'Reverse', exact: true }).click();
      await expect(dialog.getByText('Enter a reason')).toBeVisible();
      await dialog.getByLabel('Reason', { exact: true }).fill('Помилкове нарахування');
      await dialog.getByRole('button', { name: 'Reverse', exact: true }).click();
      await expect(panel.getByText('The charge was reversed')).toBeVisible();

      const list = panel.getByRole('list', { name: 'Plot charges' }).getByRole('listitem');
      const reversal = list.filter({ hasText: 'Reversal' }).first();
      await expect(reversal).toContainText('Membership fee');
      await expect(reversal).toContainText('−₴');
      await expect(membership).toContainText('Reversed');
      await reversal.getByRole('button', { name: 'Details', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('Помилкове нарахування');
      await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).first().click();
      // neither the reversal nor the reversed charge offers "Reverse" again
      await expect(membership.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);
      await expect(reversal.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);

      // the server refuses a second reversal, a reversal of a reversal and an empty reason
      const account = await accountOf(page, plot.id);
      const reversed = account.charges.find(
        (c) => c.kind === 'REGULAR' && !c.reversible && c.chargeType.code === 'MEMBERSHIP',
      );
      const reversalCharge = account.charges.find((c) => c.kind === 'REVERSAL');
      expect(reversed).toBeDefined();
      expect(reversalCharge).toBeDefined();
      const again = await api(page, 'POST', `/charges/${reversed?.id}/reverse`, { reason: 'again' });
      expect(again.status).toBe(409);
      expect(JSON.parse(again.body)).toMatchObject({ code: 'CHARGE_ALREADY_REVERSED' });
      const ofReversal = await api(page, 'POST', `/charges/${reversalCharge?.id}/reverse`, {
        reason: 'again',
      });
      expect(JSON.parse(ofReversal.body)).toMatchObject({ code: 'REVERSAL_NOT_REVERSIBLE' });
    } finally {
      await tidyPlot(page, plot);
    }
  });

  test('a charge booked in a closed month cannot be reversed by hand', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    const plotId = await plotIdOf(page, '14');
    const account = await accountOf(page, plotId);
    const { closed } = await demoMonths(page);
    const index = account.charges.findIndex((c) => c.billingMonth === closed);
    expect(index).toBeGreaterThanOrEqual(0);
    expect(account.charges[index]?.reversible).toBe(false);
    await page.goto(`/plots/${plotId}`);
    const row = main(page).getByRole('list', { name: 'Plot charges' }).getByRole('listitem').nth(index);
    await expect(row.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);
    await row.getByRole('button', { name: 'Details', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText(
      'The billing month is closed: the correction is made by an adjustment.',
    );
    const refused = await api(page, 'POST', `/charges/${account.charges[index]?.id}/reverse`, {
      reason: 'closed',
    });
    expect(refused.status).toBe(409);
  });

  test('the treasurer sees the charges but no action', async ({ page }) => {
    await loginAs(page, 'treasurer@demo.local');
    await expect(menuLink(page, 'Charges')).toBeVisible();
    await menuLink(page, 'Charges').click();
    const { open } = await demoMonths(page);
    await main(page)
      .getByRole('link', { name: monthLabel(open) })
      .click();
    await expect(page.getByRole('heading', { level: 1, name: monthLabel(open) })).toBeVisible();
    await expect(main(page).getByText('Before closing')).toBeVisible();
    await expect(main(page).getByRole('button')).toHaveCount(0);

    // the losses and common consumers pages show the history only
    await page.goto('/charges/losses');
    await expect(main(page).getByText('You can look at past runs here')).toBeVisible();
    await expect(main(page).getByRole('button', { name: 'Preview', exact: true })).toHaveCount(0);
    await expect(main(page).getByRole('button', { name: 'Reverse the run', exact: true })).toHaveCount(0);
    await page.goto('/charges/common');
    await expect(main(page).getByRole('button', { name: 'Preview', exact: true })).toHaveCount(0);
    await page.goto('/charges/not-charged');
    await expect(main(page).getByRole('button', { name: 'Retry', exact: true })).toHaveCount(0);

    // a plot account lists the charges and offers neither reversal nor opening balance
    const plotId = await plotIdOf(page, '7');
    await page.goto(`/plots/${plotId}`);
    const panel = main(page).locator('section', {
      has: page.getByRole('heading', { name: 'Account', exact: true }),
    });
    await expect(panel.getByRole('listitem').first()).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Enter an opening balance', exact: true })).toHaveCount(0);

    // the server refuses what the screen does not offer
    const run = await api(page, 'POST', `/billing-months/${open}/run?preview=true`);
    expect(run.status).toBe(403);
    const close = await api(page, 'POST', `/billing-months/${open}/close`);
    expect(close.status).toBe(403);
  });

  test('a member sees only the own plot account and nothing of the board screens @smoke', async ({
    page,
  }) => {
    await loginAs(page, 'member@demo.local');
    await expect(menuLink(page, 'Charges')).toHaveCount(0);
    await page.goto('/account');
    const mine = main(page).locator('section[aria-labelledby="my-charges"]');
    await expect(mine.getByRole('heading', { level: 2, name: 'My charges' })).toBeVisible();
    // exactly the plots the member owns (7 and 15 in the demo), read only: debt per charge type, the charges
    // with the calculation, no action
    const owned = expectCreated<{ ownedPlots: { number: string }[] }>(await api(page, 'GET', '/me'), 200)
      .ownedPlots.map((p) => `Plot ${p.number}`)
      .sort();
    expect(owned).toContain('Plot 7');
    await expect(mine.getByRole('heading', { name: 'Plot 7' })).toBeVisible();
    expect((await mine.getByRole('heading', { name: /^Plot / }).allInnerTexts()).sort()).toEqual(owned);
    await expect(mine.getByText('Debt by charge type').first()).toBeVisible();
    await expect(mine.getByRole('listitem').filter({ hasText: 'Membership fee' }).first()).toBeVisible();
    await expect(mine.getByRole('listitem').filter({ hasText: 'Electricity' }).first()).toBeVisible();
    await expect(mine.getByRole('listitem').filter({ hasText: 'Common consumers' }).first()).toBeVisible();
    await expect(mine.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);
    await expect(mine.getByRole('button', { name: 'Enter an opening balance', exact: true })).toHaveCount(0);
    await mine.getByRole('button', { name: 'Details', exact: true }).first().click();
    await expect(page.getByRole('dialog').getByText('Calculation')).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).first().click();

    // the board screens are closed to the member, and so is another plot's account
    await page.goto('/charges');
    await expect(page.getByRole('heading', { level: 1, name: 'Charges' })).toHaveCount(0);
    expect((await api(page, 'GET', '/billing-months')).status).toBe(403);
    expect((await api(page, 'GET', '/not-charged')).status).toBe(403);
    const other = await api(page, 'GET', `/plots/00000000-0000-0000-0000-000000000000/account`);
    expect([403, 404]).toContain(other.status);
  });
});
