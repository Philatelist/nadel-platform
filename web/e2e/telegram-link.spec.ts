// @layer: e2e
// @spec: 017-delivery-email-telegram
// @regression
//
// Linking Telegram and getting the invoice there (requirement 017 section 15, FS 2.2 and 2.4): the electrician (a
// member of the demo cooperative) owns a fresh plot, opens the account, «Connect Telegram» gives a personal deep link
// (the spec intercepts window.open, so nothing leaves the machine), the member "opens the bot" by writing
// `/start <code>` to the fake Telegram server, the bot greets them with the plot number and the account shows
// «Telegram connected». The same link opened again is refused. The treasurer then sends the plot's invoice and the
// fake server receives the PDF with the caption. At the end the member disconnects Telegram in the account.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createPlot,
  demoMonths,
  expectCreated,
  kyivDay,
  type CreatedPlot,
} from './support/api';
import { codeOf, sendUpdate, sentTo, uniqueChatId, waitForSent } from './support/fakeTelegram';
import { loginAs } from './support/login';

const t = messages('en');
const ELECTRICIAN_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000004';

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

test.describe('linking Telegram', () => {
  test('the member connects Telegram from the account, the bot greets them and the invoice arrives there @smoke', async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    // the member's browser never opens t.me: window.open only records the address
    const memberContext = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
    const member = await memberContext.newPage();
    await member.addInitScript(() => {
      const opened: string[] = [];
      (window as unknown as { __opened: string[] }).__opened = opened;
      window.open = (url) => {
        opened.push(String(url));
        return null;
      };
    });
    await loginAs(member, 'electrician@demo.local');
    const plot: CreatedPlot = { id: '', number: `E2E-T${Date.now().toString(36)}` };
    const chat = uniqueChatId();
    const original = expectCreated<{ channel: string }>(
      await api(member, 'GET', '/me/notifications'),
      200,
    ).channel;
    try {
      Object.assign(plot, await createPlot(chairman, plot.number));
      await changeOwners(chairman, plot.id, kyivDay(-1), [{ memberId: ELECTRICIAN_MEMBER_ID, share: '1/1' }]);
      const { closed } = await demoMonths(chairman);

      // «Connect Telegram» gives a personal link of the cooperative's bot
      await member.goto('/account');
      const panel = member.getByRole('region', { name: t.notifications.title });
      await expect(panel).toBeVisible();
      await expect(panel.getByText(t.telegramLink.connected, { exact: true })).toHaveCount(0);
      await panel.getByRole('button', { name: t.telegramLink.connect, exact: true }).click();
      await expect
        .poll(() => member.evaluate(() => (window as unknown as { __opened: string[] }).__opened.length))
        .toBe(1);
      const deepLink = await member.evaluate(
        () => (window as unknown as { __opened: string[] }).__opened[0]!,
      );
      expect(deepLink).toMatch(/^https:\/\/t\.me\/nadel_demo_bot\?start=[A-Za-z0-9_-]{22}$/);
      const code = codeOf(deepLink);

      // the member opens the bot with it: the greeting names the plot, and the account shows the link
      await sendUpdate(chat, `/start ${code}`);
      const greeting = await waitForSent(chat, (m) => m.text?.startsWith('Вітаємо') ?? false, 'a greeting');
      expect(greeting.text).toContain(plot.number); // other specs may have left archived plots with this owner
      await member.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(panel.getByText(t.telegramLink.connected, { exact: true })).toBeVisible();

      // the same link a second time: refused, and nothing else is linked
      const stranger = uniqueChatId();
      await sendUpdate(stranger, `/start ${code}`);
      const refusal = await waitForSent(
        stranger,
        (m) => m.text?.startsWith('Посилання недійсне') ?? false,
        'a refusal',
      );
      expect(refusal.text).toBe('Посилання недійсне, отримайте нове в кабінеті або в правлінні');

      // the member chooses Telegram for invoices
      await panel.getByRole('radio', { name: t.channel.TELEGRAM, exact: true }).check();
      await panel.getByRole('button', { name: t.notifications.save, exact: true }).click();
      await expect
        .poll(
          async () =>
            expectCreated<{ channel: string }>(await api(member, 'GET', '/me/notifications'), 200).channel,
        )
        .toBe('TELEGRAM');

      // the treasurer sends the plot's invoice: the PDF arrives in the member's chat
      const issued = expectCreated<{ id: string }>(
        await api(treasurer, 'POST', `/plots/${plot.id}/invoices`, { month: closed }),
        201,
      );
      expectCreated(await api(treasurer, 'POST', `/invoices/${issued.id}/send`), 200);
      const document = await waitForSent(chat, (m) => m.method === 'sendDocument', 'the invoice');
      expect(document.documentName).toMatch(/\.pdf$/);
      expect(document.documentSize).toBeGreaterThan(1000);
      expect(document.text).toContain(`ділянка ${plot.number}`);
      expect((await sentTo(stranger)).filter((m) => m.method === 'sendDocument')).toHaveLength(0);

      // «Disconnect Telegram» in the account ends the link
      await panel.getByRole('button', { name: t.telegramLink.disconnect, exact: true }).click();
      await member
        .getByRole('dialog')
        .getByRole('button', { name: t.telegramLink.disconnect, exact: true })
        .click();
      await expect(panel.getByRole('button', { name: t.telegramLink.connect, exact: true })).toBeVisible();
    } finally {
      await api(member, 'DELETE', '/me/telegram-link').catch(() => undefined);
      await api(member, 'PUT', '/me/notifications', { channel: original }).catch(() => undefined);
      await archiveLeftovers(chairman, [plot], [], kyivDay(0));
      for (const page of [chairman, treasurer, member]) await page.context().close();
    }
  });
});
