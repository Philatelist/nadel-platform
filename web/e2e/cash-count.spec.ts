// @layer: e2e
// @spec: 018-cash-book-expenses-reconciliation
// @regression
// Flow 2 of the requirement: the treasurer counts the cash and finds a shortage; the count is saved with its explanation
// and the cash card shows the unresolved difference; the treasurer has no "Book the difference"; the chairman books it
// and the cash falls by exactly the shortage. The shortage is a few kopiykas below the book balance read from the
// server, so the spec does not depend on the demo amounts; the check is a delta. Serial: the second test books the count
// the first one saved.
import { expect, test, type Page } from '@playwright/test';
import { api, expectCreated } from './support/api';
import { accountCards, amountDigits, cents, digitsOf, signedInPage } from './support/cashbook';

const main = (page: Page) => page.getByRole('main');

function money(hundredths: number): string {
  const negative = hundredths < 0;
  const abs = Math.abs(hundredths);
  return `${negative ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

test.describe.configure({ mode: 'serial' });

test.describe('cash count with a difference, booked by the chairman', () => {
  const shortage = 700 + (Date.now() % 90); // 7.00 .. 7.89
  let countPath = '';
  let cashBefore = 0;

  test('the treasurer counts less cash than the book shows; the count is unresolved and cannot be booked by the treasurer', async ({
    browser,
  }) => {
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      test.setTimeout(120_000);
      const cards = await accountCards(treasurer);
      cashBefore = cents(cards.CASH.balance);
      // the cash card shows the LAST count by its time, which the form rounds to the minute: start in a minute after the
      // latest count (the demo's count is written at start-up), so that this count is the last one
      if (cards.CASH.lastCount) {
        const startMinute = Math.floor(Date.parse(cards.CASH.lastCount.countedAt) / 60_000) * 60_000 + 60_000;
        const wait = startMinute - Date.now();
        if (wait > 0) await treasurer.waitForTimeout(wait + 1_000);
      }
      await treasurer.goto('/cashbook');
      await main(treasurer).getByRole('link', { name: 'Cash count', exact: true }).click();
      await expect(treasurer).toHaveURL(/\/cashbook\/counts\/new$/);

      // the server previews the book balance and the difference while the amount is typed
      await treasurer.getByLabel('Cash actually counted').fill(money(cashBefore - shortage));
      const preview = treasurer.getByTestId('count-preview');
      await expect(preview).toBeVisible();
      await expect
        .poll(async () => digitsOf(await preview.textContent()))
        .toContain(amountDigits(money(cashBefore)));
      await expect(preview).toContainText(money(shortage));

      // a difference needs an explanation: saving without one is refused on the screen
      await treasurer.getByRole('button', { name: 'Save the count', exact: true }).click();
      await expect(treasurer.getByText('Explain the difference.')).toBeVisible();
      await expect(treasurer).toHaveURL(/\/cashbook\/counts\/new$/);
      await treasurer.getByLabel('Explanation of the difference').fill('E2E: change given back by mistake');
      await treasurer.getByRole('button', { name: 'Save the count', exact: true }).click();

      // the saved count: unresolved, with its act, and no way for the treasurer to book it
      await expect(treasurer).toHaveURL(/\/cashbook\/counts\/[0-9a-f-]{36}$/);
      countPath = new URL(treasurer.url()).pathname;
      await expect(main(treasurer).getByText('Unresolved difference').first()).toBeVisible();
      await expect(
        main(treasurer).getByRole('button', { name: 'Cash count act', exact: true }),
      ).toBeVisible();
      await expect(main(treasurer).getByRole('button', { name: 'Book the difference' })).toHaveCount(0);

      // the cash card shows the unresolved difference; the balance has not moved yet
      await treasurer.goto('/cashbook');
      await expect(treasurer.getByText(/Unresolved difference/).first()).toBeVisible();
      const afterCount = await accountCards(treasurer);
      expect(afterCount.CASH.unresolvedDifference).toBe(money(-shortage));
      expect(cents(afterCount.CASH.balance)).toBe(cashBefore);

      // the act downloads as a PDF
      await treasurer.goto(countPath);
      const download = treasurer.waitForEvent('download');
      await main(treasurer).getByRole('button', { name: 'Cash count act', exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toMatch(/^cash_count_\d{4}-\d{2}-\d{2}\.pdf$/);

      // the server also refuses the treasurer
      const id = countPath.split('/').pop();
      expect(
        (await api(treasurer, 'POST', `/cash-counts/${id}/book-difference`, { reason: 'Not allowed' }))
          .status,
      ).toBe(403);
    } finally {
      await treasurer.context().close();
    }
  });

  test('the chairman books the shortage; the cash falls by exactly that amount and the unresolved difference disappears', async ({
    browser,
  }) => {
    test.skip(countPath === '', 'the count of the previous test was not saved');
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      await chairman.goto(countPath);
      await expect(main(chairman).getByText('Unresolved difference').first()).toBeVisible();
      await main(chairman).getByRole('button', { name: 'Book the difference', exact: true }).click();
      const dialog = chairman.getByRole('dialog');
      await dialog.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(dialog.getByText('Enter a reason.')).toBeVisible();
      await dialog.getByLabel('Reason').fill('E2E: shortage confirmed by the act');
      await dialog.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(chairman.getByRole('dialog')).toHaveCount(0);

      await expect(main(chairman).getByText(/Booked as a shortage of/)).toBeVisible();
      await expect(main(chairman).getByRole('button', { name: 'Book the difference' })).toHaveCount(0);

      const cards = await accountCards(chairman);
      expect(cents(cards.CASH.balance)).toBe(cashBefore - shortage);
      expect(cards.CASH.unresolvedDifference).toBeUndefined();

      // the cash card on the cash book no longer warns
      await chairman.goto('/cashbook');
      await expect(chairman.getByTestId('last-count-CASH')).toBeVisible();
      await expect(chairman.getByText(/^Unresolved difference/)).toHaveCount(0);
      await expect
        .poll(async () => digitsOf(await chairman.getByTestId('balance-CASH').textContent()))
        .toBe(amountDigits(cards.CASH.balance ?? ''));

      // a difference is booked once
      const id = countPath.split('/').pop();
      const again = await api(chairman, 'POST', `/cash-counts/${id}/book-difference`, { reason: 'Twice' });
      expect(again.status).toBe(409);
      expect(expectCreated<{ code: string }>(again, 409).code).toBe('DIFFERENCE_ALREADY_BOOKED');
    } finally {
      await chairman.context().close();
    }
  });
});
