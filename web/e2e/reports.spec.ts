// @layer: e2e
// @spec: 013-reports
// @smoke
//
// Reports: the chairman builds the electricity-by-meter-tree report on screen and downloads it as an Excel file;
// a member and the electrician (no REPORTS_VIEW) get no menu item and the no-access view.
import { expect, test } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, expectCreated } from './support/api';
import { loginAs } from './support/login';
import { readReportWorkbook } from './support/reportFile';

const t = messages('en');
const menuLink = (page: import('@playwright/test').Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });

test.describe('Reports', () => {
  test('@smoke chairman opens the electricity report, sees the preview and downloads the Excel file', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    await menuLink(page, t.nav.reports).click();
    await expect(page.getByRole('heading', { level: 1, name: t.reports.title })).toBeVisible();
    await page.getByRole('link', { name: t.reports['electricity-tree'].title }).click();
    await expect(page).toHaveURL(/\/reports\/electricity-tree/);

    // a preview (a table) or the empty state; never the error state
    await expect(page.getByRole('table').first().or(page.getByText(t.reports.noRows))).toBeVisible();
    await expect(page.getByText(t.state.error)).toHaveCount(0);

    const me = expectCreated<{ tenant?: { name?: string } }>(await api(page, 'GET', '/me'), 200);
    const settings = expectCreated<{ officialName?: string }>(await api(page, 'GET', '/settings'), 200);

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: t.reports.download, exact: true }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(
      /^electricity-tree_\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.xlsx$/,
    );

    const path = await download.path();
    const workbook = await readReportWorkbook(path);
    expect(workbook.sheetNames.length, `sheets: ${workbook.sheetNames.join(', ')}`).toBe(2);
    const names = [me.tenant?.name, settings.officialName].filter((n): n is string => !!n);
    expect(names.length).toBeGreaterThan(0);
    expect(
      workbook.cells.some((cell) => names.some((name) => cell.includes(name))),
      `the header block names the cooperative (${names.join(' | ')})`,
    ).toBe(true);
  });

  for (const email of ['member@demo.local', 'electrician@demo.local']) {
    test(`${email} has no Reports menu item and /reports shows no access`, async ({ page }) => {
      await loginAs(page, email);
      await expect(menuLink(page, t.nav.reports)).toHaveCount(0);
      await page.goto('/reports');
      await expect(page.getByRole('heading', { level: 1, name: t.access.denied })).toBeVisible();
      await page.goto('/reports/electricity-tree');
      await expect(page.getByRole('heading', { level: 1, name: t.access.denied })).toBeVisible();
    });
  }
});
