// @layer: e2e
// @spec: 018-cash-book-expenses-reconciliation
// @regression
// Flow 3 of the requirement: a month to «Reconciled». The month is the current month, read from the API at run time (the
// demo cooperative waits for its reconciliation with a bank fee nobody entered). The treasurer types a statement that
// differs from the books by a fee of the spec's own, sees the differences and that «Reconciled» cannot be pressed; the
// treasurer has no "Bank difference"; the chairman books the fee as a bank difference; the treasurer marks the month
// reconciled. The statement is built from the books the server shows, so the spec holds whatever other specs entered.
// A reconciled month cannot be changed: a later bank line dated in it sets it back to «Needs a new check», which the
// last test shows with a payment of a fresh plot dated in the month. Serial: the tests share the month.
import { expect, test, type Page } from '@playwright/test';
import { formatMonth } from '@nadel/shared';
import { api, createPlot, expectCreated, kyivDay, shiftDay } from './support/api';
import { accountCards, cents, signedInPage } from './support/cashbook';

const main = (page: Page) => page.getByRole('main');

function money(hundredths: number): string {
  const negative = hundredths < 0;
  const abs = Math.abs(hundredths);
  return `${negative ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

interface Figures {
  opening: string;
  incoming: string;
  outgoing: string;
  closing: string;
}
interface Reconciliation {
  status: 'NOT_RECONCILED' | 'RECONCILED' | 'NEEDS_RECHECK';
  books: Figures;
  balanced: boolean;
}

test.describe.configure({ mode: 'serial' });

test.describe('bank reconciliation of a month', () => {
  const month = kyivDay(0).slice(0, 7);
  const shortMonth = formatMonth(month, 'en'); // "Nov 2026", as the month list prints it
  const fee = 1100 + (Date.now() % 90); // 11.00 .. 11.89

  test('the treasurer enters a statement with an unexplained fee: the differences show and the month cannot be marked reconciled', async ({
    browser,
  }) => {
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      const books = expectCreated<Reconciliation>(
        await api(treasurer, 'GET', `/bank-reconciliations/${month}`),
        200,
      ).books;

      // the bank card lists the month with its status and opens it
      await treasurer.goto('/cashbook');
      const months = treasurer.getByTestId('bank-months');
      await expect(months).toContainText(shortMonth);
      // the demo cooperative (FS 2.16): the month before is reconciled, the current month waits for its reconciliation
      const previous = formatMonth(shiftDay(`${month}-01`, -1).slice(0, 7), 'en');
      await expect(
        months.locator('li', { hasText: previous }).getByText('Reconciled', { exact: true }),
      ).toBeVisible();
      await months.getByRole('link', { name: shortMonth, exact: true }).click();
      await expect(treasurer).toHaveURL(new RegExp(`/cashbook/bank/${month}$`));

      // a statement that does not add up is refused on the screen
      await treasurer.getByLabel('Balance at the start of the month').fill('100.00');
      await treasurer.getByLabel('Receipts').fill('50.00');
      await treasurer.getByLabel('Spendings').fill('20.00');
      await treasurer.getByLabel('Balance at the end of the month').fill('200.00');
      await treasurer.getByRole('button', { name: 'Save the statement', exact: true }).click();
      await expect(main(treasurer).getByText(/The statement figures do not agree/)).toBeVisible();

      // the statement of the books plus a bank fee nobody entered
      await treasurer.getByLabel('Balance at the start of the month').fill(books.opening);
      await treasurer.getByLabel('Receipts').fill(books.incoming);
      await treasurer.getByLabel('Spendings').fill(money(cents(books.outgoing) + fee));
      await treasurer.getByLabel('Balance at the end of the month').fill(money(cents(books.closing) - fee));
      await treasurer.getByRole('button', { name: 'Save the statement', exact: true }).click();

      await expect(treasurer.getByTestId('figure-outgoing')).toContainText(money(fee));
      await expect(treasurer.getByTestId('figure-closing')).toContainText(money(fee));
      await expect(main(treasurer).getByRole('button', { name: 'Reconciled', exact: true })).toBeDisabled();
      await expect(main(treasurer).getByText('Available when every difference is 0.00.')).toBeVisible();
      // the treasurer may not book a difference
      await expect(main(treasurer).getByRole('button', { name: 'Bank difference', exact: true })).toHaveCount(
        0,
      );
      const state = expectCreated<Reconciliation>(
        await api(treasurer, 'GET', `/bank-reconciliations/${month}`),
        200,
      );
      expect(state.balanced).toBe(false);
      expect(
        (
          await api(treasurer, 'POST', `/bank-reconciliations/${month}/book-difference`, {
            amount: '-1.00',
            reason: 'x',
          })
        ).status,
      ).toBe(403);
    } finally {
      await treasurer.context().close();
    }
  });

  test('the chairman books the fee as a bank difference; the treasurer then marks the month reconciled', async ({
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      await chairman.goto(`/cashbook/bank/${month}`);
      await main(chairman).getByRole('button', { name: 'Bank difference', exact: true }).click();
      const dialog = chairman.getByRole('dialog');
      await dialog.getByLabel('Amount (negative: money out)').fill(money(-fee));
      await dialog.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(dialog.getByText('Enter a reason.')).toBeVisible();
      await dialog.getByLabel('Reason').fill('E2E: bank fee on the statement');
      await dialog.getByRole('button', { name: 'Book', exact: true }).click();
      await expect(chairman.getByRole('dialog')).toHaveCount(0);
      await expect(main(chairman).getByText('Bank difference').first()).toBeVisible();

      // every difference is 0.00 now; the treasurer marks the month reconciled
      await treasurer.goto(`/cashbook/bank/${month}`);
      const confirm = main(treasurer).getByRole('button', { name: 'Reconciled', exact: true });
      await expect(confirm).toBeEnabled();
      await confirm.click();
      await expect(main(treasurer).getByText(/^Reconciled by /)).toBeVisible();
      await expect(
        main(treasurer).getByText('The month is reconciled: its statement cannot be changed.'),
      ).toBeVisible();

      // the month list on the bank card shows «Reconciled»
      await treasurer.goto('/cashbook');
      await expect(
        treasurer.getByTestId('bank-months').locator('li', { hasText: shortMonth }).getByText('Reconciled'),
      ).toBeVisible();
      const cards = await accountCards(treasurer);
      expect(cards.BANK.months?.find((m) => m.month === month)?.status).toBe('RECONCILED');
    } finally {
      await chairman.context().close();
      await treasurer.context().close();
    }
  });

  test('a bank payment dated in the reconciled month and entered later sends it back to «Needs a new check»', async ({
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      const status = async () =>
        expectCreated<Reconciliation>(await api(chairman, 'GET', `/bank-reconciliations/${month}`), 200)
          .status;
      test.skip((await status()) !== 'RECONCILED', 'the month is not reconciled');

      const plot = await createPlot(chairman, `E2E-BR${Date.now().toString(36)}`);
      const payment = expectCreated<{ payments: { id: string }[] }>(
        await api(chairman, 'POST', '/payments?preview=false', {
          plotId: plot.id,
          amount: '1.00',
          receivedOn: `${month}-01`,
          source: 'BANK',
        }),
      );
      try {
        expect(await status()).toBe('NEEDS_RECHECK');
        await chairman.goto('/cashbook');
        await expect(
          chairman
            .getByTestId('bank-months')
            .locator('li', { hasText: shortMonth })
            .getByText('Needs a new check'),
        ).toBeVisible();
        await chairman.goto(`/cashbook/bank/${month}`);
        await expect(main(chairman).getByText(/A line was added after the reconciliation/)).toBeVisible();
      } finally {
        const id = payment.payments[0]?.id;
        if (id)
          await api(chairman, 'POST', `/payments/${id}/reverse`, { reason: 'E2E clean-up' }).catch(
            () => undefined,
          );
        await api(chairman, 'POST', `/plots/${plot.id}/archive`).catch(() => undefined);
      }
    } finally {
      await chairman.context().close();
    }
  });
});
