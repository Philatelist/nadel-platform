// @layer: e2e
// @spec: 018-cash-book-expenses-reconciliation
// @regression
// Flow 4 of the requirement: the member downloads the reconciliation act of their own plot from the account, as a PDF;
// another plot's act is not available to them ("not found"); a period that ends after today is refused on the form.
// The plots are not named by number: the member's own plots are those the account shows, and "another plot" is one of
// the cooperative's plots for which the server answers the member 404, found at run time with the chairman's list.
import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { api, expectCreated, kyivDay } from './support/api';
import { signedInPage } from './support/cashbook';

const main = (page: Page) => page.getByRole('main');

/**
 * The earliest day the server accepts as the start of the plot's act (the day of its first charge or payment; the form's
 * default, the 1st of January, is too early in a young ledger such as the demo). Found by asking, a day at a time from
 * 100 days back; the answer is one of the bounds of the period, so asking is cheaper than guessing the ledger.
 */
async function earliestStart(page: Page, plotId: string): Promise<string> {
  for (let back = 100; back >= 0; back -= 1) {
    const from = kyivDay(-back);
    const answer = await api(
      page,
      'GET',
      `/plots/${plotId}/reconciliation-act.pdf?from=${from}&to=${kyivDay(0)}`,
    );
    if (answer.status === 200) return from;
  }
  throw new Error(`no start day in the last 100 days gives an act of plot ${plotId}`);
}

test.describe('reconciliation act', () => {
  test('the member downloads the act of their own plot from the account; another plot is not found', async ({
    browser,
  }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      // the cooperative's plots; the member is refused some and given others
      const plots = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(chairman, 'GET', '/plots?size=100'),
        200,
      ).items;
      expect(plots.length).toBeGreaterThan(1);
      const period = `from=${kyivDay(-30)}&to=${kyivDay(0)}`;
      const own: string[] = [];
      let notFound = 0;
      for (const plot of plots) {
        const answer = await api(member, 'GET', `/plots/${plot.id}/reconciliation-act.pdf?${period}`);
        if (answer.status === 404) {
          notFound += 1;
          expect(expectCreated<{ code: string }>(answer, 404).code).toBe('PLOT_NOT_FOUND');
        } else if (answer.status === 200 || answer.status === 400) own.push(plot.id);
      }
      expect(notFound, 'the member is refused the act of the plots they do not own').toBeGreaterThan(0);
      expect(own.length, 'but is given the act of their own plot').toBeGreaterThan(0);
      expect(own.length).toBeLessThan(plots.length);
      const starts = await Promise.all(own.map((id) => earliestStart(member, id)));
      const from = starts.sort().at(-1) ?? kyivDay(-30);

      // the account offers the act for each own plot; the form defaults to a period up to today
      await member.goto('/account');
      const buttons = main(member).getByRole('button', { name: 'Reconciliation act', exact: true });
      await expect(buttons.first()).toBeVisible();
      await expect(buttons).toHaveCount(own.length);
      await buttons.first().click();
      // the act's own form: the charges panel above it has From and To fields of its own
      const actForm = main(member)
        .locator('form')
        .filter({ has: member.getByRole('button', { name: 'Download the reconciliation act', exact: true }) })
        .first();
      await expect(actForm.getByLabel('To', { exact: true })).toHaveValue(kyivDay(0));
      await actForm.getByLabel('From', { exact: true }).fill(from);
      const download = member.waitForEvent('download');
      await actForm.getByRole('button', { name: 'Download the reconciliation act', exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe(`reconciliation-act_${from}_${kyivDay(0)}.pdf`);
      const bytes = await readFile(await file.path());
      expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
      expect(bytes.length).toBeGreaterThan(1000);

      // a period that ends after today is refused on the form
      await actForm.getByLabel('To', { exact: true }).fill(kyivDay(5));
      await actForm.getByRole('button', { name: 'Download the reconciliation act', exact: true }).click();
      await expect(actForm.getByText('Invalid period')).toBeVisible();
    } finally {
      await member.context().close();
      await chairman.context().close();
    }
  });

  test('the board downloads the act of a plot from the plot card', async ({ browser }) => {
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      const plots = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(treasurer, 'GET', '/plots?q=7'),
        200,
      ).items;
      const plot = plots.find((p) => p.number === '7');
      if (!plot) throw new Error('demo plot 7 not found');
      const from = await earliestStart(treasurer, plot.id);
      await treasurer.goto(`/plots/${plot.id}`);
      await treasurer.getByRole('tab', { name: 'Reconciliation act' }).click();
      await main(treasurer).getByLabel('From', { exact: true }).fill(from);
      const download = treasurer.waitForEvent('download');
      await main(treasurer)
        .getByRole('button', { name: 'Download the reconciliation act', exact: true })
        .click();
      const file = await download;
      const bytes = await readFile(await file.path());
      expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    } finally {
      await treasurer.context().close();
    }
  });
});
