// @layer: e2e
// @spec: 011-ledger-charges-period-closing
// @regression
// Closing a billing month. It lives in its own file whose name sorts last because Playwright runs files in
// alphabetical order: once the demo's only open month (October) is closed, nothing can be charged any more, and
// the other specs that run the month or book ranges would find no open month. The test skips itself when no month
// is open (a re-run on the same database).
import { expect, test, type Page } from '@playwright/test';
import { api, expectCreated } from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');

test.describe('closing a month', () => {
  test('the chairman closes October after viewing the check list; nothing can be added to it afterwards @smoke', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const months = expectCreated<{ month: string; status: string }[]>(
      await api(page, 'GET', '/billing-months'),
      200,
    );
    const open = months.filter((m) => m.status === 'OPEN').map((m) => m.month);
    test.skip(open.length === 0, 'no open month: this database was already used by this spec');
    const earliest = [...open].sort()[0] ?? '';

    // a month cannot be closed while an earlier month is open (the only open month is the earliest one)
    expect(months.filter((m) => m.status === 'CLOSED').every((m) => m.month < earliest)).toBe(true);

    await page.goto(`/charges/months/${earliest}`);
    await expect(main(page).getByText('Before closing')).toBeVisible();
    await expect(main(page).getByText('Items waiting in “Not charged”: ')).toBeVisible();
    await expect(main(page).getByText('Meters without a recent reading')).toBeVisible();
    await main(page).getByRole('button', { name: 'Close the month', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('A closed month cannot be reopened')).toBeVisible();
    await expect(dialog.getByText('The month run is done')).toBeVisible();
    await dialog.getByRole('button', { name: 'Close the month', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(main(page).getByText(/^Closed /)).toBeVisible();
    await expect(main(page).getByRole('button', { name: 'Close the month', exact: true })).toHaveCount(0);
    await expect(main(page).getByRole('button', { name: 'Charge the month', exact: true })).toHaveCount(0);
    const run = await api(page, 'POST', `/billing-months/${earliest}/run?preview=false`, {
      digest: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    });
    expect(run.status).toBeGreaterThanOrEqual(400);
  });
});
