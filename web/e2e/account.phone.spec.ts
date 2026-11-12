// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// «My account» on a 360x740 touch phone (feature 021 section 2.4): no side scroll and every control at least 44 px tall,
// as the member (all blocks, scrolled to the end), as the chairman, in the member's readings dialog with the photo step
// open and in the member's invoices with the pay and copy buttons. Meter data is created by the chairman and removed in
// `finally` (the way meter-photo.spec.ts does); invoices are issued on request when the member has none to pay.
import { expect, test, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, cleanupMeterPlot, demoMonths, expectCreated } from './support/api';
import { expectNoSideScroll, expectTouchTargets } from './support/phone';
import {
  createPhoneFixture,
  makeJpeg,
  memberIdOf,
  signedInPage,
  type PhoneFixture,
} from './support/phoneFixture';

const t = messages('en');
const main = (page: Page) => page.getByRole('main');

async function scrollThrough(page: Page): Promise<void> {
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await page.waitForLoadState('networkidle');
}

test.describe('«My account» on a phone', () => {
  test.describe.configure({ timeout: 90_000 });

  test('the member sees every block without side scroll and with 44 px controls @smoke', async ({
    browser,
  }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    try {
      await member.goto('/account');
      await expect(member.getByRole('heading', { level: 1 })).toBeVisible();
      await member.waitForLoadState('networkidle');
      await expectNoSideScroll(member);
      await expectTouchTargets(member);
      await scrollThrough(member);
      await expectNoSideScroll(member);
      await expectTouchTargets(member);
    } finally {
      await member.context().close();
    }
  });

  test('«My details» fits the phone for a member and the menu holds the person, language and Sign out', async ({
    browser,
  }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    try {
      await member.goto('/account');
      await expect(member.getByRole('heading', { level: 1 })).toBeVisible();
      // the header is one compact row: the name, «?» and Menu; the rest is inside the menu
      const header = member.getByRole('banner');
      await expect(header.getByRole('button', { name: t.nav.menu, exact: true })).toBeVisible();
      await expect(header.getByRole('button', { name: t.auth.signOut })).toBeHidden();
      expect((await header.boundingBox())?.height ?? 999).toBeLessThanOrEqual(80);
      await header.getByRole('button', { name: t.nav.menu, exact: true }).click();
      const menu = member.locator('#app-menu');
      await expect(menu.getByRole('group', { name: t.nav.language })).toBeVisible();
      await expect(menu.getByRole('button', { name: t.auth.signOut })).toBeVisible();
      await menu.locator('a[href="/account/profile"]').click();
      await expect(member).toHaveURL(/\/account\/profile$/);
      await expect(member.getByRole('heading', { level: 1, name: t.account.details })).toBeVisible();
      await member.waitForLoadState('networkidle');
      await expectNoSideScroll(member);
      await expectTouchTargets(member);
      await scrollThrough(member);
      await expectNoSideScroll(member);
      await expectTouchTargets(member);
    } finally {
      await member.context().close();
    }
  });

  test('the chairman sees every block without side scroll and with 44 px controls', async ({ browser }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      await chairman.goto('/account');
      await expect(chairman.getByRole('heading', { level: 1 })).toBeVisible();
      await chairman.waitForLoadState('networkidle');
      await expectNoSideScroll(chairman);
      await expectTouchTargets(chairman);
      await scrollThrough(chairman);
      await expectNoSideScroll(chairman);
      await expectTouchTargets(chairman);
    } finally {
      await chairman.context().close();
    }
  });

  test("the member's readings dialog with the photo step fits the phone", async ({ browser }) => {
    const stamp = Date.now().toString(36);
    let fixture: PhoneFixture | undefined;
    const chairman = await signedInPage(browser, 'chairman@demo.local', false);
    try {
      fixture = await createPhoneFixture(chairman, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local');
      try {
        await member.goto('/account');
        const card = main(member)
          .locator('section[aria-labelledby="my-meters"]')
          .getByRole('listitem')
          .filter({ hasText: fixture.serial })
          .first();
        await card.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        const dialog = member.getByRole('dialog');
        await expect(dialog).toBeVisible();
        await expect(dialog.getByRole('button', { name: t.readings.photo.add, exact: true })).toBeVisible();
        await expectNoSideScroll(member);
        await expectTouchTargets(member, dialog);
        const box = await dialog.boundingBox();
        expect(box?.height ?? 0, 'the dialog fits the viewport height').toBeLessThanOrEqual(740);
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByTestId('photo-input').setInputFiles({
          name: 'meter.jpg',
          mimeType: 'image/jpeg',
          buffer: await makeJpeg(member, 800, 600),
        });
        await expect(dialog.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await expectNoSideScroll(member);
        await expectTouchTargets(member, dialog);
      } finally {
        await member.context().close();
      }
    } finally {
      if (fixture) await cleanupMeterPlot(chairman, fixture.plot.id);
      await chairman.context().close();
    }
  });

  test("the member's invoices show the pay and copy buttons within the phone", async ({ browser }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    try {
      const mine = async () =>
        expectCreated<{ payment?: unknown }[]>(await api(member, 'GET', '/me/invoices'), 200);
      if (!(await mine()).some((r) => r.payment !== undefined)) {
        const treasurer = await signedInPage(browser, 'treasurer@demo.local', false);
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
      await member.goto('/account');
      const section = main(member).locator('section', {
        has: member.getByRole('heading', { name: t.invoices.myTitle, exact: true }),
      });
      await expect(section).toBeVisible();
      await expect(
        section.getByRole('link', { name: t.invoices.pay.payInBank, exact: true }).first(),
      ).toBeVisible();
      await expect(
        section.getByRole('button', { name: t.invoices.pay.copyIban, exact: true }).first(),
      ).toBeVisible();
      await expectNoSideScroll(member);
      await expectTouchTargets(member, section);
    } finally {
      await member.context().close();
    }
  });
});
