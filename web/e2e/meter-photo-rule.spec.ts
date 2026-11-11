// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// The photo rule (feature 021 section 2.3): the chairman turns «Meter photo required for members» on in Settings; a
// member at 360x740 submitting a reading without a photo gets the problem message and nothing is saved, with a photo the
// reading is saved. With the rule on the electrician (READINGS_ENTER) still saves a reading without a photo on the round
// page and may add one through the row's «Add a photo». With the rule off a member saves without a photo. Every test
// CREATES its own plot, owner and meter and removes them in `finally`; the setting is always restored to off in
// `afterEach` and `afterAll` (also when a test fails).
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  cleanupMeterPlot,
  expectCreated,
  kyivDay,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const t = messages('en');
const main = (page: Page) => page.getByRole('main');
const PHONE = { width: 360, height: 740 };

async function signedInPage(browser: Browser, email: string, phone = false): Promise<Page> {
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

/** A JPEG of the given size drawn by the browser (a gradient with a mark, so it is a real photo-like picture). */
async function makeJpeg(page: Page, width: number, height: number): Promise<Buffer> {
  const base64 = await page.evaluate(
    async ({ width, height }) => {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('no 2d context');
      const gradient = ctx.createLinearGradient(0, 0, width, height);
      gradient.addColorStop(0, '#2b6cb0');
      gradient.addColorStop(1, '#f6ad55');
      ctx.fillStyle = gradient;
      ctx.fillRect(0, 0, width, height);
      ctx.fillStyle = '#111';
      ctx.fillRect(width / 4, height / 4, width / 2, height / 8);
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8));
      if (!blob) throw new Error('no jpeg');
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let binary = '';
      for (let i = 0; i < bytes.length; i += 0x8000)
        binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return btoa(binary);
    },
    { width, height },
  );
  return Buffer.from(base64, 'base64');
}

interface Fixture {
  plot: CreatedPlot;
  serial: string;
  meterId: string;
}

/** A plot owned by the demo member with an electricity meter installed 30 days ago at 100. */
async function createFixture(chairman: Page, stamp: string, ownerId: string): Promise<Fixture> {
  const number = `E2E-P-${stamp}`;
  const serial = `E2E-PSN-${stamp}`;
  const created = expectCreated<{ id: string }>(
    await api(chairman, 'POST', '/plots', {
      number,
      kind: 'PLOT',
      areaM2: 400,
      street: `E2E Street P${stamp}`,
    }),
  );
  const plot = { id: created.id, number };
  try {
    expectCreated(
      await api(chairman, 'POST', `/plots/${plot.id}/ownership-changes`, {
        effectiveFrom: kyivDay(-40),
        owners: [{ memberId: ownerId, share: '1/1' }],
      }),
    );
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
    return { plot, serial, meterId: meter.id };
  } catch (error) {
    await archiveLeftovers(chairman, [plot], [], kyivDay(0));
    throw error;
  }
}

async function cleanup(browser: Browser, fixture: Fixture | undefined): Promise<void> {
  if (!fixture) return;
  const chairman = await signedInPage(browser, 'chairman@demo.local').catch(() => undefined);
  if (!chairman) return;
  try {
    await cleanupMeterPlot(chairman, fixture.plot.id);
  } finally {
    await chairman.context().close();
  }
}

async function memberIdOf(browser: Browser): Promise<string> {
  const member = await signedInPage(browser, 'member@demo.local');
  try {
    return expectCreated<{ memberId: string }>(await api(member, 'GET', '/me'), 200).memberId;
  } finally {
    await member.context().close();
  }
}

async function readingsOf(chairman: Page, meterId: string) {
  return expectCreated<{ readings: { id: string; status: string; value: string; hasPhoto?: boolean }[] }>(
    await api(chairman, 'GET', `/meters/${meterId}/readings`),
    200,
  ).readings;
}

async function noSideScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

async function setPhotoRule(browser: Browser, required: boolean): Promise<void> {
  const chairman = await signedInPage(browser, 'chairman@demo.local');
  try {
    expectCreated(await api(chairman, 'PUT', '/settings', { readingPhotoRequired: required }), 200);
  } finally {
    await chairman.context().close();
  }
}

async function openSubmitDialog(member: Page, fixture: Fixture) {
  await member.goto('/account');
  const mine = main(member).locator('section[aria-labelledby="my-meters"]');
  const meterCard = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
  await meterCard.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
  const dialog = member.getByRole('dialog');
  await expect(dialog).toBeVisible();
  return { meterCard, dialog };
}

