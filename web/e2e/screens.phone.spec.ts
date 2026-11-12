// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// Admin and field screens on a 360x740 touch phone (feature 021 section 2.4): «Readings to approve» with a pending reading
// that has a photo, the electrician's readings round, «New payment» (treasurer) and the open help panel. Each fits without
// side scroll and every control is at least 44 px tall. The approvals test creates a plot, owner and meter, has the member
// submit a reading with a photo, and removes everything in `finally`.
import { expect, test, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { cleanupMeterPlot } from './support/api';
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

test.describe('admin and field screens on a phone', () => {
  test.describe.configure({ timeout: 90_000 });

  test('«Readings to approve» with a photo fits the phone', async ({ browser }) => {
    const stamp = Date.now().toString(36);
    let fixture: PhoneFixture | undefined;
    const setup = await signedInPage(browser, 'chairman@demo.local', false);
    try {
      fixture = await createPhoneFixture(setup, stamp, await memberIdOf(browser));
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
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByTestId('photo-input').setInputFiles({
          name: 'meter.jpg',
          mimeType: 'image/jpeg',
          buffer: await makeJpeg(member, 800, 600),
        });
        await dialog.getByRole('button', { name: t.readings.mine.submit, exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
      } finally {
        await member.context().close();
      }

      const chairman = await signedInPage(browser, 'chairman@demo.local');
      try {
        await chairman.goto('/readings/approvals');
        const item = main(chairman)
          .getByRole('list', { name: 'Readings submitted by owners' })
          .getByRole('listitem')
          .filter({ hasText: `Plot ${fixture.plot.number}` });
        await expect(item.getByRole('img', { name: t.readings.photo.thumbnailAlt })).toBeVisible();
        await expectNoSideScroll(chairman);
        await expectTouchTargets(chairman);
      } finally {
        await chairman.context().close();
      }
    } finally {
      if (fixture) await cleanupMeterPlot(setup, fixture.plot.id);
      await setup.context().close();
    }
  });

  test("the electrician's readings round fits the phone", async ({ browser }) => {
    const electrician = await signedInPage(browser, 'electrician@demo.local');
    try {
      await electrician.goto('/readings');
      await expect(
        main(electrician).getByRole('list', { name: 'Meters of the round' }).getByRole('listitem').first(),
      ).toBeVisible();
      await electrician.waitForLoadState('networkidle');
      await expectNoSideScroll(electrician);
      await expectTouchTargets(electrician);
    } finally {
      await electrician.context().close();
    }
  });

  test('«New payment» fits the phone', async ({ browser }) => {
    const treasurer = await signedInPage(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/payments/new');
      await expect(treasurer.getByRole('heading', { level: 1 })).toBeVisible();
      await treasurer.waitForLoadState('networkidle');
      await expectNoSideScroll(treasurer);
      await expectTouchTargets(treasurer);
    } finally {
      await treasurer.context().close();
    }
  });

  test('the help panel opened with «?» fits the phone', async ({ browser }) => {
    const member = await signedInPage(browser, 'member@demo.local');
    try {
      await member.goto('/account');
      await member.getByRole('button', { name: t.help.open }).click();
      const panel = member
        .getByRole('complementary')
        .filter({ has: member.getByRole('button', { name: t.help.close }) });
      await expect(panel).toBeVisible();
      await member.waitForLoadState('networkidle');
      await expectNoSideScroll(member);
      await expectTouchTargets(member, panel);
      const box = await panel.boundingBox();
      expect(box?.width ?? 0, 'the panel is as wide as the phone').toBeGreaterThanOrEqual(340);
    } finally {
      await member.context().close();
    }
  });
});
