// @layer: e2e
// @spec: 017-delivery-email-telegram
// @regression
//
// The payment message (requirement 017 section 15, FS 2.4): the electrician owns a fresh plot with an opening debt of
// 1000.00, is linked to the fake Telegram bot and prefers Telegram. The treasurer saves a cash payment of 400.00 from
// the plot card; the fake Telegram server receives «Отримано 400,00 ₴ по ділянці … Борг: 600,00 ₴» with the cash receipt
// PDF. When the chairman turns «Notify about payments» off, the next payment sends nothing.
import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createPlot,
  expectCreated,
  kyivDay,
  type CreatedPlot,
} from './support/api';
import { linkTelegram, sentTo, uniqueChatId, waitForSent } from './support/fakeTelegram';
import { loginAs } from './support/login';

const ELECTRICIAN_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000004';
const NBSP = ' ';

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

/** Saves a cash payment from the plot card in the browser. */
async function payFromPlotCard(treasurer: Page, plotId: string, amount: string): Promise<void> {
  await treasurer.goto(`/plots/${plotId}`);
  await treasurer.getByRole('link', { name: 'Enter payment', exact: true }).click();
  await expect(treasurer.getByRole('heading', { level: 1, name: 'Enter payment' })).toBeVisible();
  await treasurer.getByLabel('Amount', { exact: true }).fill(amount);
  await treasurer.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(treasurer.getByTestId('receipt-number')).toHaveText(/^Receipt № \d+$/);
}

test.describe('payment messages', () => {
  test('a cash payment reaches the linked owner in Telegram with the receipt; with the switch off it does not', async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    const electrician = await signedInPage(browser, 'electrician@demo.local');
    const plot: CreatedPlot = { id: '', number: `E2E-M${Date.now().toString(36)}` };
    const chat = uniqueChatId();
    const original = expectCreated<{ channel: string }>(
      await api(electrician, 'GET', '/me/notifications'),
      200,
    ).channel;
    try {
      Object.assign(plot, await createPlot(chairman, plot.number));
      await changeOwners(chairman, plot.id, kyivDay(-10), [
        { memberId: ELECTRICIAN_MEMBER_ID, share: '1/1' },
      ]);
      const types = expectCreated<{ id: string; code?: string }[]>(
        await api(chairman, 'GET', '/charge-types'),
        200,
      );
      const membership = types.find((type) => type.code === 'MEMBERSHIP');
      expect(membership, 'the membership charge type').toBeDefined();
      expectCreated(
        await api(chairman, 'POST', `/plots/${plot.id}/opening-balances`, {
          chargeTypeId: membership!.id,
          amount: '1000.00',
          date: kyivDay(-3),
          reason: 'E2E opening balance',
        }),
      );
      await linkTelegram(electrician, chat);
      expectCreated(await api(electrician, 'PUT', '/me/notifications', { channel: 'TELEGRAM' }), 200);

      // the treasurer saves a cash payment: the message with the receipt PDF arrives
      await payFromPlotCard(treasurer, plot.id, '400.00');
      const message = await waitForSent(chat, (m) => m.method === 'sendDocument', 'the payment message');
      expect(message.text).toMatch(
        new RegExp(
          `^Отримано 400,00${NBSP}₴ по ділянці ${plot.number} \\(\\d{2}\\.\\d{2}\\.\\d{4}\\)\\. Борг: 600,00${NBSP}₴$`,
        ),
      );
      expect(message.documentName).toMatch(/^receipt_.*\.pdf$/);
      expect(message.documentSize).toBeGreaterThan(1000);

      // «Notify about payments» off: the next payment sends nothing
      const settings = expectCreated<{ notifyPayments: boolean }>(
        await api(chairman, 'GET', '/settings/delivery'),
        200,
      );
      expect(settings.notifyPayments).toBe(true);
      expectCreated(await api(chairman, 'PUT', '/settings/delivery', { notifyPayments: false }), 200);
      const before = (await sentTo(chat)).length;
      await payFromPlotCard(treasurer, plot.id, '100.00');
      await new Promise((resolve) => setTimeout(resolve, 5000)); // a message, if one were queued, goes out within this
      expect((await sentTo(chat)).length).toBe(before);
    } finally {
      await api(chairman, 'PUT', '/settings/delivery', { notifyPayments: true }).catch(() => undefined);
      const listed = await api(chairman, 'GET', `/payments?plotId=${plot.id}&size=100`).catch(
        () => undefined,
      );
      if (listed?.status === 200)
        for (const payment of (JSON.parse(listed.body) as { items: { id: string; kind?: string }[] }).items)
          await api(chairman, 'POST', `/payments/${payment.id}/reverse`, { reason: 'E2E clean-up' }).catch(
            () => undefined,
          );
      await api(electrician, 'DELETE', '/me/telegram-link').catch(() => undefined);
      await api(electrician, 'PUT', '/me/notifications', { channel: original }).catch(() => undefined);
      await archiveLeftovers(chairman, [plot], [], kyivDay(0));
      for (const page of [chairman, treasurer, electrician]) await page.context().close();
    }
  });
});
