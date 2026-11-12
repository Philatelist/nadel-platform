import { type Browser, type Page } from '@playwright/test';
import { api, archiveLeftovers, cleanupMeterPlot, expectCreated, kyivDay, type CreatedPlot } from './api';
import { loginAs } from './login';
import { PHONE } from './phone';

/** A signed-in page in its own context: a 360x740 touch phone, or a desktop window for the chairman's set-up. */
export async function signedInPage(browser: Browser, email: string, phone = true): Promise<Page> {
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

/** A JPEG drawn by the browser. */
export async function makeJpeg(page: Page, width: number, height: number): Promise<Buffer> {
  const base64 = await page.evaluate(
    async ({ width, height }) => {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('no 2d context');
      ctx.fillStyle = '#2b6cb0';
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

export interface PhoneFixture {
  plot: CreatedPlot;
  serial: string;
  meterId: string;
}

export async function memberIdOf(browser: Browser): Promise<string> {
  const member = await signedInPage(browser, 'member@demo.local', false);
  try {
    return expectCreated<{ memberId: string }>(await api(member, 'GET', '/me'), 200).memberId;
  } finally {
    await member.context().close();
  }
}

/** A plot owned by the demo member with an electricity meter installed 30 days ago at 100 (created by the chairman). */
export async function createPhoneFixture(
  chairman: Page,
  stamp: string,
  ownerId: string,
): Promise<PhoneFixture> {
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

/** Removes leftovers of earlier runs (E2E-P-) and then this run's fixture. */
export async function cleanupPhoneFixtures(browser: Browser, fixture?: PhoneFixture): Promise<void> {
  const chairman = await signedInPage(browser, 'chairman@demo.local', false).catch(() => undefined);
  if (!chairman) return;
  try {
    if (fixture) await cleanupMeterPlot(chairman, fixture.plot.id);
  } finally {
    await chairman.context().close();
  }
}
