// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// The other pushes and removal on leaving (feature 021 slice 8). Synthetic devices (addresses on the fake push service
// of the stack, keys made here) of the demo member, the demo chairman and a freshly invited member receive, decrypted:
//  - READINGS_TO_APPROVE for the chairman when the member submits a reading (Topic readings-approvals), once in an
//    hour: a second submission sends nothing more; the submitter is not told of their own submission;
//  - READING_APPROVED with the url /account#my-meters for the member when the chairman approves it;
//  - PAYMENT_RECORDED for the payer while the cooperative's payment notices are on, nothing while they are off;
//  - ANNOUNCEMENT for a recipient of an announcement;
//  - nothing for a member whose access was turned off or who was archived: the devices are gone with the access.
// No payload has an amount, a plot number, a name, an address or the text of an announcement. The throttle is kept in
// the database for an hour: run this file on a fresh database (make e2e, the scripted run) or at least an hour after
// another spec that submitted a reading of a member. The fixtures stay behind archived, as in the other specs.
import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  changeOwners,
  cleanupMeterPlot,
  createMember,
  createPlot,
  expectCreated,
  kyivDay,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import {
  decryptJson,
  newSubscriptionKeys,
  received,
  reset,
  type FakeReceived,
  type SubscriptionKeys,
} from './support/fakePush';
import { loginAs, signInAtKeycloak } from './support/login';
import { letterLink, waitForLetterTo } from './support/mailpit';

const t = messages('en');
const PUSH_BASE = process.env.E2E_FAKE_PUSH_URL ?? 'http://localhost:8092';
const QUIET_MS = 5000; // a push that should not come would be sent within this

interface Device {
  id: string;
  subscription: string;
  keys: SubscriptionKeys;
  owner: Page;
}

interface Fixture {
  plot: CreatedPlot;
  meterId: string;
  serial: string;
}

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

