// @layer: e2e
// @spec: 015-payments-and-allocation
// @regression
// Payments in a browser: the treasurer enters a cash payment from the plot card (proposal, receipt, the plot account
// with the payment and the lower debt), splits one payment over two plots of one payer (one receipt, a link per part),
// the chairman reverses a payment with a reason (the treasurer has no "Reverse"), and a member sees a payment on the
// account. Every test CREATES its own plots (unique number), owner and opening balance through the API, so the specs do
// not depend on the demo payments or on each other, and cleans them up. The requirement names demo plot 9 for the first
// flow; a fresh plot keeps the spec re-runnable (a payment of plot 9 would lower its demo debt for good). The receipt
// number is never a fixed value: it is read from the screen, and the success screen and the card must show the same one.
import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  createPlot,
  expectCreated,
  kyivDay,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');
const DEBT_SECTION = 'section[aria-label="Debt after payments, by charge type"]';

async function signedInPage(browser: Browser, email: string) {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

interface SavedPayment {
  id: string;
  plotId: string;
  receiptNumber?: number;
}

/** A fresh plot with an owner and an opening balance of `debt` (membership fee), made with the chairman's token. */
async function plotWithDebt(
  chairman: Page,
  number: string,
  debt: string,
  owner?: CreatedMember,
): Promise<CreatedPlot> {
  const plot = await createPlot(chairman, number);
  if (owner) await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId: owner.id, share: '1/1' }]);
  const types = expectCreated<{ id: string; code?: string }[]>(
    await api(chairman, 'GET', '/charge-types'),
    200,
  );
  const membership = types.find((type) => type.code === 'MEMBERSHIP');
  if (!membership) throw new Error('the membership charge type is missing');
  expectCreated(
    await api(chairman, 'POST', `/plots/${plot.id}/opening-balances`, {
      chargeTypeId: membership.id,
      amount: debt,
      date: kyivDay(-3),
      reason: 'E2E opening balance',
    }),
  );
  return plot;
}

/** A cash payment saved through the API (the chairman has every permission). */
async function cashPayment(chairman: Page, plotId: string, amount: string): Promise<SavedPayment> {
  const result = expectCreated<{ payments: SavedPayment[] }>(
    await api(chairman, 'POST', '/payments?preview=false', {
      plotId,
      amount,
      receivedOn: kyivDay(0),
      source: 'CASH',
    }),
  );
  const payment = result.payments[0];
  if (!payment) throw new Error('no payment was saved');
  return payment;
}

/** Best effort: reverses the plot's payments and charges, then ends the ownership and archives plots and members. */
async function tidy(chairman: Page, plots: CreatedPlot[], members: CreatedMember[]): Promise<void> {
  for (const plot of plots) {
    if (!plot.id) continue;
    const listed = await api(chairman, 'GET', `/payments?plotId=${plot.id}&size=100`).catch(() => undefined);
    if (listed?.status === 200)
      for (const payment of (JSON.parse(listed.body) as { items: { id: string; reversed: boolean }[] }).items)
        if (!payment.reversed)
          await api(chairman, 'POST', `/payments/${payment.id}/reverse`, { reason: 'E2E clean-up' }).catch(
            () => undefined,
          );
    const account = await api(chairman, 'GET', `/plots/${plot.id}/account`).catch(() => undefined);
    if (account?.status === 200)
      for (const charge of (JSON.parse(account.body) as { charges: { id: string; reversible: boolean }[] })
        .charges)
        if (charge.reversible)
          await api(chairman, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
            () => undefined,
          );
  }
  await archiveLeftovers(chairman, plots, members, kyivDay(0));
}

/** The receipt number printed as "Receipt № 12" or "№ 12". */
function receiptOf(text: string | null): number {
  const found = /№\s*(\d+)/.exec(text ?? '');
  if (!found) throw new Error(`no receipt number in "${text}"`);
  return Number(found[1]);
}

