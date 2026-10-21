// @layer: e2e
// @spec: 010-meters-and-readings
// @regression
// Meters and readings in a browser: the electrician's menu, landing and round (default date, changed date, interval
// consumption); the owner submits a reading and the chairman approves it; what a member and the treasurer see;
// the round at phone width. Demo meters are only read. Every test CREATES its own plot (unique street and number),
// owner and meter and removes its readings and the meter in `finally`; the plot is archived. Reruns never collide.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { api, archiveLeftovers, expectCreated, kyivDay, shortDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });

async function signedInPage(browser: Browser, email: string, viewport?: { width: number; height: number }) {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US', viewport });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

/** The member id of member@demo.local, from its own token. */
async function memberIdOf(browser: Browser): Promise<string> {
  const member = await signedInPage(browser, 'member@demo.local');
  try {
    return expectCreated<{ memberId: string }>(await api(member, 'GET', '/me'), 200).memberId;
  } finally {
    await member.context().close();
  }
}

interface Fixture {
  plot: CreatedPlot;
  street: string;
  serial: string;
  meterId: string;
}

/** A plot on a unique street, owned by the demo member, with an electricity meter installed 30 days ago at 100. */
async function createFixture(chairman: Page, stamp: string, ownerId?: string): Promise<Fixture> {
  const street = `E2E Street ${stamp}`;
  const number = `E2E-M-${stamp}`;
  const serial = `E2E-SN-${stamp}`;
  const created = expectCreated<{ id: string }>(
    await api(chairman, 'POST', '/plots', { number, kind: 'PLOT', areaM2: 400, street }),
  );
  const plot = { id: created.id, number };
  try {
    if (ownerId) {
      expectCreated(
        await api(chairman, 'POST', `/plots/${plot.id}/ownership-changes`, {
          effectiveFrom: kyivDay(-40),
          owners: [{ memberId: ownerId, share: '1/1' }],
        }),
      );
    }
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
    return { plot, street, serial, meterId: meter.id };
  } catch (error) {
    await archiveLeftovers(chairman, [plot], [], kyivDay(0));
    throw error;
  }
}

/** Best effort: deletes the counted readings and the meter, ends the ownership and archives the plot. */
async function cleanup(chairman: Page, fixtures: Fixture[]): Promise<void> {
  for (const f of fixtures) {
    const history = await api(chairman, 'GET', `/meters/${f.meterId}/readings`).catch(() => undefined);
    if (history?.status === 200) {
      const readings = (JSON.parse(history.body) as { readings: { id: string; status: string }[] }).readings;
      for (const r of readings)
        if (r.status === 'APPROVED')
          await api(chairman, 'DELETE', `/readings/${r.id}`).catch(() => undefined);
    }
    await api(chairman, 'DELETE', `/meters/${f.meterId}`).catch(() => undefined);
  }
  await archiveLeftovers(
    chairman,
    fixtures.map((f) => f.plot),
    [],
    kyivDay(0),
  );
}

test.describe('meters and readings', () => {
  // plots left active by an interrupted earlier run would show up in the plot registry: archive them
  test.beforeAll(async ({ browser }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    try {
      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(chairman, 'GET', '/plots?q=E2E-M-'),
        200,
      );
      await archiveLeftovers(
        chairman,
        found.items.filter((p) => p.number.startsWith('E2E-M-')),
        [],
        kyivDay(0),
      );
    } finally {
      await chairman.context().close();
    }
  });

  test('the electrician sees only Readings and Meters, lands on Readings and enters readings with the default and a changed date', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp);
      const electrician = await signedInPage(browser, 'electrician@demo.local');
      try {
        await expect(electrician).toHaveURL(/\/readings$/);
        await expect(electrician.getByRole('heading', { level: 1, name: 'Readings' })).toBeVisible();
        await expect(menuLink(electrician, 'Readings')).toBeVisible();
        await expect(menuLink(electrician, 'Meters')).toBeVisible();
        await expect(menuLink(electrician, 'Plots')).toHaveCount(0);
        await expect(menuLink(electrician, 'To approve')).toHaveCount(0);
        await expect(menuLink(electrician, 'Members')).toHaveCount(0);

        // narrow the round to this run's street
        await main(electrician).getByLabel('Street', { exact: true }).fill(fixture.street);
        const cards = main(electrician)
          .getByRole('list', { name: 'Meters of the round' })
          .getByRole('listitem');
        await expect(cards).toHaveCount(1);
        const card = cards.first();
        await expect(card).toContainText(`Plot ${fixture.plot.number}`);
        await expect(card).toContainText(`Last reading: 100 kWh, ${shortDay(kyivDay(-30))}`);

        // the default date is today
        await expect(card.getByLabel('Reading date for this meter')).toHaveValue(kyivDay(0));
        await card.getByLabel('Reading, kWh', { exact: true }).fill('130');
        await card.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(card.getByText('Saved', { exact: true })).toBeVisible();
        await expect(card).toContainText(
          `Interval ${shortDay(kyivDay(-30))} – ${shortDay(kyivDay(0))}: 30 kWh in 30 d`,
        );

        // a reading on another day lands between the two points and splits the interval
        await card.getByLabel('Reading date for this meter').fill(kyivDay(-10));
        await card.getByLabel('Reading, kWh', { exact: true }).fill('110');
        await card.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(card).toContainText(
          `Interval ${shortDay(kyivDay(-30))} – ${shortDay(kyivDay(-10))}: 10 kWh in 20 d`,
        );

        // both readings are in the meter's history for the chairman
        const history = expectCreated<{ readings: { status: string }[] }>(
          await api(page, 'GET', `/meters/${fixture.meterId}/readings`),
          200,
        );
        expect(history.readings.filter((r) => r.status === 'APPROVED')).toHaveLength(2);
      } finally {
        await electrician.context().close();
      }
    } finally {
      await cleanup(page, fixture ? [fixture] : []);
    }
  });

  test('an owner submits a reading and the chairman approves it', async ({ page, browser }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp, await memberIdOf(browser));
      const member = await signedInPage(browser, 'member@demo.local');
      try {
        await member.goto('/account');
        const mine = main(member).locator('section[aria-labelledby="my-meters"]');
        await expect(mine.getByRole('heading', { level: 2, name: 'My meters' })).toBeVisible();
        const meterCard = mine.getByRole('listitem').filter({ hasText: fixture.serial }).first();
        await meterCard.getByRole('button', { name: 'Submit a reading', exact: true }).click();
        const dialog = member.getByRole('dialog');
        await dialog.getByLabel('Reading, kWh', { exact: true }).fill('120');
        await dialog.getByRole('button', { name: 'Submit a reading', exact: true }).click();
        await expect(member.getByRole('dialog')).toHaveCount(0);
        await expect(meterCard.getByText('The reading is submitted and waits for approval')).toBeVisible();
        await expect(meterCard.getByText('pending', { exact: true })).toBeVisible();
      } finally {
        await member.context().close();
      }

      // the chairman sees it among the readings to approve and approves it
      await menuLink(page, 'To approve').click();
      await expect(page.getByRole('heading', { level: 1, name: 'Readings to approve' })).toBeVisible();
      const item = main(page)
        .getByRole('list', { name: 'Readings submitted by owners' })
        .getByRole('listitem')
        .filter({ hasText: `Plot ${fixture.plot.number}` });
      await expect(item).toContainText(`Reading 120 kWh on ${shortDay(kyivDay(0))}`);
      await expect(item).toContainText('Once approved: 20 kWh over 30 days');
      await item.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect(item).toHaveCount(0);

      const history = expectCreated<{ readings: { status: string; value: string }[] }>(
        await api(page, 'GET', `/meters/${fixture.meterId}/readings`),
        200,
      );
      expect(history.readings.map((r) => r.status)).toEqual(['APPROVED']);
    } finally {
      await cleanup(page, fixture ? [fixture] : []);
    }
  });

  test('a member sees only the meters of own plots and gets not found for another plot', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const fixtures: Fixture[] = [];
    await loginAs(page, 'chairman@demo.local');
    try {
      const own = await createFixture(page, `${stamp}a`, await memberIdOf(browser));
      fixtures.push(own);
      const other = await createFixture(page, `${stamp}b`);
      fixtures.push(other);
      const member = await signedInPage(browser, 'member@demo.local');
      try {
        // no register and no round for a member
        await expect(menuLink(member, 'Meters')).toHaveCount(0);
        await expect(menuLink(member, 'Readings')).toHaveCount(0);
        await member.goto('/meters');
        await expect(
          member.getByRole('heading', { level: 1, name: "You don't have access to this page" }),
        ).toBeVisible();

        await member.goto('/account');
        const mine = main(member).locator('section[aria-labelledby="my-meters"]');
        await expect(mine.getByText(own.serial)).toBeVisible();
        await expect(mine.getByText(other.serial)).toHaveCount(0);
        await expect(mine.getByText(`Plot ${other.plot.number}`)).toHaveCount(0);

        // the API refuses another plot's meters as not found, and so does the history of its meter
        expect((await api(member, 'GET', `/plots/${own.plot.id}/meters`)).status).toBe(200);
        expect((await api(member, 'GET', `/plots/${other.plot.id}/meters`)).status).toBe(404);
        expect((await api(member, 'GET', `/meters/${own.meterId}/readings`)).status).toBe(200);
        expect((await api(member, 'GET', `/meters/${other.meterId}/readings`)).status).toBe(404);
        expect(
          (
            await api(member, 'POST', `/plots/${other.plot.id}/readings`, {
              meterId: other.meterId,
              value: '120.000',
            })
          ).status,
        ).toBe(404);
      } finally {
        await member.context().close();
      }
    } finally {
      await cleanup(page, fixtures);
    }
  });

  test('the treasurer sees the register and the meter card without actions', async ({ page, browser }) => {
    const stamp = Date.now().toString(36);
    let fixture: Fixture | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      fixture = await createFixture(page, stamp);
      const treasurer = await signedInPage(browser, 'treasurer@demo.local');
      try {
        await expect(menuLink(treasurer, 'Meters')).toBeVisible();
        await expect(menuLink(treasurer, 'Readings')).toHaveCount(0);
        await menuLink(treasurer, 'Meters').click();
        await expect(treasurer.getByRole('heading', { level: 1, name: 'Meters' })).toBeVisible();
        await main(treasurer).getByLabel('Street', { exact: true }).fill(fixture.street);
        await expect(main(treasurer).getByRole('link', { name: fixture.serial })).toBeVisible();
        await expect(main(treasurer).getByRole('button', { name: 'Register a meter' })).toHaveCount(0);

        await main(treasurer).getByRole('link', { name: fixture.serial }).click();
        await expect(treasurer).toHaveURL(new RegExp(`/meters/${fixture.meterId}$`));
        await expect(main(treasurer).getByText(fixture.serial).first()).toBeVisible();
        for (const action of ['Edit', 'Remove', 'Delete'])
          await expect(main(treasurer).getByRole('button', { name: action, exact: true })).toHaveCount(0);
      } finally {
        await treasurer.context().close();
      }
    } finally {
      await cleanup(page, fixture ? [fixture] : []);
    }
  });

  test('the readings round fits a phone without horizontal scroll', async ({ browser }) => {
    const electrician = await signedInPage(browser, 'electrician@demo.local', { width: 375, height: 800 });
    try {
      await electrician.goto('/readings');
      await expect(electrician.getByRole('heading', { level: 1, name: 'Readings' })).toBeVisible();
      await expect(
        main(electrician).getByRole('list', { name: 'Meters of the round' }).getByRole('listitem').first(),
      ).toBeVisible();
      const overflow = await electrician.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, 'horizontal overflow in px').toBeLessThanOrEqual(0);
    } finally {
      await electrician.context().close();
    }
  });
});