test.describe('the photo rule', () => {
  test.describe.configure({ timeout: 120_000, mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    await setPhotoRule(browser, false);
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(chairman, 'GET', '/plots?q=E2E-P-'),
        200,
      );
      for (const p of found.items.filter((p) => p.number.startsWith('E2E-P-')))
        await cleanupMeterPlot(chairman, p.id);
    } finally {
      await chairman.context().close();
    }
  });
  test.afterEach(async ({ browser }) => {
    await setPhotoRule(browser, false);
  });
  test.afterAll(async ({ browser }) => {
    await setPhotoRule(browser, false);
  });

  test('rule on: a member at 360 px is refused without a photo, saved with one; the chairman turns it on in Settings', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));

      // the chairman turns the rule on in the Settings UI
      await page.goto('/settings');
      const panel = main(page).locator('section, div').filter({ hasText: t.settings.readings.title }).last();
      const toggle = main(page).getByRole('switch', { name: t.settings.readings.photoRequired });
      await expect(toggle).not.toBeChecked();
      await toggle.check();
      await main(page).getByRole('button', { name: t.settings.readings.save, exact: true }).click();
      await expect(panel).toContainText(t.settings.saved);
      await page.reload();
      await expect(main(page).getByRole('switch', { name: t.settings.readings.photoRequired })).toBeChecked();

      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const jpeg = await makeJpeg(member, 800, 600);
        const { meterCard, dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(
          dialog.getByText('Add a photo of the meter: the board asks for a photo with every reading.'),
        ).toBeVisible({ timeout: 5000 });
        await expect(member.getByRole('dialog')).toBeVisible();
        await noSideScroll(member);
        expect(await readingsOf(page, fixture.meterId)).toHaveLength(0);

        await dialog
          .getByTestId('photo-input')
          .setInputFiles({ name: 'meter.jpg', mimeType: 'image/jpeg', buffer: jpeg });
        await expect(dialog.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
        await expect(meterCard.getByText(t.readings.mine.submitted)).toBeVisible();
      } finally {
        await member.context().close();
      }
      const stored = await readingsOf(page, fixture.meterId);
      expect(stored).toHaveLength(1);
      expect(stored[0]?.hasPhoto).toBe(true);
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('rule on: the API refuses a member submission without a photo with READING_PHOTO_REQUIRED', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      await setPhotoRule(browser, true);
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const refused = await member.evaluate(
          async ({ plotId, meterId }) => {
            const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
            const token = key
              ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
              : {};
            const form = new FormData();
            form.append(
              'reading',
              new Blob([JSON.stringify({ meterId, value: '121' })], { type: 'application/json' }),
            );
            const r = await fetch(`/api/v1/plots/${plotId}/reading-submissions`, {
              method: 'POST',
              headers: { Authorization: `Bearer ${token.access_token ?? ''}` },
              body: form,
            });
            return { status: r.status, body: await r.text() };
          },
          { plotId: fixture.plot.id, meterId: fixture.meterId },
        );
        expect(refused.status).toBe(400);
        expect(refused.body).toContain('READING_PHOTO_REQUIRED');
      } finally {
        await member.context().close();
      }
      expect(await readingsOf(page, fixture.meterId)).toHaveLength(0);
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('rule on: the electrician saves a reading without a photo and with one through «Add a photo», no side scroll at 360 px', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      await setPhotoRule(browser, true);
      const electrician = await signedInPage(browser, 'electrician@demo.local', true);
      try {
        const jpeg = await makeJpeg(electrician, 800, 600);
        await expect(electrician).toHaveURL(/\/readings$/);
        await main(electrician).getByLabel('Street', { exact: true }).fill(`E2E Street P${stamp}`);
        const cards = main(electrician)
          .getByRole('list', { name: 'Meters of the round' })
          .getByRole('listitem');
        await expect(cards).toHaveCount(1);
        const card = cards.first();
        await expect(card).toContainText(`Plot ${fixture.plot.number}`);
        await noSideScroll(electrician);

        // without a photo (the rule binds members only)
        await card.getByLabel('Reading date for this meter').fill(kyivDay(-10));
        await card.getByLabel('Reading, kWh', { exact: true }).fill('110');
        await card.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(card.getByText(t.readings.saved, { exact: true })).toBeVisible();
        let stored = await readingsOf(page, fixture.meterId);
        expect(stored.filter((r) => r.status === 'APPROVED')).toHaveLength(1);
        expect(stored[0]?.hasPhoto ?? false).toBe(false);

        // with a photo through the row's «Add a photo»
        await card.getByLabel('Reading date for this meter').fill(kyivDay(0));
        await card.getByLabel('Reading, kWh', { exact: true }).fill('130');
        await expect(card.getByRole('button', { name: t.readings.photo.addRow, exact: true })).toBeVisible();
        await card
          .getByTestId('photo-input')
          .setInputFiles({ name: 'meter.jpg', mimeType: 'image/jpeg', buffer: jpeg });
        await expect(card.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await noSideScroll(electrician);
        await card.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(card.getByText(t.readings.saved, { exact: true })).toBeVisible();
        await noSideScroll(electrician);
        stored = await readingsOf(page, fixture.meterId);
        expect(stored.filter((r) => r.status === 'APPROVED')).toHaveLength(2);
        expect(stored.filter((r) => r.hasPhoto === true)).toHaveLength(1);
        expect(stored.find((r) => r.value.startsWith('130'))?.hasPhoto).toBe(true);
      } finally {
        await electrician.context().close();
      }
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('rule off: a member saves a reading without a photo', async ({ browser, page }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const { meterCard, dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
        await expect(meterCard.getByText(t.readings.mine.submitted)).toBeVisible();
      } finally {
        await member.context().close();
      }
      const stored = await readingsOf(page, fixture.meterId);
      expect(stored).toHaveLength(1);
      expect(stored[0]?.status).toBe('PENDING');
      expect(stored[0]?.hasPhoto ?? false).toBe(false);
    } finally {
      await cleanup(browser, fixture);
    }
  });
});
