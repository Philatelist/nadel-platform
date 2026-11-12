// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// Acceptance of feature 021 as a whole, the member's journey that the slice specs prove only in parts. The photo
// reading (meter-photo.spec.ts, @smoke) and the «Pay in the bank app» buttons (pay-from-phone.spec.ts, @smoke) are
// proven there verbatim; this spec adds what no slice spec joins up:
//  1. the member turns push on in Ukrainian (a synthetic device: a headless browser cannot subscribe), the chairman
//     issues an invoice, and the push the fake service receives decrypts to INVOICES_ISSUED with the month, and the
//     text the service worker shows for it (buildPushView, the same code the worker runs) is «Новий рахунок за <month>»
//     with no amount, plot number or name;
//  2. the push opens the path it carries on a 360 px phone, and there the member sees the invoice with «Pay in the bank
//     app» (en locale in e2e) and no side scroll.
// Each test makes its own plot owned by the demo member, so an invoice is always a new version (an unchanged one is
// not announced). Plots stay behind archived, as in the other specs.
import { randomUUID } from 'node:crypto';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { buildPushView, monthName } from '../src/pwa/pushPayload';
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
import { decryptJson, newSubscriptionKeys, received, reset, type FakeReceived } from './support/fakePush';
import { loginAs } from './support/login';
import { PHONE } from './support/phone';

const t = messages('en');
const PUSH_BASE = process.env.E2E_FAKE_PUSH_URL ?? 'http://localhost:8092';

