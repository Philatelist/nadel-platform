// @layer: e2e
// @spec: 017-delivery-email-telegram
// @regression
//
// An announcement to one street (requirement 017 section 15, FS 2.6): the chairman writes the text, chooses the
// audience «Streets» with a street of the spec's own and the Telegram channel only, previews the counts (one member
// reaches Telegram, one is on paper), sends, and the announcement page and the list show the outcome. The fake Telegram
// server receives the text for the linked owner on that street and nothing is sent to the demo member who lives
// elsewhere. A treasurer has no «Announcements» section and the API refuses her.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  expectCreated,
  kyivDay,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { linkTelegram, sentTo, uniqueChatId, waitForSent } from './support/fakeTelegram';
import { loginAs } from './support/login';

const t = messages('en');
const ELECTRICIAN_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000004';
/** The demo member linked by the demo set-up (member@demo.local). */
const DEMO_MEMBER_CHAT = 700000001;

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

test.describe('announcements', () => {
  test('the chairman previews and sends an announcement to one street and the linked owner gets it in Telegram', async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    const electrician = await signedInPage(browser, 'electrician@demo.local');
    const stamp = Date.now().toString(36);
    const street = `E2E Street ${stamp}`;
    const text = `Збори на вулиці ${stamp}: неділя о 10:00`;
    const plots: CreatedPlot[] = [];
    const members: CreatedMember[] = [];
    const chat = uniqueChatId();
    const original = expectCreated<{ channel: string }>(
      await api(electrician, 'GET', '/me/notifications'),
      200,
    ).channel;
    const demoBefore = (await sentTo(DEMO_MEMBER_CHAT)).length;
    try {
      // two plots on the street: the electrician (linked, prefers Telegram) and a member on paper
      const paper = await createMember(chairman, `E2eAnn${stamp}`);
      members.push(paper);
      for (const [index, owner] of [ELECTRICIAN_MEMBER_ID, paper.id].entries()) {
        const number = `E2E-A${index}${stamp}`;
        const created = expectCreated<{ id: string }>(
          await api(chairman, 'POST', '/plots', { number, kind: 'PLOT', areaM2: 400, street }),
        );
        plots.push({ id: created.id, number });
        await changeOwners(chairman, created.id, kyivDay(-1), [{ memberId: owner, share: '1/1' }]);
      }
      await linkTelegram(electrician, chat);
      expectCreated(await api(electrician, 'PUT', '/me/notifications', { channel: 'TELEGRAM' }), 200);

      // the form: text, the street, Telegram only; the preview counts and lists the member on paper
      await chairman.goto('/announcements');
      await chairman.getByRole('link', { name: t.announcements.newAnnouncement, exact: true }).click();
      await chairman.getByLabel(t.announcements.form.text, { exact: true }).fill(text);
      await chairman.getByRole('radio', { name: t.announcements.form.audienceStreets, exact: true }).check();
      await chairman.getByRole('checkbox', { name: street, exact: true }).check();
      await chairman
        .getByRole('checkbox', { name: t.announcements.form.channelEmail, exact: true })
        .uncheck();
      await chairman.getByRole('button', { name: t.announcements.form.preview, exact: true }).click();
      await expect(chairman.getByRole('heading', { name: t.announcements.form.previewTitle })).toBeVisible();
      await expect(chairman.getByText('Telegram: 1, e-mail: 0, on paper: 1')).toBeVisible();
      await expect(chairman.getByRole('list').getByText(paper.name, { exact: true })).toBeVisible();

      // sent: the announcement page opens with the text; the linked owner has it in Telegram
      await chairman.getByRole('button', { name: t.announcements.form.send, exact: true }).click();
      await expect(chairman).toHaveURL(/\/announcements\/[0-9a-f-]{36}$/);
      await expect(chairman.getByText(text, { exact: true })).toBeVisible();
      const received = await waitForSent(chat, (m) => m.text === text, 'the announcement text');
      expect(received.method).toBe('sendMessage');
      // the page does not poll: look again until the dispatcher has sent it
      await expect(async () => {
        await chairman.reload();
        await expect(chairman.getByText('Sent: 1, failed: 0, queued: 0, on paper: 1')).toBeVisible({
          timeout: 3000,
        });
      }).toPass({ timeout: 45_000 });

      // the list shows it with its counts; the demo member of another street got nothing
      await chairman.goto('/announcements');
      const row = chairman.getByRole('row').filter({ hasText: street });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('Sent: 1, failed: 0, queued: 0, on paper: 1');
      expect((await sentTo(DEMO_MEMBER_CHAT)).length).toBe(demoBefore);

      // the treasurer has no right to send announcements
      const refused = await api(treasurer, 'POST', '/announcements?preview=true', {
        text,
        streets: [street],
        channels: ['TELEGRAM'],
      });
      expect(refused.status).toBe(403);
      await treasurer.goto('/');
      await expect(treasurer.getByRole('link', { name: t.announcements.title, exact: true })).toHaveCount(0);
    } finally {
      await api(electrician, 'DELETE', '/me/telegram-link').catch(() => undefined);
      await api(electrician, 'PUT', '/me/notifications', { channel: original }).catch(() => undefined);
      await archiveLeftovers(chairman, plots, members, kyivDay(0));
      for (const page of [chairman, treasurer, electrician]) await page.context().close();
    }
  });
});
