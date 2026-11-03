// @layer: e2e
// @spec: 017-delivery-email-telegram
// @regression
//
// Sending the month's invoices in a browser (requirement 017 section 15, FS 2.3 and 2.4): the treasurer opens the
// closed demo month, previews the send per channel, confirms, and the delivery screen shows sent, failed and to print.
// The demo month is already "sent" by the demo set-up, so the spec builds its own state: a fresh plot with three
// co-owners, each through a different channel: the electrician on Telegram (linked through the fake Telegram server,
// then the chat blocks the bot, so the row fails), a member on e-mail (Mailpit receives the letter) and a member on
// paper (to print). A second send creates nothing new. Telegram and the mail server are the local fakes of the stack.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  createPlot,
  demoMonths,
  expectCreated,
  kyivDay,
  monthLabel,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { block, linkTelegram, sentTo, uniqueChatId, unblock } from './support/fakeTelegram';
import { loginAs } from './support/login';
import { waitForLetterTo } from './support/mailpit';

const t = messages('en');
const ELECTRICIAN_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000004';

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

interface DeliveryItem {
  id: string;
  channel: 'TELEGRAM' | 'EMAIL' | 'PAPER';
  status: string;
  failureCode?: string;
  plotNumber?: string;
}

/** The deliveries of the month for the plot, read through the API (all pages of the month). */
async function deliveriesOf(page: Page, month: string, plot: string): Promise<DeliveryItem[]> {
  const found: DeliveryItem[] = [];
  for (let index = 0; ; index += 1) {
    const list = expectCreated<{ items: DeliveryItem[]; totalPages: number }>(
      await api(page, 'GET', `/deliveries?month=${month}&size=100&page=${index}`),
      200,
    );
    found.push(...list.items.filter((item) => item.plotNumber === plot));
    if (index + 1 >= list.totalPages) return found;
  }
}

