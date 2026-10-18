// @layer: e2e
// @spec: 006-plots-registry
// @regression
import { expect, test, type Page } from '@playwright/test';
import { loginAs } from './support/login';

const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });
const rows = (page: Page) => page.getByRole('main').locator('tbody tr');
const field = (page: Page, label: string) => page.getByRole('main').getByLabel(label, { exact: true });

/** Signed-in access token of the current page, for the one call the UI deliberately cannot make. */
async function apiDelete(page: Page, id: string): Promise<{ status: number; body: string }> {
  return page.evaluate(async (plotId) => {
    const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
    const token = key
      ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
      : {};
    const response = await fetch(`/api/v1/plots/${plotId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token.access_token ?? ''}` },
    });
    return { status: response.status, body: await response.text() };
  }, id);
}

test.describe('plots registry', () => {
  test('treasurer lands on the plots list, searches, opens a full read-only card', async ({ page }) => {
    await loginAs(page, 'treasurer@demo.local');

    await expect(page).toHaveURL(/\/plots$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Plots' })).toBeVisible();

    await field(page, 'Search by number, street or cadastral number').fill('12');
    await expect(rows(page).first()).toContainText('12');
    await expect(rows(page).nth(1)).toContainText('12а');
    // "12" is listed before "12а" (natural order)
    const numbers = await rows(page).locator('td:first-child').allTextContents();
    expect(numbers.indexOf('12')).toBeGreaterThanOrEqual(0);
    expect(numbers.indexOf('12')).toBeLessThan(numbers.indexOf('12а'));

    // plot 21 has a cadastral number and a building
    await field(page, 'Search by number, street or cadastral number').fill('21');
    await rows(page)
      .filter({ hasText: 'Orchard St' })
      .first()
      .getByRole('link', { name: '21', exact: true })
      .click();
    const main = page.getByRole('main');
    await expect(page.getByRole('heading', { level: 1, name: 'Plot 21' })).toBeVisible();
    await expect(main).toContainText('10 sotkas (1000 m²)');
    await expect(main).toContainText('4810136300:02:002:0021');
    await expect(main.getByText('Building', { exact: true })).toBeVisible();
    await expect(main.getByText('Yes', { exact: true })).toBeVisible();
    await expect(main.getByText('Owners', { exact: true })).toBeVisible();

    for (const name of ['Add plot', 'Edit', 'Archive', 'Delete', 'Restore']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
  });

  test('member opens Plots from the menu and sees the restricted view only', async ({ page }) => {
    await loginAs(page, 'member@demo.local');

    await menuLink(page, 'Plots').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Plots' })).toBeVisible();
    await expect(rows(page).first()).toBeVisible();

    const headers = await page.getByRole('main').locator('thead th').allTextContents();
    expect(headers.join('|')).not.toMatch(/Area|Cadastral/);
    expect(headers).toEqual(expect.arrayContaining(['Number', 'Street', 'Kind', 'Status']));
    await expect(page.getByRole('main').getByLabel('Show archived')).toHaveCount(0);

    await rows(page).first().getByRole('link').first().click();
    const main = page.getByRole('main');
    await expect(page.getByRole('heading', { level: 1, name: /^Plot / })).toBeVisible();
    await expect(main.getByText('Number', { exact: true })).toBeVisible();
    for (const hidden of ['Area', 'Cadastral number', 'Building', 'Notes', 'Owners']) {
      await expect(main.getByText(hidden, { exact: true })).toHaveCount(0);
    }
    await expect(page.getByRole('button', { name: /^(Edit|Archive|Delete)$/ })).toHaveCount(0);
  });

  test('chairman creates, edits, archives, restores and deletes a plot; the audit log shows it', async ({
    page,
  }) => {
    const number = `E2E-${Date.now()}`;
    let plotId: string | undefined;
    await loginAs(page, 'chairman@demo.local');
    const main = page.getByRole('main');
    try {
      await expect(page).toHaveURL(/\/plots$/);

      // required fields: nothing is saved
      await page.getByRole('button', { name: 'Add plot' }).click();
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'New plot' })).toBeVisible();
      await expect(main.getByText(/required/i).first()).toBeVisible();

      // duplicate number of an existing active plot: inline error under the number field
      await field(page, 'Number').fill(' 12 ');
      await field(page, 'Area').fill('6');
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(main.getByText('Another active plot already has this number.')).toBeVisible();
      await expect(page.getByRole('heading', { level: 1, name: 'New plot' })).toBeVisible();

      // create with an area of 6,5 sotkas
      await field(page, 'Number').fill(number);
      await field(page, 'Street').fill('E2E Lane');
      await field(page, 'Area').fill('6,5');
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
      plotId = /\/plots\/([0-9a-f-]{36})$/.exec(page.url())?.[1];
      expect(plotId).toBeTruthy();
      await expect(main).toContainText('6.5 sotkas (650 m²)');
      await expect(main).toContainText('E2E Lane');

      // edit the street
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
      await field(page, 'Street').fill('E2E Avenue');
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
      await expect(main).toContainText('E2E Avenue');
      await expect(main).not.toContainText('E2E Lane');

      // archive: gone from the default list, visible with "show archived"
      await page.getByRole('button', { name: 'Archive', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Restore', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
      await page.getByRole('link', { name: 'Back to plots' }).click();
      const search = field(page, 'Search by number, street or cadastral number');
      await search.fill(number);
      await expect(main.getByText('Nothing found')).toBeVisible();
      await main.getByLabel('Show archived').click(); // URL-driven checkbox: assert instead of check()
      await expect(main.getByLabel('Show archived')).toBeChecked();
      await expect(rows(page)).toHaveCount(1);
      await expect(rows(page).first()).toContainText(number);
      await expect(rows(page).first()).toContainText('Archived');

      // deleting an archived plot is refused by the server (the UI offers no Delete button)
      const refused = await apiDelete(page, plotId ?? '');
      expect(refused.status).toBeGreaterThanOrEqual(400);
      expect(refused.status).toBeLessThan(500);
      expect(refused.body).toContain('PLOT_ARCHIVED');

      // restore
      await rows(page).first().getByRole('link', { name: number }).click();
      await page.getByRole('button', { name: 'Restore', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Archive', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Delete', exact: true })).toBeVisible();

      // delete: cancel changes nothing, confirm removes the plot
      await page.getByRole('button', { name: 'Delete', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('Delete the plot?')).toBeVisible();
      await dialog.getByRole('button', { name: 'Cancel' }).click();
      await expect(page.getByRole('heading', { level: 1, name: `Plot ${number}` })).toBeVisible();
      await page.getByRole('button', { name: 'Delete', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
      await expect(page).toHaveURL(/\/plots$/);
      plotId = undefined;
      await field(page, 'Search by number, street or cadastral number').fill(number);
      await expect(main.getByText('Nothing found')).toBeVisible();
      await main.getByLabel('Show archived').click(); // URL-driven checkbox: assert instead of check()
      await expect(main.getByLabel('Show archived')).toBeChecked();
      await expect(main.getByText('Nothing found')).toBeVisible();

      // audit log filtered to plots shows the entries for this plot (written by the outbox: reload until they appear)
      await menuLink(page, 'Audit log').click();
      await page.getByLabel('Kind of record').selectOption('PLOT');
      for (const action of [
        'Plot created',
        'Plot updated',
        'Plot archived',
        'Plot restored',
        'Plot deleted',
      ]) {
        await expect(async () => {
          await page.reload();
          await expect(
            page.getByRole('row').filter({ hasText: number }).filter({ hasText: action }).first(),
          ).toBeVisible({ timeout: 1500 });
        }).toPass({ timeout: 15_000 });
      }
      await expect(
        page.getByRole('row').filter({ hasText: number }).filter({ hasText: 'Plot updated' }).first(),
      ).toContainText('E2E Lane → E2E Avenue');
    } finally {
      if (plotId) {
        // leftover from a failed run: restore if needed, then delete, so the demo data stays clean
        await page.goto(`/plots/${plotId}`);
        const restore = page.getByRole('button', { name: 'Restore', exact: true });
        const remove = page.getByRole('button', { name: 'Delete', exact: true });
        await restore.or(remove).waitFor({ timeout: 10_000 });
        if (await restore.isVisible()) {
          await restore.click();
          await remove.waitFor({ timeout: 10_000 });
        }
        await remove.click();
        await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
        await expect(page).toHaveURL(/\/plots$/);
      }
    }
  });
});
