// @layer: e2e
// @spec: 011-ledger-charges-period-closing
// @regression
// Acceptance specs of the whole feature "Ledger, charges and period closing" (functional-spec.md of 011) for the two
// user flows charges.spec.ts does not walk in a browser: the water connection on the plot card (§2.8: connect, the
// refusal to connect twice, disconnect with the history kept) and the red status threshold in the cooperative settings
// (§2.10). Everything else of the feature is in charges.spec.ts and zz-closing.spec.ts (screens) and in the backend
// acceptance tests (rules). The plot is created by the spec (unique number) and archived afterwards; the threshold is
// put back in `finally`.
import { expect, test, type Page } from '@playwright/test';
import { api, archiveLeftovers, createPlot, expectCreated, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');

test.describe('ledger acceptance flows', () => {
  test('§2.8 the chairman connects a plot to water, cannot connect it twice and disconnects it; the history stays', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const plot: CreatedPlot = await createPlot(page, `E2E-W-${Date.now().toString(36).toUpperCase()}`);
    try {
      await page.goto(`/plots/${plot.id}`);
      const panel = main(page).locator('section', {
        has: page.getByRole('heading', { name: 'Water', exact: true }),
      });
      await expect(panel.getByText('Not connected to water')).toBeVisible();
      await expect(panel.getByText('The plot has never been connected to water')).toBeVisible();

      await panel.getByRole('button', { name: 'Connect', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Connect the plot to water' });
      await dialog.getByLabel('Connected from', { exact: true }).fill(kyivDay(-10));
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(panel.getByText(/Connected to water: Cooperative water supply from /)).toBeVisible();
      // the lasting connection offers "Disconnect" and no second "Connect"
      await expect(panel.getByRole('button', { name: 'Connect', exact: true })).toHaveCount(0);

      // connecting again (through the API: the screen does not offer it) is refused with the code of the message
      const again = await api(page, 'POST', `/plots/${plot.id}/water-connections`, { from: kyivDay(0) });
      expect(again.status).toBe(409);
      expect(JSON.parse(again.body)).toMatchObject({ code: 'WATER_ALREADY_CONNECTED' });

      await panel.getByRole('button', { name: 'Disconnect', exact: true }).click();
      const ending = page.getByRole('dialog', { name: 'Disconnect the plot from water' });
      // the last connected day is yesterday, so the plot is not connected today
      await ending.getByLabel('Last connected day', { exact: true }).fill(kyivDay(-1));
      await ending.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(panel.getByText('Not connected to water')).toBeVisible();
      // the connection stays in the history with its end date
      await expect(panel.getByRole('listitem')).toHaveCount(1);
      await expect(panel.getByRole('listitem').first()).toContainText('Cooperative water supply');
      await expect(panel.getByRole('listitem').first()).toContainText(' – ');
      await expect(panel.getByRole('button', { name: 'Connect', exact: true })).toBeVisible();
    } finally {
      await archiveLeftovers(page, [plot], [], kyivDay(0));
    }
  });

  test('§2.10 the chairman changes the red amount, the settings keep the new value and it is put back', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const before = expectCreated<{ redAmount: string; redDays: number }>(
      await api(page, 'GET', '/settings'),
      200,
    );
    const amountField = () => main(page).getByLabel('Red from the debt amount, ₴', { exact: true });
    try {
      await page.goto('/settings');
      await expect(page.getByRole('heading', { level: 1, name: 'Cooperative settings' })).toBeVisible();
      await expect(amountField()).toHaveValue(before.redAmount);

      const next = before.redAmount === '2000.00' ? '2500.00' : '2000.00';
      await amountField().fill(next);
      await main(page).getByRole('button', { name: 'Save the threshold', exact: true }).click();
      await expect(main(page).getByText('Settings saved.')).toBeVisible();

      await page.reload();
      await expect(amountField()).toHaveValue(next);
      const saved = expectCreated<{ redAmount: string }>(await api(page, 'GET', '/settings'), 200);
      expect(saved.redAmount).toBe(next);

      // an amount that is not money is refused on the form and nothing is saved
      await amountField().fill('abc');
      await main(page).getByRole('button', { name: 'Save the threshold', exact: true }).click();
      await expect(main(page).getByText('Enter an amount such as 1500.00')).toBeVisible();
      expect(expectCreated<{ redAmount: string }>(await api(page, 'GET', '/settings'), 200).redAmount).toBe(
        next,
      );
    } finally {
      await api(page, 'PUT', '/settings', { redAmount: before.redAmount, redDays: before.redDays }).catch(
        () => undefined,
      );
    }
  });
});
