// @layer: e2e
// @spec: 008-excel-import
// @regression
// Excel import in a browser: the template download, upload and preview, commit, an unchanged re-import, an invalid
// file, the size limit, the history and the people who may not import. Workbooks are generated at test time with
// unique plot numbers, member keys and emails per run; plots and members the import creates are archived through the
// API in `finally` (their ownership is ended first), so the demo counts of the other specs stay as they were.
import { stat } from 'node:fs/promises';
import { expect, test, type Page } from '@playwright/test';
import { api, archiveLeftovers, kyivDay, type CreatedMember, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';
import { xlsx } from './support/xlsx';

const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const main = (page: Page) => page.getByRole('main');
const menuLink = (page: Page, name: string) =>
  page.locator('#app-menu nav').getByRole('link', { name, exact: true });

const PLOT_HEADER = ['number', 'street', 'kind', 'area_m2'];
const MEMBER_HEADER = ['external_id', 'last_name', 'first_name', 'phones', 'email'];
const OWNERSHIP_HEADER = ['plot_number', 'member_external_id', 'share', 'valid_from'];

interface Run {
  stamp: string;
  plots: string[];
  lastNames: string[];
}

function newRun(): Run {
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
  return {
    stamp,
    plots: [1, 2, 3].map((n) => `E2E-IMP-${stamp}-${n}`),
    lastNames: ['A', 'B', 'C'].map((c) => `E2eImp${stamp}${c}`),
  };
}

/** Three plots, three members; plot 2 has two co-owners with 1/2 each. */
function validWorkbook(run: Run): Buffer {
  const [p1, p2, p3] = run.plots as [string, string, string];
  return xlsx([
    {
      name: 'Plots',
      rows: [
        PLOT_HEADER,
        [p1, 'E2E Lane', 'PLOT', '400'],
        [p2, 'E2E Lane', 'PLOT', '600'],
        [p3, 'E2E Lane', 'PLOT', '500'],
      ],
    },
    {
      name: 'Members',
      rows: [
        MEMBER_HEADER,
        [`k-${run.stamp}-a`, run.lastNames[0], 'Anna', '+380501110001', `a.${run.stamp}@e2e.example`],
        [`k-${run.stamp}-b`, run.lastNames[1], 'Borys', '+380501110002', `b.${run.stamp}@e2e.example`],
        [`k-${run.stamp}-c`, run.lastNames[2], 'Clara', '+380501110003', `c.${run.stamp}@e2e.example`],
      ],
    },
    {
      name: 'Ownership',
      rows: [
        OWNERSHIP_HEADER,
        [p1, `k-${run.stamp}-a`, '1', '01.06.2015'],
        [p2, `k-${run.stamp}-a`, '1/2', '01.06.2015'],
        [p2, `k-${run.stamp}-b`, '1/2', '01.06.2015'],
        [p3, `k-${run.stamp}-c`, '1', '01.06.2015'],
      ],
    },
  ]);
}

/** A plot with a malformed cadastral number and an owner whose member key is not in the file. */
function invalidWorkbook(run: Run): Buffer {
  return xlsx([
    {
      name: 'Plots',
      rows: [
        [...PLOT_HEADER, 'cadastral_number'],
        [`E2E-BAD-${run.stamp}`, 'E2E Lane', 'PLOT', '400', 'not-a-cadastral-number'],
      ],
    },
    {
      name: 'Members',
      rows: [
        MEMBER_HEADER,
        [`k-${run.stamp}-x`, run.lastNames[0], 'Xenia', '+380501110009', `x.${run.stamp}@e2e.example`],
      ],
    },
    { name: 'Ownership', rows: [OWNERSHIP_HEADER, [`E2E-BAD-${run.stamp}`, 'nobody', '1', '01.06.2015']] },
  ]);
}

async function chooseFile(page: Page, name: string, buffer: Buffer): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({ name, mimeType: XLSX_TYPE, buffer });
}

async function checkFile(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Check file' }).click();
  await expect(main(page).getByRole('heading', { name: 'Check result' })).toBeVisible();
}

/** The counts table row of an entity as [label, created, updated, unchanged]. */
function countsRow(page: Page, caption: string, label: string) {
  return main(page)
    .getByRole('table', { name: caption })
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: label, exact: true }) });
}

/** Finds the records the run created through the API so that they can be archived. */
async function findLeftovers(
  page: Page,
  run: Run,
): Promise<{ plots: CreatedPlot[]; members: CreatedMember[] }> {
  const plots: CreatedPlot[] = [];
  const members: CreatedMember[] = [];
  const foundPlots = await api(page, 'GET', `/plots?q=${encodeURIComponent(`E2E-IMP-${run.stamp}`)}&size=50`);
  if (foundPlots.status === 200)
    for (const p of (JSON.parse(foundPlots.body) as { items: { id: string; number: string }[] }).items)
      plots.push({ id: p.id, number: p.number });
  for (const lastName of run.lastNames) {
    const found = await api(page, 'GET', `/members?q=${encodeURIComponent(lastName)}&size=50`);
    if (found.status === 200)
      for (const m of (JSON.parse(found.body) as { items: { id: string }[] }).items)
        members.push({ id: m.id, name: lastName, lastName });
  }
  return { plots, members };
}