test.describe('payments', () => {
  test('the treasurer enters a cash payment from the plot card and sees it on the plot account with the lower debt @smoke', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const plot: CreatedPlot = { id: '', number: `E2E-P-C${Date.now().toString(36)}` };
    const member = await createMember(chairman, `E2ePc${Date.now().toString(36)}`);
    try {
      Object.assign(plot, await plotWithDebt(chairman, plot.number, '1000.00', member));
      await loginAs(page, 'treasurer@demo.local');
      await page.goto(`/plots/${plot.id}`);
      await page.getByRole('link', { name: 'Enter payment', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Enter payment' })).toBeVisible();

      // the proposal fills the open charge as soon as the amount is typed
      await page.getByLabel('Amount', { exact: true }).fill('400.00');
      const proposal = page.getByRole('table', { name: 'Allocation of the payment to charges' });
      await expect(proposal).toBeVisible();
      await expect(proposal).toContainText('Membership fee');
      await expect(proposal.getByLabel(/^Amount to allocate:/)).toHaveValue('400.00');
      await page.getByRole('button', { name: 'Save', exact: true }).click();

      // the receipt number, large, and the same one on the payment card
      const receipt = page.getByTestId('receipt-number');
      await expect(receipt).toHaveText(/^Receipt № \d+$/);
      const number = receiptOf(await receipt.textContent());
      await page.getByRole('link', { name: 'Open the payment card', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Receipt № ${number}` })).toBeVisible();
      await expect(main(page)).toContainText('400.00');

      // the plot account shows the payment and the debt that is left
      await main(page).getByRole('link', { name: plot.number, exact: true }).first().click();
      await expect(page.getByRole('heading', { level: 1, name: new RegExp(plot.number) })).toBeVisible();
      const account = main(page).locator('section', {
        has: page.getByRole('heading', { name: 'Account', exact: true }),
      });
      await expect(account.getByText(`№ ${number}`, { exact: true })).toBeVisible();
      await expect(account.locator(DEBT_SECTION)).toContainText('600.00');
    } finally {
      await tidy(chairman, [plot], [member]);
      await chairman.context().close();
    }
  });

  test('the treasurer splits one payment over two plots of one payer: one receipt, a link per part, each card shows the other part', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const stamp = Date.now().toString(36);
    const payer = await createMember(chairman, `E2ePs${stamp}`);
    const plotA: CreatedPlot = { id: '', number: `E2E-P-A${stamp}` };
    const plotB: CreatedPlot = { id: '', number: `E2E-P-B${stamp}` };
    try {
      Object.assign(plotA, await plotWithDebt(chairman, plotA.number, '600.00', payer));
      Object.assign(plotB, await plotWithDebt(chairman, plotB.number, '400.00', payer));
      await loginAs(page, 'treasurer@demo.local');
      await page.goto('/payments/new');
      await page.getByRole('radio', { name: 'Several plots of one payer' }).check();
      await page.getByLabel('Payer', { exact: true }).fill(payer.lastName);
      await page.getByRole('button', { name: new RegExp(payer.lastName) }).click();
      await page.getByLabel('Amount', { exact: true }).fill('1000.00');

      // the proposal splits the payment in proportion to the debts: one part per plot of the payer
      const parts = page.getByRole('table', { name: 'Parts of the payment over the plots of the payer' });
      await expect(parts.getByLabel(`Part of the payment for plot ${plotA.number}`)).toHaveValue('600.00');
      await expect(parts.getByLabel(`Part of the payment for plot ${plotB.number}`)).toHaveValue('400.00');
      await page.getByRole('button', { name: 'Save', exact: true }).click();

      // one receipt for the group and a link per part
      const receipt = page.getByTestId('receipt-number');
      await expect(receipt).toHaveText(/^Receipt № \d+$/);
      const number = receiptOf(await receipt.textContent());
      await expect(page.getByRole('link', { name: `Plot ${plotA.number}`, exact: true })).toBeVisible();
      await expect(page.getByRole('link', { name: `Plot ${plotB.number}`, exact: true })).toBeVisible();

      // each card carries the same receipt and lists the other part
      await page.getByRole('link', { name: `Plot ${plotA.number}`, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Receipt № ${number}` })).toBeVisible();
      const others = main(page).locator('section', {
        has: page.getByRole('heading', { name: 'Other parts of this payment' }),
      });
      await expect(others.getByRole('link', { name: plotB.number, exact: true })).toBeVisible();
      await expect(others.getByRole('link', { name: plotA.number, exact: true })).toHaveCount(0);
      await others.getByRole('link', { name: plotB.number, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Receipt № ${number}` })).toBeVisible();
      await expect(
        main(page)
          .locator('section', { has: page.getByRole('heading', { name: 'Other parts of this payment' }) })
          .getByRole('link', { name: plotA.number, exact: true }),
      ).toBeVisible();
    } finally {
      await tidy(chairman, [plotA, plotB], [payer]);
      await chairman.context().close();
    }
  });

  test('the chairman reverses a payment with a reason; the treasurer sees no Reverse', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    const plot: CreatedPlot = { id: '', number: `E2E-P-R${Date.now().toString(36)}` };
    try {
      Object.assign(plot, await plotWithDebt(chairman, plot.number, '500.00'));
      const toReverse = await cashPayment(chairman, plot.id, '200.00');
      const kept = await cashPayment(chairman, plot.id, '100.00');

      // a treasurer opens the card of a payment that can be reversed and is not offered the action
      await treasurer.goto(`/payments/${kept.id}`);
      await expect(
        treasurer.getByRole('heading', { level: 1, name: `Receipt № ${kept.receiptNumber}` }),
      ).toBeVisible();
      await expect(treasurer.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);

      // the chairman reverses the other one: a reason is required, then the card shows the reversal
      await loginAs(page, 'chairman@demo.local');
      await page.goto(`/payments/${toReverse.id}`);
      await expect(
        page.getByRole('heading', { level: 1, name: `Receipt № ${toReverse.receiptNumber}` }),
      ).toBeVisible();
      await page.getByRole('button', { name: 'Reverse', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('button', { name: 'Reverse', exact: true }).click();
      await expect(dialog.getByText('Enter a reason')).toBeVisible();
      await dialog.getByLabel('Reason', { exact: true }).fill('Внесено помилково');
      await dialog.getByRole('button', { name: 'Reverse', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(main(page).getByText('Reversed', { exact: true }).first()).toBeVisible();
      await expect(main(page)).toContainText('Reversal reason');
      await expect(main(page)).toContainText('Внесено помилково');
      await expect(page.getByRole('button', { name: 'Reverse', exact: true })).toHaveCount(0);
    } finally {
      await tidy(chairman, [plot], []);
      await chairman.context().close();
      await treasurer.context().close();
    }
  });

  test('the treasurer reallocates a payment: an intermediate exceeding value keeps the table and the problem, a corrected one saves', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const plot: CreatedPlot = { id: '', number: `E2E-P-L${Date.now().toString(36)}` };
    try {
      Object.assign(plot, await plotWithDebt(chairman, plot.number, '500.00'));
      const payment = await cashPayment(chairman, plot.id, '200.00');

      await loginAs(page, 'treasurer@demo.local');
      await page.goto(`/payments/${payment.id}`);
      await expect(main(page).getByRole('heading', { name: 'Allocated now' })).toBeVisible();
      await expect(main(page).getByText('Allocation history')).toBeVisible();
      await main(page).getByRole('button', { name: 'Reallocate', exact: true }).click();

      const dialog = page.getByRole('dialog');
      const table = dialog.getByRole('table', { name: 'Allocation of the payment to charges' });
      const line = table.getByLabel(/^Amount to allocate:/);
      const submit = dialog.getByRole('button', { name: 'Reallocate', exact: true });
      await expect(line).toHaveValue('200.00');
      await expect(submit).toBeEnabled();

      // an intermediate value above the open amount: the table stays, the problem shows by the line, no save
      await line.fill('9999');
      await expect(dialog.getByText('The amount exceeds what is open on the charge')).toBeVisible();
      await expect(table).toBeVisible();
      await expect(line).toHaveValue('9999');
      await expect(submit).toBeDisabled();
      await expect(dialog.getByRole('status')).toHaveCount(0);

      // corrected: the problem goes, the button is back, and the reallocation saves
      await line.fill('150');
      await expect(dialog.getByText('The amount exceeds what is open on the charge')).toHaveCount(0);
      await expect(submit).toBeEnabled();
      await submit.click();
      await expect(dialog).toHaveCount(0);
      await expect(main(page).getByRole('heading', { name: 'Allocated now' })).toBeVisible();
      await main(page).getByText('Allocation history').click();
      await expect(main(page).getByRole('cell', { name: 'Reallocation' }).first()).toBeVisible();
    } finally {
      await tidy(chairman, [plot], []);
      await chairman.context().close();
    }
  });

  test('a member sees a payment of their plot on the account with its receipt number', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    let paymentId = '';
    try {
      // the demo member's plot 7: a small cash payment, reversed again at the end (a reversal nets it to zero)
      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(chairman, 'GET', '/plots?q=7'),
        200,
      );
      const plot7 = found.items.find((p) => p.number === '7');
      if (!plot7) throw new Error('demo plot 7 not found');
      const payment = await cashPayment(chairman, plot7.id, '1.00');
      paymentId = payment.id;
      const number = payment.receiptNumber;
      expect(number).toBeGreaterThan(0);

      await loginAs(page, 'member@demo.local');
      await page.goto('/account');
      await expect(main(page).getByText(`№ ${number}`, { exact: true }).first()).toBeVisible();
    } finally {
      if (paymentId)
        await api(chairman, 'POST', `/payments/${paymentId}/reverse`, { reason: 'E2E clean-up' }).catch(
          () => undefined,
        );
      await chairman.context().close();
    }
  });
});
