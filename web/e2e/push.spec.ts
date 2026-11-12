// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// Push on a device and the first push «Новий рахунок за …» (feature 021 slice 7). The API with the member's token: the
// config says push works, a synthetic device (an address on the fake push service of the stack, keys made here) is
// registered and listed without its address; when the chairman issues an invoice for a plot of the member the fake
// service receives exactly one encrypted message that decrypts to the code INVOICES_ISSUED with the month and a path,
// and nothing personal; a device the service reports gone is dropped by the backend; a device can be deleted by its
// owner only. The browser: without a service worker (the dev server has none) the switch says so and nothing breaks.
// Each test makes its own plot owned by the demo member, so an invoice is always a new version (an unchanged one is
// not announced). Plots and members stay behind archived, as in the other specs.
import { randomUUID } from 'node:crypto';
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
import {
  decryptJson,
  gone,
  newSubscriptionKeys,
  received,
  reset,
  type FakeReceived,
  type SubscriptionKeys,
} from './support/fakePush';
import { loginAs } from './support/login';

const t = messages('en');
const PUSH_BASE = process.env.E2E_FAKE_PUSH_URL ?? 'http://localhost:8092';

interface PushDevice {
  id: string;
  label?: string;
  createdAt: string;
  lastSentAt?: string;
}

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

const devicesOf = async (page: Page) =>
  expectCreated<PushDevice[]>(await api(page, 'GET', '/me/push/devices'), 200);

async function register(
  member: Page,
  label: string,
): Promise<{ device: PushDevice; subscription: string; keys: SubscriptionKeys; raw: string }> {
  const subscription = randomUUID();
  const keys = newSubscriptionKeys();
  const raw = await api(member, 'POST', '/me/push/devices', {
    endpoint: `${PUSH_BASE}/push/${subscription}`,
    keys: { p256dh: keys.p256dh, auth: keys.auth },
    label,
    language: 'en',
  });
  return { device: expectCreated<PushDevice>(raw, 201), subscription, keys, raw: raw.body };
}

async function waitForPushes(subscription: string, count: number): Promise<FakeReceived[]> {
  await expect
    .poll(async () => (await received(subscription)).length, {
      message: 'the fake push service should receive the message',
      timeout: 60_000,
      intervals: [500, 1000, 2000],
    })
    .toBe(count);
  return received(subscription);
}

