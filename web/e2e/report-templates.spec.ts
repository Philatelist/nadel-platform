// @layer: e2e
// @spec: 013-reports
//
// Report templates: the chairman sees the empty tax report slot, uploads a small template, generates a file for a
// period and reads it back; a template with an unknown placeholder shows the warnings notice and the Warnings sheet.
// Uploaded templates cannot be deleted, so they are archived at the end and carry a name that is unique per run.
import { expect, test, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, expectCreated, kyivDay } from './support/api';
import { loginAs } from './support/login';
import { readReportWorkbook } from './support/reportFile';
import { xlsx } from './support/xlsx';

const t = messages('en');
const s = t.reportTemplates;
const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

interface TemplateEntry {
  id?: string;
  slot?: string;
  name?: string;
  status: string;
}

const listTemplates = async (page: Page): Promise<TemplateEntry[]> =>
  expectCreated<TemplateEntry[]>(await api(page, 'GET', '/report-templates'), 200);

/** Archives the active tax template and every template of this run; best effort. */
async function cleanUp(page: Page, runName: string): Promise<void> {
  const entries = await listTemplates(page).catch(() => [] as TemplateEntry[]);
  for (const entry of entries) {
    if (entry.id === undefined || entry.status !== 'ACTIVE') continue;
    if (entry.slot === 'TAX' || entry.name?.startsWith(runName))
      await api(page, 'POST', `/report-templates/${entry.id}/archive`).catch(() => undefined);
  }
}

const sample = (rows: string[][]) => xlsx([{ name: 'Sheet1', rows }]);

test.describe('Report templates', () => {
  test('tax slot: upload, generate and read back; unknown placeholder warns', async ({ page }) => {
    test.setTimeout(120_000);
    const runName = `E2E tpl ${Date.now()}`;
    const year = kyivDay(0).slice(0, 4);
    const period = { from: `${year}-01-01`, to: `${year}-12-31` };

    await loginAs(page, 'chairman@demo.local');
    try {
      // a tax template left by an earlier failed run would hide the empty slot
      await cleanUp(page, runName);

      await page.goto('/reports');
      await page.getByRole('link', { name: s.cardTitle, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: s.title })).toBeVisible();
      const slot = page.getByRole('region', { name: s.tax.title });
      await expect(slot.getByText(s.tax.notAdded)).toBeVisible();

      // upload the tax template through the screen
      await slot.getByRole('button', { name: s.tax.upload }).click();
      await slot.locator('input[type=file]').setInputFiles({
        name: 'e2e-tax.xlsx',
        mimeType: XLSX_TYPE,
        buffer: sample([
          ['Land tax statement ${cooperative.name}', '${period.year}'],
          ['${rows.plots.number}', '${rows.plots.landTax.expected}'],
        ]),
      });
      await slot.getByRole('button', { name: s.form.submit, exact: true }).click();
      await expect(page.getByText(s.uploaded)).toBeVisible();
      const taxList = page.getByRole('list', { name: s.tax.title });
      await expect(taxList.getByText(s.status.ACTIVE)).toBeVisible();

      // generate for the whole current year and read the file back
      await taxList.getByRole('button', { name: s.generate.button }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel(s.generate.from, { exact: true }).fill(period.from);
      await dialog.getByLabel(s.generate.to, { exact: true }).fill(period.to);
      const generated = page.waitForEvent('download');
      await dialog.getByRole('button', { name: s.generate.submit }).click();
      const file = await generated;
      expect(file.suggestedFilename()).toBe(`Tax-report_${period.from}_${period.to}.xlsx`);
      const workbook = await readReportWorkbook(await file.path());
      expect(
        workbook.cells.filter((c) => c.includes('${')),
        'placeholders left',
      ).toEqual([]);
      expect(workbook.cells, 'the period year replaced').toContain(year);
      expect(
        workbook.cells.some(
          (c) => c.startsWith('Land tax statement ') && c.length > 'Land tax statement '.length,
        ),
        'the cooperative name replaced',
      ).toBe(true);
      await dialog.getByRole('button', { name: s.generate.close }).click();

      // a second template with an unknown placeholder
      const warnName = `${runName} warn`;
      await page.getByRole('button', { name: s.add }).click();
      await page.getByLabel(s.form.name, { exact: true }).fill(warnName);
      await page.locator('input[type=file]').setInputFiles({
        name: 'e2e-warn.xlsx',
        mimeType: XLSX_TYPE,
        buffer: sample([['${nonsense.field}', '${period.year}']]),
      });
      await page.getByRole('button', { name: s.form.submit, exact: true }).click();
      await expect(page.getByText(s.uploaded)).toBeVisible();
      await page
        .getByRole('list', { name: s.listTitle })
        .getByRole('listitem')
        .filter({ hasText: warnName })
        .getByRole('button', { name: s.generate.button })
        .click();
      await page.getByRole('dialog').getByLabel(s.generate.from, { exact: true }).fill(period.from);
      await page.getByRole('dialog').getByLabel(s.generate.to, { exact: true }).fill(period.to);
      const warned = page.waitForEvent('download');
      await page.getByRole('dialog').getByRole('button', { name: s.generate.submit }).click();
      const warnedFile = await warned;
      await expect(page.getByRole('dialog').getByText(/Warnings in the file: [1-9]/)).toBeVisible();
      await expect(page.getByRole('dialog').getByText(s.warnings.UNKNOWN_PLACEHOLDER)).toBeVisible();
      const warnedBook = await readReportWorkbook(await warnedFile.path());
      expect(warnedBook.sheetNames).toContain(s.warningsSheet.name);
      expect(warnedBook.cells, 'the unknown placeholder is left as written').toContain('${nonsense.field}');
    } finally {
      await cleanUp(page, runName);
    }
  });
});
