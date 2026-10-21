// @layer: e2e
// @spec: 009-charge-types-and-tariffs
// @regression
// Correcting a tariff version in a browser: the chairman made a tariff with a wrong rate, sees the wrong expected
// amount on the plot card, edits the version in the version history and sees the corrected amount.
// The charge type and the plot are CREATED per run with a unique stamp and archived in `finally`; demo tariffs are
// never changed.
import { expect, test, type Page } from '@playwright/test';
import { api, archiveLeftovers, expectCreated, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';

const main = (page: Page) => page.getByRole('main');

test.describe('tariff version correction', () => {
  test('the chairman fixes a wrong rate and the plot card shows the corrected amount', async ({ page }) => {
    const stamp = Date.now().toString(36);
    const typeName = `E2E fix type ${stamp}`;
    const tariffName = `E2E fix tariff ${stamp}`;
    let chargeTypeId: string | undefined;
    let plot: CreatedPlot | undefined;
    await loginAs(page, 'chairman@demo.local');
    try {
      const createdPlot = expectCreated<{ id: string }>(
        await api(page, 'POST', '/plots', {
          number: `E2E-FIX-${stamp}`,
          kind: 'PLOT',
          areaM2: 600,
          street: 'E2E Lane',
        }),
      );
      plot = { id: createdPlot.id, number: `E2E-FIX-${stamp}` };
      chargeTypeId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/charge-types', { name: typeName }),
      ).id;
      const tariff = expectCreated<{ id: string }>(
        await api(page, 'POST', '/tariffs', {
          chargeTypeId,
          name: tariffName,
          firstVersion: {
            validFrom: kyivDay(0).slice(0, 8) + '01',
            basis: 'PER_AREA_SOTKA',
            frequency: 'YEARLY',
            rate: '60',
          },
        }),
      );

      // the wrong rate: 600 m² = 6 sotka, 60 * 6 / 12 = 30.00
      await page.goto(`/plots/${plot.id}`);
      const item = main(page).getByRole('listitem').filter({ hasText: tariffName });
      await expect(item).toContainText(/30[.,]00/);

      // the fix in the version history: same form, prefilled
      await page.goto(`/tariffs/${tariff.id}`);
      const version = main(page).getByRole('list').getByRole('listitem').first();
      await version.getByRole('button', { name: 'Edit', exact: true }).click();
      const form = main(page).getByRole('form', { name: 'Edit version' });
      await expect(form.getByLabel(/^Rate/)).toHaveValue(/^60/);
      await form.getByLabel(/^Rate/).fill('50');
      await form.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(main(page).getByText('Version corrected', { exact: true })).toBeVisible();
      await expect(version).toContainText('₴50.00');

      // the plot card shows the corrected amount, 50 * 6 / 12 = 25.00
      await page.goto(`/plots/${plot.id}`);
      await expect(item).toContainText(/25[.,]00/);
      await expect(item).not.toContainText(/30[.,]00/);
    } finally {
      if (chargeTypeId)
        await api(page, 'POST', `/charge-types/${chargeTypeId}/archive`).catch(() => undefined);
      if (plot) await archiveLeftovers(page, [plot], [], kyivDay(0));
    }
  });
});
