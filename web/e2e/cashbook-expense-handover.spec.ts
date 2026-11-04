// @layer: e2e
// @spec: 018-cash-book-expenses-reconciliation
// @regression
// Flow 1 of the requirement: the treasurer records a cash expense with a photo of the receipt and hands cash over to the
// bank; the cash balance follows. Works on the demo cooperative (whose opening balances are written once), so the
// amounts are small and the checks are DELTAS of the balances read from the API before and after, never absolutes. The
// amounts carry a few kopiykas that differ per run so that the new journal lines are found by their amount.
import { expect, test, type Page } from '@playwright/test';
import { api, expectCreated, kyivDay } from './support/api';
import { accountCards, amountDigits, cents, digitsOf, RECEIPT_PNG, signedInPage } from './support/cashbook';

const main = (page: Page) => page.getByRole('main');

function money(hundredths: number): string {
  return `${Math.trunc(hundredths / 100)}.${String(hundredths % 100).padStart(2, '0')}`;
}

test.describe('cash book: expense with a photo and a handover', () => {
  test('the treasurer records a cash expense with a photo and hands cash over to the bank; the cash balance follows @smoke', async ({
    browser,
  }) => {
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      const unique = Date.now() % 90; // 0..89 kopiykas
      const expenseCents = 1200 + unique; // 12.00 .. 12.89
      const handoverCents = 500 + unique; // 5.00 .. 5.89
      const slip = `KV-E2E-${Date.now().toString(36)}`;
      const before = await accountCards(treasurer);

      // the cash book: both balances are shown, the treasurer may enter
      await treasurer.goto('/cashbook');
      await expect(main(treasurer).getByRole('heading', { name: 'Cash and bank', level: 1 })).toBeVisible();
      await expect(treasurer.getByTestId('balance-CASH')).toBeVisible();
      await expect(treasurer.getByTestId('balance-BANK')).toBeVisible();

      // a cash expense of «Materials» with a photo of the receipt
      await main(treasurer).getByRole('link', { name: 'Expense', exact: true }).click();
      await expect(treasurer).toHaveURL(/\/cashbook\/expenses\/new$/);
      await treasurer.getByLabel('Account').selectOption({ label: 'Cash' });
      await treasurer.getByLabel('Amount').fill(money(expenseCents));
      await treasurer.getByLabel('Expense category').selectOption({ label: 'Materials' });
      await treasurer.getByLabel('Paid to').fill('E2E Supplier');
      await treasurer.getByTestId('file-input').setInputFiles({
        name: 'receipt-e2e.png',
        mimeType: 'image/png',
        buffer: RECEIPT_PNG,
      });
      await expect(treasurer.getByText('receipt-e2e.png')).toBeVisible();
      await treasurer.getByRole('button', { name: 'Record expense', exact: true }).click();

      // the expense card shows the photo
      await expect(treasurer).toHaveURL(/\/cashbook\/expenses\/[0-9a-f-]{36}$/);
      await expect(main(treasurer).getByText('receipt-e2e.png')).toBeVisible();
      await expect(main(treasurer).getByRole('button', { name: 'Download', exact: true })).toBeVisible();
      // the treasurer has no right to approve: no reversal on the card
      await expect(main(treasurer).getByRole('button', { name: 'Reverse and record again' })).toHaveCount(0);

      // the expense is in the list with one file, the cash is down by exactly the amount
      await treasurer.goto('/cashbook/expenses');
      await expect(main(treasurer).getByText('E2E Supplier').first()).toBeVisible();
      const afterExpense = await accountCards(treasurer);
      expect(cents(afterExpense.CASH.balance)).toBe(cents(before.CASH.balance) - expenseCents);
      expect(cents(afterExpense.BANK.balance)).toBe(cents(before.BANK.balance));

      // a handover to the bank
      await treasurer.goto('/cashbook');
      await treasurer.getByRole('button', { name: 'Hand over to the bank', exact: true }).click();
      const dialog = treasurer.getByRole('dialog');
      await dialog.getByLabel('Amount').fill(money(handoverCents));
      await dialog.getByLabel('Bank slip number').fill(slip);
      await dialog.getByRole('button', { name: 'Record the handover', exact: true }).click();
      await expect(treasurer.getByRole('dialog')).toHaveCount(0);

      // the screen follows the server: cash down by expense + handover, bank up by the handover
      const after = await accountCards(treasurer);
      expect(cents(after.CASH.balance)).toBe(cents(before.CASH.balance) - expenseCents - handoverCents);
      expect(cents(after.BANK.balance)).toBe(cents(before.BANK.balance) + handoverCents);
      await expect
        .poll(async () => digitsOf(await treasurer.getByTestId('balance-CASH').textContent()))
        .toBe(amountDigits(after.CASH.balance ?? ''));
      await expect
        .poll(async () => digitsOf(await treasurer.getByTestId('balance-BANK').textContent()))
        .toBe(amountDigits(after.BANK.balance ?? ''));

      // both journals carry the handover with its slip number (the API journal of today)
      const today = kyivDay(0);
      for (const kind of ['CASH', 'BANK'] as const) {
        const list = expectCreated<{ id: string; kind: string }[]>(
          await api(treasurer, 'GET', '/money-accounts'),
          200,
        );
        const account = list.find((a) => a.kind === kind);
        const journal = expectCreated<{ items: { documentNumber?: string; amount: string }[] }>(
          await api(treasurer, 'GET', `/money-accounts/${account?.id}/movements?from=${today}&to=${today}`),
          200,
        );
        const line = journal.items.find((item) => item.documentNumber === slip);
        expect(line?.amount, `${kind} journal has the handover ${slip}`).toBe(
          kind === 'CASH' ? `-${money(handoverCents)}` : money(handoverCents),
        );
      }
    } finally {
      await treasurer.context().close();
    }
  });

  test('a member has no cash book: no menu entry, no page, no API', async ({ browser }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    try {
      await expect(member.getByRole('link', { name: 'Cash and bank' })).toHaveCount(0);
      await member.goto('/cashbook');
      await expect(member.getByTestId('balance-CASH')).toHaveCount(0);
      expect((await api(member, 'GET', '/money-accounts')).status).toBe(403);
    } finally {
      await member.context().close();
    }
  });
});
