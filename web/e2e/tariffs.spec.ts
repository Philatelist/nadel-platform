// @layer: e2e
// @spec: 009-charge-types-and-tariffs
// @regression
// Charge types and tariffs in a browser: the chairman creates a custom charge type and a per-sotka tariff and sees the
// expected amount on a plot card; the treasurer only reads; a member has no menu item, no page and sees only own plots.
// The charge type and the plot are CREATED per run with a unique stamp; both are archived in `finally` (archiving the
// charge type removes its tariffs from every plot). Demo tariffs are never counted or changed.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { api, archiveLeftovers, expectCreated, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

test.describe('tariffs', () => {
  test('chairman creates a charge type and a per-sotka tariff, the treasurer and a member see only what they may @smoke', async ({
    page,
    browser,
  }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E type ${stamp}`;
    const tariffName = `E2E tariff ${stamp}`;
    let chargeTypeId: string | undefined;
    let plot: CreatedPlot | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      const created = expectCreated<{ id: string }>(
        await api(page, 'POST', '/plots', {
          number: `E2E-TAR-${stamp}`,
          kind: 'PLOT',
          areaM2: 600,
          street: 'E2E Lane',
        }),
      );
      plot = { id: created.id, number: `E2E-TAR-${stamp}` };

      // 1. a custom charge type, then a tariff for it
      await menuLink(page, 'Tariffs').click();
      await main(page).getByRole('link', { name: 'Charge types', exact: true }).click();
      await main(page).getByRole('button', { name: 'Add charge type', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Name', { exact: true }).fill(typeName);
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(main(page).getByText(typeName, { exact: true })).toBeVisible();
      const types = expectCreated<{ id: string; name: string }[]>(
        await api(page, 'GET', '/charge-types'),
        200,
      );
      chargeTypeId = types.find((x) => x.name === typeName)?.id;
      expect(chargeTypeId).toBeTruthy();

      await main(page).getByRole('link', { name: 'Back to tariffs', exact: true }).click();
      await main(page).getByRole('link', { name: 'New tariff', exact: true }).click();
      await main(page).getByLabel('Charge type', { exact: true }).selectOption({ label: typeName });
      await main(page).getByLabel('Tariff name', { exact: true }).fill(tariffName);
      await main(page)
        .getByLabel('Valid from', { exact: true })
        .fill(kyivDay(0).slice(0, 8) + '01');
      await main(page).getByLabel('Basis', { exact: true }).selectOption({ label: 'Per sotka' });
      await main(page).getByLabel('Frequency', { exact: true }).selectOption({ label: 'Yearly' });
      await main(page).getByLabel(/^Rate/).fill('50');
      await main(page).getByRole('button', { name: 'Create tariff', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: tariffName })).toBeVisible();

      // the tariffs list shows the rate with its significant decimals, at least two
      await menuLink(page, 'Tariffs').click();
      const row = main(page).getByRole('listitem').filter({ hasText: tariffName }).last();
      await expect(row).toContainText('₴50.00');
      await expect(row).toContainText('Per sotka');
      await expect(row).toContainText('Yearly');

      // the plot card: the expected amount for the current month, 600 m² = 6 sotka, 50 * 6 / 12
      await page.goto(`/plots/${plot.id}`);
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${plot.number}` })).toBeVisible();
      const item = main(page).getByRole('listitem').filter({ hasText: tariffName });
      await expect(item).toContainText(typeName);
      await expect(item).toContainText(/25[.,]00/); // the amount keeps the formatMoney style of the cooperative

      // 2. the treasurer reads the list but manages nothing
      const treasurer = await signedInPage(browser, 'treasurer@demo.local');
      try {
        await treasurer.goto('/tariffs');
        await expect(treasurer.getByRole('heading', { level: 1, name: 'Tariffs' })).toBeVisible();
        const listed = main(treasurer).getByRole('link', { name: tariffName, exact: true });
        await expect(listed).toBeVisible();
        await expect(main(treasurer).getByRole('link', { name: 'New tariff', exact: true })).toHaveCount(0);
        await expect(main(treasurer).getByRole('link', { name: 'Charge types', exact: true })).toHaveCount(0);
        await listed.click();
        await expect(treasurer.getByRole('heading', { level: 1, name: tariffName })).toBeVisible();
        await expect(main(treasurer).getByText('New version', { exact: true })).toHaveCount(0);
        await expect(main(treasurer).getByRole('button', { name: 'Add version', exact: true })).toHaveCount(
          0,
        );
        await expect(main(treasurer).getByRole('button', { name: 'Change', exact: true })).toHaveCount(0);
      } finally {
        await treasurer.context().close();
      }

      // 3. a member: no menu item, no page, own plots only, and a plot of someone else is 404
      const member = await signedInPage(browser, 'member@demo.local');
      try {
        await expect(menuLink(member, 'Tariffs')).toHaveCount(0);
        await member.goto('/tariffs');
        await expect(member.getByText("You don't have access to this page")).toBeVisible();
        await member.goto('/account');
        const mine = member.getByRole('region', { name: 'What I will be charged' });
        await expect(mine).toBeVisible();
        await expect(mine.getByRole('heading', { level: 2, name: /^Plot (7|15)$/ })).toHaveCount(2);
        await expect(mine).not.toContainText(plot.number);
        expect((await api(member, 'GET', `/plots/${plot.id}/tariffs`)).status).toBe(404);
      } finally {
        await member.context().close();
      }
    } finally {
      if (chargeTypeId)
        await api(page, 'POST', `/charge-types/${chargeTypeId}/archive`).catch(() => undefined);
      if (plot) await archiveLeftovers(page, [plot], [], kyivDay(0));
    }
  });
});
