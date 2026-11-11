// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// A member submits a meter reading with a photo from a phone (feature 021 section 2.3): at 360x740 on a touch screen the
// owner opens «My account», adds a JPEG, submits and sees the reading pending; the chairman finds the photo thumbnail on
// the approvals page, opens the viewer and approves. A 4000 px photo is stored at 2000 px on the long side; a file that
// is not a photo shows an error and saves nothing; a person who does not own the plot gets 404 for the photo. Every test
// CREATES its own plot, owner and meter and removes them in `finally` (the way meters.spec.ts does).
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  cleanupMeterPlot,
  expectCreated,
  kyivDay,
  shortDay,
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

/** Width and height from the first start-of-frame marker of a JPEG. */
function jpegSize(bytes: Buffer): { width: number; height: number } {
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8) throw new Error('not a JPEG');
  let offset = 2;
  while (offset + 9 < bytes.length) {
    if (bytes[offset] !== 0xff) throw new Error('bad marker');
    const marker = bytes[offset + 1] ?? 0;
    if (marker === 0xff) {
      offset += 1;
      continue;
    }
    const length = bytes.readUInt16BE(offset + 2);
    const isSof = marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker);
    if (isSof) return { height: bytes.readUInt16BE(offset + 5), width: bytes.readUInt16BE(offset + 7) };
    offset += 2 + length;
  }
  throw new Error('no SOF marker');
}