test.describe('sending the month invoices', () => {
  test('the treasurer sends the closed month and sees sent, failed and to print; a second send adds nothing @smoke', async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    const electrician = await signedInPage(browser, 'electrician@demo.local');
    const stamp = Date.now().toString(36);
    const chat = uniqueChatId();
    const plot: CreatedPlot = { id: '', number: `E2E-D${stamp}` };
    const members: CreatedMember[] = [];
    const emailAddress = `e2e-${stamp}@delivery.test`;
    const original = expectCreated<{ channel: string }>(
      await api(electrician, 'GET', '/me/notifications'),
      200,
    ).channel;
    try {
      const { closed } = await demoMonths(chairman);

      // a plot of three co-owners: Telegram (the electrician), e-mail and paper
      Object.assign(plot, await createPlot(chairman, plot.number));
      const emailer = expectCreated<{ id: string }>(
        await api(chairman, 'POST', '/members', {
          lastName: `E2eMail${stamp}`,
          firstName: 'Test',
          email: emailAddress,
          deliveryChannel: 'EMAIL',
        }),
      );
      members.push({ id: emailer.id, name: `E2eMail${stamp} Test`, lastName: `E2eMail${stamp}` });
      members.push(await createMember(chairman, `E2ePaper${stamp}`));
      await changeOwners(chairman, plot.id, kyivDay(-1), [
        { memberId: ELECTRICIAN_MEMBER_ID, share: '1/3' },
        { memberId: emailer.id, share: '1/3' },
        { memberId: members[1]!.id, share: '1/3' },
      ]);
      await linkTelegram(electrician, chat);
      expectCreated(await api(electrician, 'PUT', '/me/notifications', { channel: 'TELEGRAM' }), 200);
      const issued = await api(treasurer, 'POST', `/plots/${plot.id}/invoices`, { month: closed });
      expect([200, 201], issued.body).toContain(issued.status);
      // the chat blocks the bot: the Telegram row must fail
      await block(chat);

      // the preview first: how many get it per channel; nothing is created yet
      await treasurer.goto(`/charges/months/${closed}`);
      const panel = treasurer.getByRole('main').locator('section', {
        has: treasurer.getByRole('heading', { name: t.invoices.monthPanel.title, exact: true }),
      });
      await panel
        .getByRole('button', { name: `Send invoices for ${monthLabel(closed)}`, exact: true })
        .click();
      const dialog = treasurer.getByRole('dialog');
      await dialog.getByLabel(t.invoices.send.includeNothingDue).check();
      const preview = dialog.getByRole('list', { name: t.invoices.send.previewTitle });
      await expect(preview).toContainText(/Telegram: [1-9]\d*/);
      await expect(preview).toContainText(/Email: [1-9]\d*/);
      await expect(preview).toContainText(/Paper \(to print\): [1-9]\d*/);
      expect(await deliveriesOf(chairman, closed, plot.number)).toHaveLength(0);

      // confirmed: the delivery screen of the month opens
      await dialog.getByRole('button', { name: t.invoices.send.confirm, exact: true }).click();
      await expect(treasurer).toHaveURL(new RegExp(`/deliveries\\?month=${closed}`));
      await expect(treasurer.getByRole('heading', { level: 1, name: t.deliveries.title })).toBeVisible();
      const counts = treasurer.getByRole('table', { name: t.deliveries.counts.title });
      await expect(counts).toBeVisible();

      // the three outcomes of the new plot, one per channel
      await expect
        .poll(
          async () =>
            (await deliveriesOf(chairman, closed, plot.number))
              .map((d) => `${d.channel}:${d.status}`)
              .sort()
              .join(','),
          { timeout: 45_000, intervals: [500, 1000, 2000] },
        )
        .toBe('EMAIL:SENT,PAPER:TO_PRINT,TELEGRAM:FAILED');
      const failed = (await deliveriesOf(chairman, closed, plot.number)).find((d) => d.status === 'FAILED');
      expect(failed?.failureCode).toBe('TELEGRAM_BLOCKED');

      // on the screen: the failed row with its reason, and the counts per channel
      await treasurer.goto(`/deliveries?month=${closed}&status=FAILED`);
      const failedRow = treasurer.getByRole('row').filter({ hasText: plot.number });
      await expect(failedRow).toHaveCount(1);
      await expect(failedRow).toContainText(t.deliveries.status.FAILED);
      await expect(failedRow).toContainText(t.deliveries.reason.TELEGRAM_BLOCKED);
      await treasurer.goto(`/deliveries?month=${closed}&status=TO_PRINT`);
      await expect(treasurer.getByRole('row').filter({ hasText: plot.number })).toContainText(
        t.deliveries.status.TO_PRINT,
      );
      await treasurer.goto(`/deliveries?month=${closed}`);
      const telegramCounts = counts.getByRole('row').filter({ hasText: /^Telegram/ });
      await expect(telegramCounts).toContainText(/[1-9]\d*/);

      // the letter reached the mail catcher with the invoice attached; the blocked chat got nothing
      const letter = await waitForLetterTo(emailAddress);
      expect(letter.attachments).toBeGreaterThanOrEqual(1);
      expect((await sentTo(chat)).filter((m) => m.method === 'sendDocument')).toHaveLength(0);

      // a second send creates nothing for anyone who got the invoice or has a row: the only new row is an e-mail
      // to the electrician, whose Telegram link ended when the bot was blocked (the fallback channel now, FS 2.3)
      await treasurer.goto(`/charges/months/${closed}`);
      await panel
        .getByRole('button', { name: `Send invoices for ${monthLabel(closed)}`, exact: true })
        .click();
      const again = treasurer.getByRole('dialog');
      await again.getByLabel(t.invoices.send.includeNothingDue).check();
      const repeated = again.getByRole('list', { name: t.invoices.send.previewTitle });
      await expect(repeated).toContainText('Telegram: 0');
      await expect(repeated).toContainText('Email: 1');
      await expect(repeated).toContainText('Paper (to print): 0');
      await again.getByRole('button', { name: t.memberForm.cancel, exact: true }).click();
      expect(await deliveriesOf(chairman, closed, plot.number)).toHaveLength(3);

      // «Printed» marks the month's paper copies as printed
      await treasurer.goto(`/deliveries?month=${closed}`);
      await treasurer.getByRole('button', { name: t.deliveries.printDone, exact: true }).click();
      await treasurer
        .getByRole('dialog')
        .getByRole('button', { name: t.deliveries.printDone, exact: true })
        .click();
      await expect(treasurer.getByText(/^Marked as printed: \d+\.$/)).toBeVisible();
      await expect
        .poll(
          async () =>
            (await deliveriesOf(chairman, closed, plot.number)).find((d) => d.channel === 'PAPER')?.status,
        )
        .toBe('PRINTED');
    } finally {
      await unblock(chat).catch(() => undefined);
      await api(electrician, 'DELETE', '/me/telegram-link').catch(() => undefined);
      await api(electrician, 'PUT', '/me/notifications', { channel: original }).catch(() => undefined);
      await archiveLeftovers(chairman, [plot], members, kyivDay(0));
      for (const page of [chairman, treasurer, electrician]) await page.context().close();
    }
  });
});