test.describe('push on a device and the first push', () => {
  let chairman: Page;
  let member: Page;
  let memberId: string;
  const plots: CreatedPlot[] = [];
  const devices: string[] = [];

  test.beforeAll(async ({ browser }) => {
    chairman = await signedInPage(browser, 'chairman@demo.local');
    member = await signedInPage(browser, 'member@demo.local');
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

  /** A new plot of the demo member and the chairman's invoice for it in the open month: a new version. */
  async function issueInvoice(): Promise<string> {
    const plot = await createPlot(chairman, `P${Date.now().toString(36)}${plots.length}`);
    plots.push(plot);
    await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId, share: '1/1' }]);
    const { open } = await demoMonths(chairman);
    expectCreated(await api(chairman, 'POST', `/plots/${plot.id}/invoices`, { month: open }), 201);
    return open;
  }

  test('the config enables push; a device is registered and listed without its address', async () => {
    const config = expectCreated<{ enabled: boolean; publicKey?: string }>(
      await api(member, 'GET', '/me/push'),
      200,
    );
    expect(config.enabled).toBe(true);
    expect(config.publicKey).toMatch(/^[A-Za-z0-9_-]{87}$/);

    const { device, subscription, raw } = await register(member, 'E2E phone');
    devices.push(device.id);
    expect(raw).not.toContain(subscription);
    expect(raw).not.toContain('endpoint');

    const listed = await api(member, 'GET', '/me/push/devices');
    expect(listed.status).toBe(200);
    expect(listed.body).not.toContain('endpoint');
    expect(listed.body).not.toContain(subscription);
    expect((JSON.parse(listed.body) as PushDevice[]).map((d) => d.id)).toContain(device.id);
    expect((await devicesOf(member)).find((d) => d.id === device.id)?.label).toBe('E2E phone');
  });

  test('registering refuses an address that is not https on a known service and bad keys', async () => {
    const keys = newSubscriptionKeys();
    const bad = await api(member, 'POST', '/me/push/devices', {
      endpoint: 'https://evil.example/push/abc',
      keys: { p256dh: keys.p256dh, auth: keys.auth },
      language: 'en',
    });
    expect(bad.status).toBe(400);
    const badKeys = await api(member, 'POST', '/me/push/devices', {
      endpoint: `${PUSH_BASE}/push/${randomUUID()}`,
      keys: { p256dh: 'AAAA', auth: keys.auth },
      language: 'en',
    });
    expect(badKeys.status).toBe(400);
  });

  test('issuing an invoice sends one encrypted push with the month and nothing personal', async () => {
    const { subscription, keys, device } = await register(member, 'E2E first push');
    devices.push(device.id);
    const month = await issueInvoice();

    const messagesReceived = await waitForPushes(subscription, 1);
    // give a second delivery (a bug) the time to show up
    await new Promise((resolve) => setTimeout(resolve, 3000));
    expect(await received(subscription)).toHaveLength(1);

    const [message] = messagesReceived;
    expect(message?.headers['content-encoding']).toBe('aes128gcm');
    expect(message?.headers['authorization']).toMatch(/^vapid t=[^,]+, k=[A-Za-z0-9_-]+$/);

    const payload = decryptJson(message as FakeReceived, keys);
    expect(payload['code']).toBe('INVOICES_ISSUED');
    expect((payload['params'] as { month?: string }).month).toBe(month);
    expect(String(payload['url'])).toMatch(/^\//);
    expect(payload['lang']).toBe('en');

    // no amount, plot number or name: the only digits in the JSON are the month and the version
    const json = JSON.stringify(payload);
    expect(json.replace(month, '').replace(/"v":\d+/, '')).not.toMatch(/\d/);
    const me = expectCreated<{ fullName: string; email: string }>(await api(member, 'GET', '/me'), 200);
    for (const name of me.fullName.split(/\s+/)) expect(json).not.toContain(name);
    expect(json).not.toContain(me.email);
    expect(json).not.toContain(plots.at(-1)?.number ?? '\u0000');

    await expect
      .poll(async () => (await devicesOf(member)).find((d) => d.id === device.id)?.lastSentAt, {
        message: 'the device should show when the last push was sent',
      })
      .toBeTruthy();
  });

  test('a device the push service reports gone is dropped', async () => {
    const { subscription, device } = await register(member, 'E2E gone');
    devices.push(device.id);
    await gone(subscription);
    await issueInvoice();
    await expect
      .poll(async () => (await devicesOf(member)).some((d) => d.id === device.id), {
        message: 'the backend should delete the device after 410 Gone',
        timeout: 60_000,
        intervals: [500, 1000, 2000],
      })
      .toBe(false);
    expect(await received(subscription)).toHaveLength(0);
  });

  test('a member deletes a device of their own; another member gets 404 for it', async ({ browser }) => {
    const { device } = await register(member, 'E2E delete');
    const other = await signedInPage(browser, 'electrician@demo.local');
    try {
      expect((await api(other, 'DELETE', `/me/push/devices/${device.id}`)).status).toBe(404);
      expect((await devicesOf(other)).some((d) => d.id === device.id)).toBe(false);
      expect((await devicesOf(member)).some((d) => d.id === device.id)).toBe(true);
    } finally {
      await other.context().close();
    }
    expect((await api(member, 'DELETE', `/me/push/devices/${device.id}`)).status).toBe(204);
    expect((await devicesOf(member)).some((d) => d.id === device.id)).toBe(false);
    expect((await api(member, 'DELETE', `/me/push/devices/${device.id}`)).status).toBe(404);
  });

  test('a call without a token is refused', async ({ request }) => {
    expect((await request.get('/api/v1/me/push')).status()).toBe(401);
  });

  test('the Notifications block renders without a service worker and does not crash', async ({ browser }) => {
    const page = await signedInPage(browser, 'member@demo.local');
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text());
    });
    try {
      await page.goto('/account/profile');
      const block = page.getByRole('main').locator('section', {
        has: page.getByRole('heading', { name: t.notifications.title, exact: true }),
      });
      await expect(block).toBeVisible();
      await expect(block.getByRole('heading', { name: t.notifications.push.title })).toBeVisible();
      await expect(block.getByText(t.notifications.push.unsupported)).toBeVisible();
      expect(errors).toEqual([]);
    } finally {
      await page.context().close();
    }
  });
});