test.describe('excel import', () => {
  test('chairman downloads the template', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await menuLink(page, 'Import').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Import' })).toBeVisible();

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Download template' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('nadel-import-template.xlsx');
    const path = await download.path();
    const { size } = await stat(path);
    expect(size).toBeGreaterThan(0);
  });

  test('chairman checks, imports and re-imports a workbook; an invalid file and the history', async ({
    page,
  }) => {
    const run = newRun();
    const validName = `e2e-imp-${run.stamp}-valid.xlsx`;
    const invalidName = `e2e-imp-${run.stamp}-invalid.xlsx`;
    await loginAs(page, 'chairman@demo.local');
    try {
      await page.goto('/imports');
      await expect(page.getByRole('heading', { level: 1, name: 'Import' })).toBeVisible();

      // preview: nothing is saved, the counts are shown, Import is enabled
      await chooseFile(page, validName, validWorkbook(run));
      await checkFile(page);
      await expect(main(page).getByText('The file has no problems. You can import it.')).toBeVisible();
      await expect(countsRow(page, 'What the import will do', 'Plots').getByRole('cell')).toHaveText([
        'Plots',
        '3',
        '0',
        '0',
      ]);
      await expect(countsRow(page, 'What the import will do', 'Members').getByRole('cell')).toHaveText([
        'Members',
        '3',
        '0',
        '0',
      ]);
      const found = await api(page, 'GET', `/plots?q=${encodeURIComponent(`E2E-IMP-${run.stamp}`)}`);
      expect((JSON.parse(found.body) as { items: unknown[] }).items).toHaveLength(0);

      // commit
      await main(page).getByRole('button', { name: 'Import', exact: true }).click();
      await expect(main(page).getByRole('heading', { name: 'Import complete' })).toBeVisible();
      await expect(countsRow(page, 'What was saved', 'Plots').getByRole('cell')).toHaveText([
        'Plots',
        '3',
        '0',
        '0',
      ]);

      // the plots are in the list with their owners
      await main(page).getByRole('link', { name: 'Go to plots' }).click();
      await main(page)
        .getByLabel('Search by number, street or cadastral number', { exact: true })
        .fill(`E2E-IMP-${run.stamp}`);
      const rows = main(page).locator('tbody tr');
      await expect(rows).toHaveCount(3);
      await expect(rows.filter({ hasText: run.plots[0]! })).toContainText(`${run.lastNames[0]} Anna`);
      const shared = rows.filter({ hasText: run.plots[1]! });
      await expect(shared).toContainText(run.lastNames[0]!);
      await expect(shared).toContainText(run.lastNames[1]!);
      await expect(rows.filter({ hasText: run.plots[2]! })).toContainText(`${run.lastNames[2]} Clara`);

      // the same workbook again: everything is unchanged and Import is disabled
      await menuLink(page, 'Import').click();
      await chooseFile(page, validName, validWorkbook(run));
      await checkFile(page);
      await expect(main(page).getByText('Nothing new or changed in the file')).toBeVisible();
      await expect(countsRow(page, 'What the import will do', 'Plots').getByRole('cell')).toHaveText([
        'Plots',
        '0',
        '0',
        '3',
      ]);
      await expect(countsRow(page, 'What the import will do', 'Members').getByRole('cell')).toHaveText([
        'Members',
        '0',
        '0',
        '3',
      ]);
      await expect(main(page).getByRole('button', { name: 'Import', exact: true })).toBeDisabled();

      // an invalid workbook: the problems are listed, Import is disabled
      await chooseFile(page, invalidName, invalidWorkbook(run));
      await checkFile(page);
      await expect(main(page).getByText(/^Problems found: [1-9]/)).toBeVisible();
      const problems = main(page).getByRole('table', { name: 'Problems in the file' });
      await expect(problems).toContainText('The cadastral number must look like');
      await expect(problems).toContainText('The Members sheet has no row with this member key.');
      await expect(main(page).getByRole('button', { name: 'Import', exact: true })).toBeDisabled();

      // the history lists both uploads
      const history = main(page).getByRole('table', { name: 'Import history' });
      const committed = history
        .getByRole('row')
        .filter({ hasText: validName })
        .filter({ hasText: /Imported/ });
      await expect(committed).toHaveCount(1);
      await expect(committed).toContainText('Plots: 3 new, 0 updated');
      await expect(history.getByRole('row').filter({ hasText: invalidName })).toContainText('Has errors');
    } finally {
      const { plots, members } = await findLeftovers(page, run);
      await archiveLeftovers(page, plots, members, kyivDay());
    }
  });

  test('a file over 10 MB is refused before the upload', async ({ page }) => {
    await loginAs(page, 'chairman@demo.local');
    await page.goto('/imports');
    await chooseFile(page, 'too-big.xlsx', Buffer.alloc(10 * 1024 * 1024 + 1));
    await expect(main(page).getByText('The file is too large — the maximum is 10 MB')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Check file' })).toBeDisabled();
  });

  for (const email of ['treasurer@demo.local', 'member@demo.local']) {
    test(`${email} has no Import item and no access to /imports`, async ({ page }) => {
      await loginAs(page, email);
      await expect(menuLink(page, 'Import')).toHaveCount(0);
      await page.goto('/imports');
      await expect(page.getByText("You don't have access to this page")).toBeVisible();
      await expect(page.getByRole('button', { name: 'Download template' })).toHaveCount(0);
    });
  }
});