/** GET of the photo with the signed-in person's token; the status and the bytes. */
async function fetchPhoto(page: Page, readingId: string): Promise<{ status: number; bytes: Buffer }> {
  const result = await page.evaluate(async (id) => {
    const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
    const token = key
      ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
      : {};
    const response = await fetch(`/api/v1/readings/${id}/photo`, {
      headers: { Authorization: `Bearer ${token.access_token ?? ''}` },
    });
    const bytes = new Uint8Array(await response.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000)
      binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return { status: response.status, base64: btoa(binary) };
  }, readingId);
  return { status: result.status, bytes: Buffer.from(result.base64, 'base64') };
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

async function openSubmitDialog(member: Page, fixture: Fixture) {
  await member.goto('/account');
  const mine = main(member).locator('section[aria-labelledby="my-meters"]');
  const meterCard = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
  await meterCard.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
  const dialog = member.getByRole('dialog');
  await expect(dialog).toBeVisible();
  return { meterCard, dialog };
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

test.describe('a reading with a photo', () => {
  test.describe.configure({ timeout: 90_000 });
  test.beforeAll(async ({ browser }) => {
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

  test('the member submits a reading with a photo on a phone and the chairman sees it and approves @smoke', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const jpeg = await makeJpeg(member, 800, 600);
        const { meterCard, dialog } = await openSubmitDialog(member, fixture);
        await noSideScroll(member);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await expect(dialog.getByRole('button', { name: t.readings.photo.add, exact: true })).toBeVisible();
        await dialog
          .getByTestId('photo-input')
          .setInputFiles({ name: 'meter.jpg', mimeType: 'image/jpeg', buffer: jpeg });
        await expect(dialog.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await expect(
          dialog.getByRole('button', { name: t.readings.photo.replace, exact: true }),
        ).toBeVisible();
        await noSideScroll(member);
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
        await expect(meterCard.getByText(t.readings.mine.submitted)).toBeVisible();
        await expect(meterCard.getByText('pending', { exact: true })).toBeVisible();
        await noSideScroll(member);
      } finally {
        await member.context().close();
      }

      const stored = (await readingsOf(page, fixture.meterId)).filter((r) => r.status === 'PENDING');
      expect(stored).toHaveLength(1);
      expect(stored[0]?.hasPhoto).toBe(true);

      await page.locator('#app-menu nav').getByRole('link', { name: 'To approve', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Readings to approve' })).toBeVisible();
      const item = main(page)
        .getByRole('list', { name: 'Readings submitted by owners' })
        .getByRole('listitem')
        .filter({ hasText: `Plot ${fixture.plot.number}` });
      await expect(item).toContainText(`Reading 120 kWh on ${shortDay(kyivDay(0))}`);
      const thumb = item.getByRole('button', { name: t.readings.photo.open, exact: true });
      await expect(thumb.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
      await thumb.click();
      const viewer = page.getByRole('dialog');
      await expect(viewer).toBeVisible();
      await expect(viewer.getByRole('img').first()).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await item.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect(item).toHaveCount(0);

      const after = await readingsOf(page, fixture.meterId);
      expect(after.map((r) => r.status)).toEqual(['APPROVED']);
      expect(after[0]?.hasPhoto).toBe(true);
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('a 4000 px photo is stored at no more than 2000 px on the long side', async ({ browser, page }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const big = await makeJpeg(member, 4000, 3000);
        expect(jpegSize(big)).toEqual({ width: 4000, height: 3000 });
        const { dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('125');
        await dialog
          .getByTestId('photo-input')
          .setInputFiles({ name: 'big.jpg', mimeType: 'image/jpeg', buffer: big });
        await expect(dialog.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);

        const pending = (await readingsOf(page, fixture.meterId)).filter((r) => r.status === 'PENDING');
        expect(pending).toHaveLength(1);
        const photo = await fetchPhoto(member, pending[0]?.id ?? '');
        expect(photo.status).toBe(200);
        const size = jpegSize(photo.bytes);
        expect(Math.max(size.width, size.height)).toBeLessThanOrEqual(2000);
        expect(Math.max(size.width, size.height)).toBeGreaterThan(0);
        // the picture keeps its proportions (4:3)
        expect(size.width / size.height).toBeCloseTo(4 / 3, 1);
      } finally {
        await member.context().close();
      }
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('a file that is not a photo saves no reading, and the API refuses a PDF part with FILE_TYPE_NOT_ALLOWED', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const { dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('130');
        await dialog.getByTestId('photo-input').setInputFiles({
          name: 'notes.jpg',
          mimeType: 'image/jpeg',
          buffer: Buffer.from('this is plain text, not a picture'),
        });
        const refused = member.waitForResponse(
          (r) => r.url().includes('/reading-submissions') && r.request().method() === 'POST',
        );
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        const response = await refused;
        expect(response.status()).toBe(400);
        expect(await response.text()).toContain('FILE_TYPE_NOT_ALLOWED');
        await expect(member.getByRole('dialog')).toBeVisible(); // the dialog stays open
        await noSideScroll(member);

        // the same refusal straight at the API for a PDF part
        const pdf = await member.evaluate(
          async ({ plotId, meterId }) => {
            const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
            const token = key
              ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
              : {};
            const form = new FormData();
            form.append(
              'reading',
              new Blob([JSON.stringify({ meterId, value: '131' })], { type: 'application/json' }),
            );
            form.append('photo', new Blob(['%PDF-1.4\n%fake\n'], { type: 'application/pdf' }), 'scan.pdf');
            const r = await fetch(`/api/v1/plots/${plotId}/reading-submissions`, {
              method: 'POST',
              headers: { Authorization: `Bearer ${token.access_token ?? ''}` },
              body: form,
            });
            return { status: r.status, body: await r.text() };
          },
          { plotId: fixture.plot.id, meterId: fixture.meterId },
        );
        expect(pdf.status).toBe(400);
        expect(pdf.body).toContain('FILE_TYPE_NOT_ALLOWED');
      } finally {
        await member.context().close();
      }
      expect(await readingsOf(page, fixture.meterId)).toHaveLength(0);
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('a file that is not a photo shows the error in the dialog', async ({ browser, page }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      try {
        const { dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('132');
        await dialog.getByTestId('photo-input').setInputFiles({
          name: 'notes.jpg',
          mimeType: 'image/jpeg',
          buffer: Buffer.from('this is plain text, not a picture'),
        });
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(dialog.getByText(t.problem.FILE_TYPE_NOT_ALLOWED)).toBeVisible({ timeout: 5000 });
      } finally {
        await member.context().close();
      }
    } finally {
      await cleanup(browser, fixture);
    }
  });

  test('another person gets not found for the photo, even a member of the same cooperative', async ({
    browser,
    page,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local', true);
      let readingId = '';
      try {
        const jpeg = await makeJpeg(member, 600, 400);
        const { dialog } = await openSubmitDialog(member, fixture);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('140');
        await dialog
          .getByTestId('photo-input')
          .setInputFiles({ name: 'meter.jpg', mimeType: 'image/jpeg', buffer: jpeg });
        await expect(dialog.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
        readingId = (await readingsOf(page, fixture.meterId))[0]?.id ?? '';
        expect(readingId).not.toBe('');
        expect((await fetchPhoto(member, readingId)).status).toBe(200);
      } finally {
        await member.context().close();
      }
      expect((await fetchPhoto(page, readingId)).status).toBe(200); // the board sees it
      for (const email of ['member@other.local']) {
        const other = await signedInPage(browser, email, true);
        try {
          expect((await fetchPhoto(other, readingId)).status, email).toBe(404);
        } finally {
          await other.context().close();
        }
      }
    } finally {
      await cleanup(browser, fixture);
    }
  });
});
