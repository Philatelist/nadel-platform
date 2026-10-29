// @layer: e2e
// @spec: 017-delivery-email-telegram
// @regression
//
// The local demo (FS 2.8): in the dev stack the demo cooperative's mailbox points at the mail catcher and its bot at
// the fake Telegram server, and last month's invoices are already sent. The chairman sees both channels «configured»
// on Settings and the test letter reaches the mail catcher; the treasurer sees sent invoices for the closed month on
// the delivery screen.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { demoMonths } from './support/api';
import { loginAs } from './support/login';
import { lettersTo } from './support/mailpit';

const t = messages('en');

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

test.describe('the demo delivery set-up', () => {
  test('both channels are set up, the test letter reaches the mail catcher and last month is sent', async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      await chairman.goto('/settings');
      const panel = chairman.getByRole('main');
      await expect(
        panel.getByRole('heading', { name: new RegExp(`^${t.deliverySettings.email.title}`) }),
      ).toBeVisible();
      await expect(panel.getByText(t.deliverySettings.configured, { exact: true })).toHaveCount(2);
      await expect(panel.getByText(t.deliverySettings.notConfigured, { exact: true })).toHaveCount(0);

      // the test letter goes to the chairman's own e-mail and shows in the mail catcher
      const before = (await lettersTo('chairman@demo.local')).length;
      await panel.getByRole('button', { name: t.deliverySettings.email.sendTest, exact: true }).click();
      await expect(panel.getByText(t.deliverySettings.email.testAccepted)).toBeVisible();
      await expect.poll(async () => (await lettersTo('chairman@demo.local')).length).toBeGreaterThan(before);

      // last month's invoices are already sent
      const { closed } = await demoMonths(treasurer);
      await treasurer.goto(`/deliveries?month=${closed}`);
      const counts = treasurer.getByRole('table', { name: t.deliveries.counts.title });
      await expect(counts).toBeVisible();
      await expect(counts.getByRole('row').filter({ hasText: /^Email/ })).toContainText(/[1-9]\d*/);
      await expect(
        treasurer.getByRole('row').filter({ hasText: t.deliveries.status.SENT }).first(),
      ).toBeVisible();
    } finally {
      for (const page of [chairman, treasurer]) await page.context().close();
    }
  });
});