async function signedIn(browser: Browser, email: string, phone: boolean): Promise<Page> {
  const context = await browser.newContext({
    baseURL: 'http://localhost:5173',
    locale: 'en-US',
    ...(phone
      ? { viewport: PHONE, isMobile: true, hasTouch: true }
      : { viewport: { width: 1280, height: 800 } }),
  });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

interface MyInvoice {
  payment?: { link: string };
}

test.describe('the member phone journey', () => {
  let chairman: Page;
  let member: Page;
  let memberId: string;
  const plots: CreatedPlot[] = [];
  const devices: string[] = [];

  test.beforeAll(async ({ browser }) => {
    chairman = await signedIn(browser, 'chairman@demo.local', false);
    member = await signedIn(browser, 'member@demo.local', true);
    memberId = expectCreated<{ memberId: string }>(await api(member, 'GET', '/me'), 200).memberId;
    await reset();
  });

  test.afterAll(async () => {
    for (const id of devices) await api(member, 'DELETE', `/me/push/devices/${id}`).catch(() => undefined);
    await archiveLeftovers(chairman, plots, [], kyivDay(0)).catch(() => undefined);
    await reset().catch(() => undefined);
    await chairman.context().close();
    await member.context().close();
  });

  test('push on in Ukrainian, the chairman issues an invoice, the device gets «Новий рахунок за …» and nothing personal', async () => {
    const subscription = randomUUID();
    const keys = newSubscriptionKeys();
    const registered = expectCreated<{ id: string }>(
      await api(member, 'POST', '/me/push/devices', {
        endpoint: `${PUSH_BASE}/push/${subscription}`,
        keys: { p256dh: keys.p256dh, auth: keys.auth },
        label: 'E2E acceptance phone',
        language: 'uk',
      }),
      201,
    );
    devices.push(registered.id);

    const plot = await createPlot(chairman, `A${Date.now().toString(36)}`);
    plots.push(plot);
    await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId, share: '1/1' }]);
    const { open } = await demoMonths(chairman);
    expectCreated(await api(chairman, 'POST', `/plots/${plot.id}/invoices`, { month: open }), 201);

    await expect
      .poll(async () => (await received(subscription)).length, {
        message: 'the fake push service should receive the message',
        timeout: 60_000,
        intervals: [500, 1000, 2000],
      })
      .toBe(1);
    const message = (await received(subscription))[0] as FakeReceived;
    const payload = decryptJson(message, keys);
    expect(payload['code']).toBe('INVOICES_ISSUED');
    expect((payload['params'] as { month?: string }).month).toBe(open);
    expect(payload['lang']).toBe('uk');

    // the text the service worker shows for this message
    const view = buildPushView(JSON.stringify(payload));
    expect(view.body).toBe(`Новий рахунок за ${monthName(open, 'uk')}`);
    expect(view.body.startsWith('Новий рахунок за ')).toBe(true);
    expect(`${view.title} ${view.body}`).not.toMatch(/\d/);
    expect(`${view.title} ${view.body}`).not.toContain(plot.number);
    expect(view.url).toMatch(/^\//);
  });

  test('the member sees an announcement in «My account», and its push link marks it read', async ({
    browser,
  }) => {
    const text = `E2E announcement ${randomUUID().slice(0, 8)}`;
    expectCreated(
      await api(chairman, 'POST', '/announcements', { text, streets: [], channels: ['EMAIL'] }),
      200,
    );
    const mine = expectCreated<{ id: string; text: string; unread: boolean }[]>(
      await api(member, 'GET', '/me/announcements'),
      200,
    );
    const sent = mine.find((a) => a.text === text);
    expect(sent?.unread).toBe(true);
    const id = (sent as { id: string }).id;

    const phone = await signedIn(browser, 'member@demo.local', true);
    try {
      // the block lists it with «New» (the demo announcements of the seed sit below it)
      await phone.goto('/account');
      const item = phone.locator(`#announcement-${id}`);
      await expect(item).toContainText(text);
      await expect(item).toContainText(messages('en').announcements.my.new);

      // the push opens /account#announcement-<id>: the item is read afterwards
      await phone.goto(`/account#announcement-${id}`);
      await expect(item).toBeVisible();
      await expect
        .poll(
          async () =>
            (
              expectCreated<{ id: string; unread: boolean }[]>(
                await api(member, 'GET', '/me/announcements'),
                200,
              ).find((a) => a.id === id) as { unread: boolean }
            ).unread,
        )
        .toBe(false);
      await expect(item).not.toContainText(messages('en').announcements.my.new);
    } finally {
      await phone.context().close();
    }
  });

  test('the push path opens on a phone and the member pays from the invoice there', async ({ browser }) => {
    test.setTimeout(120_000);
    const subscription = randomUUID();
    const keys = newSubscriptionKeys();
    const registered = expectCreated<{ id: string }>(
      await api(member, 'POST', '/me/push/devices', {
        endpoint: `${PUSH_BASE}/push/${subscription}`,
        keys: { p256dh: keys.p256dh, auth: keys.auth },
        language: 'en',
      }),
      201,
    );
    devices.push(registered.id);

    // something due: the treasurer issues invoices on request for the plots of the demo member, if none is payable yet
    const payable = async () =>
      expectCreated<MyInvoice[]>(await api(member, 'GET', '/me/invoices'), 200).some(
        (row) => row.payment !== undefined,
      );
    if (!(await payable())) {
      const treasurer = await signedIn(browser, 'treasurer@demo.local', false);
      try {
        const { open, closed } = await demoMonths(treasurer);
        for (const number of ['7', '15']) {
          const found = expectCreated<{ items: { id: string; number: string }[] }>(
            await api(treasurer, 'GET', `/plots?q=${encodeURIComponent(number)}`),
            200,
          );
          const plot = found.items.find((p) => p.number === number);
          if (plot)
            for (const month of [open, closed])
              await api(treasurer, 'POST', `/plots/${plot.id}/invoices`, { month });
        }
      } finally {
        await treasurer.context().close();
      }
    }
    expect(await payable()).toBe(true);
    // the on-request invoices above may have pushed too: the late ones are INVOICES_ISSUED as well, so the count is not asserted here
    await reset();

    const plot = await createPlot(chairman, `B${Date.now().toString(36)}`);
    plots.push(plot);
    await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId, share: '1/1' }]);
    const { open } = await demoMonths(chairman);
    expectCreated(await api(chairman, 'POST', `/plots/${plot.id}/invoices`, { month: open }), 201);
    await expect
      .poll(async () => (await received(subscription)).length, {
        timeout: 60_000,
        intervals: [500, 1000, 2000],
      })
      .toBeGreaterThanOrEqual(1);
    const payload = decryptJson((await received(subscription))[0] as FakeReceived, keys);
    expect(payload['code']).toBe('INVOICES_ISSUED');

    // what a tap on the notification does: opens its path in the app
    const path = buildPushView(JSON.stringify(payload)).url;
    // a fresh load: the page may already be on /account, where only the hash would change and the list stay stale
    await member.goto(path);
    await member.reload();
    const section = member.getByRole('main').locator('section', {
      has: member.getByRole('heading', { name: t.invoices.myTitle, exact: true }),
    });
    await expect(section).toBeVisible();
    await expect(
      section.getByRole('link', { name: t.invoices.pay.payInBank, exact: true }).first(),
    ).toBeVisible();
    const overflow = await member.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