async function register(owner: Page, label: string): Promise<Device> {
  const subscription = randomUUID();
  const keys = newSubscriptionKeys();
  const created = expectCreated<{ id: string }>(
    await api(owner, 'POST', '/me/push/devices', {
      endpoint: `${PUSH_BASE}/push/${subscription}`,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
      label,
      language: 'en',
    }),
    201,
  );
  return { id: created.id, subscription, keys, owner };
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Waits for `count` messages of the device and then for a quiet moment, so that an extra one would show. */
async function pushesOf(device: Device, count: number): Promise<FakeReceived[]> {
  await expect
    .poll(async () => (await received(device.subscription)).length, {
      message: 'the fake push service should receive the message',
      timeout: 60_000,
      intervals: [500, 1000, 2000],
    })
    .toBe(count);
  await sleep(3000);
  const all = await received(device.subscription);
  expect(all).toHaveLength(count);
  return all;
}

async function nothingFor(device: Device): Promise<void> {
  await sleep(QUIET_MS);
  expect(await received(device.subscription)).toHaveLength(0);
}

/** A plot owned by `ownerId` with an active electricity meter installed 30 days ago at 100. */
async function createFixture(chairman: Page, ownerId: string, street?: string): Promise<Fixture> {
  const stamp = `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;
  const number = `E2E-PE-${stamp}`;
  const serial = `E2E-PESN-${stamp}`;
  const created = expectCreated<{ id: string }>(
    await api(chairman, 'POST', '/plots', {
      number,
      kind: 'PLOT',
      areaM2: 400,
      street: street ?? `E2E Street PE${stamp}`,
    }),
  );
  const plot = { id: created.id, number };
  try {
    await changeOwners(chairman, plot.id, kyivDay(-40), [{ memberId: ownerId, share: '1/1' }]);
    const meter = expectCreated<{ id: string }>(
      await api(chairman, 'POST', '/meters', {
        kind: 'PLOT',
        resource: 'ELECTRICITY',
        plotId: plot.id,
        serial,
        installedOn: kyivDay(-30),
        initialReading: '100.000',
      }),
    );
    return { plot, meterId: meter.id, serial };
  } catch (error) {
    await archiveLeftovers(chairman, [plot], [], kyivDay(0));
    throw error;
  }
}

async function submit(owner: Page, fixture: Fixture, value: string): Promise<string> {
  const saved = expectCreated<{ id: string }>(
    await api(owner, 'POST', `/plots/${fixture.plot.id}/readings`, { meterId: fixture.meterId, value }),
  );
  return saved.id;
}

/** A cash payment of a plot naming `payerMemberId`, saved with the chairman's token. */
async function pay(chairman: Page, plotId: string, payerMemberId: string, amount: string): Promise<void> {
  expectCreated(
    await api(chairman, 'POST', '/payments?preview=false', {
      plotId,
      amount,
      receivedOn: kyivDay(0),
      source: 'CASH',
      payerMemberId,
    }),
  );
}

async function reversePayments(chairman: Page, plotId: string): Promise<void> {
  const listed = await api(chairman, 'GET', `/payments?plotId=${plotId}&size=100`).catch(() => undefined);
  if (listed?.status !== 200) return;
  for (const payment of (JSON.parse(listed.body) as { items: { id: string; reversed: boolean }[] }).items)
    if (!payment.reversed)
      await api(chairman, 'POST', `/payments/${payment.id}/reverse`, { reason: 'E2E clean-up' }).catch(
        () => undefined,
      );
}

/** The payload has no amount, plot number, name, address or free text: the only digits are a month and the version. */
function expectNothingPersonal(payload: Record<string, unknown>, forbidden: string[]): void {
  const json = JSON.stringify(payload);
  expect(json.replace(/"v":\d+/, '').replace(/\d{4}-\d{2}/, '')).not.toMatch(/\d/);
  for (const word of forbidden) if (word.length > 0) expect(json).not.toContain(word);
}

test.describe.configure({ mode: 'serial', timeout: 180_000 });

test.describe('the other pushes and removal on leaving', () => {
  let chairman: Page;
  let member: Page;
  let memberId: string;
  let chairmanId: string;
  let memberNames: string[];
  const devices: Device[] = [];
  const fixtures: Fixture[] = [];
  const plots: CreatedPlot[] = [];
  const members: CreatedMember[] = [];
  const extraPages: Page[] = [];

  test.beforeAll(async ({ browser }) => {
    chairman = await signedInPage(browser, 'chairman@demo.local');
    member = await signedInPage(browser, 'member@demo.local');
    const me = expectCreated<{ memberId: string; fullName: string; email: string }>(
      await api(member, 'GET', '/me'),
      200,
    );
    memberId = me.memberId;
    memberNames = [...me.fullName.split(/\s+/), me.email];
    chairmanId = expectCreated<{ memberId: string }>(await api(chairman, 'GET', '/me'), 200).memberId;
    await reset();
  });

  test.afterAll(async () => {
    await api(chairman, 'PUT', '/settings/delivery', { notifyPayments: true }).catch(() => undefined);
    for (const device of devices)
      await api(device.owner, 'DELETE', `/me/push/devices/${device.id}`).catch(() => undefined);
    for (const plot of plots) await reversePayments(chairman, plot.id);
    for (const fixture of fixtures) await cleanupMeterPlot(chairman, fixture.plot.id).catch(() => undefined);
    await archiveLeftovers(chairman, plots, members, kyivDay(0)).catch(() => undefined);
    await reset().catch(() => undefined);
    for (const page of [...extraPages, chairman, member]) await page.context().close();
  });

  test('a submitted reading sends the chairman one READINGS_TO_APPROVE and a second one within the hour sends none', async () => {
    const fixture = await createFixture(chairman, memberId);
    fixtures.push(fixture);
    const approver = await register(chairman, 'E2E chairman phone');
    const submitter = await register(member, 'E2E member phone');
    devices.push(approver, submitter);
    await reset();

    await submit(member, fixture, '120');
    const [first] = await pushesOf(approver, 1);
    expect(first?.headers['topic']).toBe('readings-approvals');
    const payload = decryptJson(first as FakeReceived, approver.keys);
    expect(payload['code']).toBe('READINGS_TO_APPROVE');
    expect(String(payload['url'])).toBe('/readings/approvals');
    expectNothingPersonal(payload, [...memberNames, fixture.plot.number, fixture.serial, '120']);

    // a second reading (another value, so it is a new one) within the hour: still the one push
    await submit(member, fixture, '121');
    await sleep(QUIET_MS);
    expect(await received(approver.subscription)).toHaveLength(1);

    // the member who submitted is told nothing of their own submission
    expect(await received(submitter.subscription)).toHaveLength(0);
  });

  test('approving the reading sends the member READING_APPROVED with the path of their meters', async () => {
    const fixture = fixtures[0];
    expect(fixture).toBeDefined();
    const submitter = devices[1] as Device;
    const history = expectCreated<{ readings: { id: string; status: string }[] }>(
      await api(chairman, 'GET', `/meters/${fixture?.meterId}/readings`),
      200,
    );
    const pending = history.readings.find((r) => r.status === 'PENDING');
    expect(pending).toBeDefined();
    expectCreated(await api(chairman, 'POST', `/readings/${pending?.id}/approve`), 200);

    const [message] = await pushesOf(submitter, 1);
    const payload = decryptJson(message as FakeReceived, submitter.keys);
    expect(payload['code']).toBe('READING_APPROVED');
    expect(String(payload['url'])).toBe('/account#my-meters');
    expectNothingPersonal(payload, [
      ...memberNames,
      fixture?.plot.number ?? '',
      fixture?.serial ?? '',
      '121',
    ]);
  });

  test('a board member who owns the plot and submits is not told to approve their own reading', async () => {
    const fixture = await createFixture(chairman, chairmanId);
    fixtures.push(fixture);
    const approver = devices[0] as Device;
    await reset();
    await submit(chairman, fixture, '130');
    await sleep(QUIET_MS);
    expect(await received(approver.subscription)).toHaveLength(0);
  });

  test('a recorded payment sends the payer PAYMENT_RECORDED while payment notices are on and nothing while they are off', async () => {
    const plot = await createPlot(chairman, `E2E-PP${Date.now().toString(36)}`);
    plots.push(plot);
    await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId, share: '1/1' }]);
    const device = devices[1] as Device;
    await reset();

    const settings = expectCreated<{ notifyPayments: boolean }>(
      await api(chairman, 'GET', '/settings/delivery'),
      200,
    );
    expect(settings.notifyPayments).toBe(true);
    await pay(chairman, plot.id, memberId, '432.10');
    const [message] = await pushesOf(device, 1);
    const payload = decryptJson(message as FakeReceived, device.keys);
    expect(payload['code']).toBe('PAYMENT_RECORDED');
    expect(String(payload['url'])).toBe('/account#my-charges');
    expectNothingPersonal(payload, [...memberNames, plot.number, '432']);

    try {
      expectCreated(await api(chairman, 'PUT', '/settings/delivery', { notifyPayments: false }), 200);
      await pay(chairman, plot.id, memberId, '11.00');
      await sleep(QUIET_MS);
      expect(await received(device.subscription)).toHaveLength(1);
    } finally {
      expectCreated(await api(chairman, 'PUT', '/settings/delivery', { notifyPayments: true }), 200);
    }
  });

  test('an announcement sends its recipient ANNOUNCEMENT with a path and not its text', async () => {
    const street = `E2E Street Ann${Date.now().toString(36)}`;
    // a plot on a street of its own, so that its owner is the whole audience
    const own = await createFixture(chairman, memberId, street);
    fixtures.push(own);
    const device = devices[1] as Device;
    await reset();

    const text = `Збори на вулиці ${street}`;
    const sent = await api(chairman, 'POST', '/announcements?preview=false', {
      text,
      streets: [street],
      channels: ['EMAIL'],
    });
    expect([200, 201]).toContain(sent.status);

    const [message] = await pushesOf(device, 1);
    const payload = decryptJson(message as FakeReceived, device.keys);
    expect(payload['code']).toBe('ANNOUNCEMENT');
    expect(String(payload['url'])).toBe('/account');
    expectNothingPersonal(payload, [...memberNames, own.plot.number, street, text, 'Збори']);
  });

  for (const how of ['disable', 'archive'] as const) {
    test(`a member whose access is turned off by ${how} loses the devices: the next payment pushes nothing`, async ({
      browser,
    }) => {
      const email = `push-${randomBytes(4).toString('hex')}@e2e.local`;
      const leaver = await createMember(chairman, `PushLeave${randomBytes(3).toString('hex')}`, email);
      members.push(leaver);
      expectCreated(await api(chairman, 'POST', `/members/${leaver.id}/invitation`), 200);
      const link = await letterLink((await waitForLetterTo(email)).id);

      const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
      const page = await context.newPage();
      extraPages.push(page);
      await page.addInitScript(() => window.localStorage.setItem('nadel.locale', 'en'));
      await page.goto(link);
      const password = `Pw-${randomBytes(6).toString('hex')}`;
      await page.getByLabel(t.setPassword.password, { exact: true }).fill(password);
      await page.getByLabel(t.setPassword.confirm).fill(password);
      await page.getByRole('button', { name: t.setPassword.submit }).click();
      await expect(page.getByText(t.setPassword.done)).toBeVisible();
      await page.getByRole('button', { name: t.setPassword.signIn }).click();
      await signInAtKeycloak(page, email, password);
      await expect(page.getByRole('banner').getByRole('button', { name: t.auth.signOut })).toBeVisible();

      const plot = await createPlot(chairman, `E2E-PL${Date.now().toString(36)}${how[0]}`);
      plots.push(plot);
      await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId: leaver.id, share: '1/1' }]);
      const device = await register(page, `E2E leaver ${how}`);
      devices.push(device);
      await reset();

      // while they have access a payment naming them reaches the device: the control for what follows
      await pay(chairman, plot.id, leaver.id, '50.00');
      await pushesOf(device, 1);

      if (how === 'disable')
        expectCreated(await api(chairman, 'POST', `/members/${leaver.id}/access/disable`), 200);
      else {
        await changeOwners(chairman, plot.id, kyivDay(0), []);
        expectCreated(await api(chairman, 'POST', `/members/${leaver.id}/archive`), 200);
      }
      await sleep(3000); // the listener of MemberAccessRevoked runs after the commit

      await reset();
      await pay(chairman, plot.id, leaver.id, '51.00');
      await nothingFor(device);
    });
  }

  test('the account page opens scrolled to «My meters» for the link of the push', async ({ browser }) => {
    const fixture = await createFixture(chairman, memberId);
    fixtures.push(fixture);
    const context = await browser.newContext({
      baseURL: 'http://localhost:5173',
      locale: 'en-US',
      viewport: { width: 1000, height: 240 },
    });
    const page = await context.newPage();
    extraPages.push(page);
    await loginAs(page, 'member@demo.local');
    const heading = page.getByRole('heading', { name: t.readings.mine.title, exact: true });
    // without the link the block lies below the first screen of this small window
    await page.goto('/account');
    await expect(heading).toBeAttached();
    await expect(heading).not.toBeInViewport();
    await page.goto('about:blank');
    // with the link of the push the page scrolls to it
    await page.goto('/account#my-meters');
    await expect(heading).toBeVisible();
    await expect(heading).toBeInViewport();
  });

  test('the member of another cooperative gets nothing of this cooperative', async ({ browser }) => {
    const other = await signedInPage(browser, 'member@other.local');
    extraPages.push(other);
    const stranger = await register(other, 'E2E other cooperative');
    devices.push(stranger);
    await reset();
    await submit(member, fixtures[0] as Fixture, '140').catch(() => undefined);
    await nothingFor(stranger);
  });
});
