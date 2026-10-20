// @layer: e2e
// @spec: 008-excel-import
// @regression
// Acceptance specs of the whole feature "Excel import" (functional-spec.md of 008) that imports.spec.ts does not cover,
// read in the Ukrainian interface: Ukrainian headers in another order and case with an area in sotkas (§2.1 #3, #5),
// the refusal messages for a CSV and for more than 2,000 plots (§2.2 #4, #5), an update of the street found by the
// plot number (§2.4 #2) and the "Change owner" error for an owned plot (§2.4 #4). Criteria are named §2.x #n. Plots
// are created per run under unique numbers and archived through the API in `finally`; the demo data is only read.
import { expect, test, type Page } from '@playwright/test';
import { api, archiveLeftovers, kyivDay, type CreatedPlot } from './support/api';
import { loginAs } from './support/login';
import { xlsx } from './support/xlsx';

const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const main = (page: Page) => page.getByRole('main');

async function signInInUkrainian(page: Page): Promise<void> {
  await page.addInitScript(() => {
    try {
      window.localStorage.setItem('nadel.locale', 'uk');
    } catch {
      // storage unavailable: Ukrainian is the default anyway
    }
  });
  await loginAs(page, 'chairman@demo.local', { keepLocale: true });
}

async function chooseFile(page: Page, name: string, buffer: Buffer, mimeType = XLSX_TYPE): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({ name, mimeType, buffer });
}

async function checkFile(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Перевірити файл' }).click();
}

const countsRow = (page: Page, label: string) =>
  main(page)
    .getByRole('table', { name: 'Що зробить імпорт' })
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: label, exact: true }) });

test.describe('excel import: acceptance', () => {
  test('§2.1 #3, #5, §2.4 #2: Ukrainian headers in another order and case; 6 sotkas show as "6 соток (600 м²)"; a changed street updates the plot', async ({
    page,
  }) => {
    const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
    const number = `E2E-ACC-${stamp}`;
    const leftovers: CreatedPlot[] = [];
    const sheet = (street: string) => [
      {
        name: 'Plots',
        rows: [
          ['ПЛОЩА, СОТ.', 'вулиця', 'ТиП', 'нОмЕр'],
          ['6', street, 'PLOT', number],
        ],
      },
    ];
    await signInInUkrainian(page);
    try {
      await page.goto('/imports');
      await chooseFile(page, `acc-${stamp}.xlsx`, xlsx(sheet('Garden St')));
      await checkFile(page);
      await expect(main(page).getByText('У файлі немає проблем. Можна імпортувати.')).toBeVisible();
      await expect(countsRow(page, 'Ділянки').getByRole('cell')).toHaveText(['Ділянки', '1', '0', '0']);
      await main(page).getByRole('button', { name: 'Імпортувати', exact: true }).click();
      await expect(main(page).getByRole('heading', { name: 'Імпорт завершено' })).toBeVisible();

      const found = await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`);
      const items = (JSON.parse(found.body) as { items: { id: string; number: string }[] }).items;
      expect(items).toHaveLength(1);
      leftovers.push({ id: items[0]!.id, number });
      await page.goto(`/plots/${items[0]!.id}`);
      await expect(main(page).getByText(/6\s+соток\s+\(600\s+м²\)/)).toBeVisible();
      await expect(main(page).getByText('Garden St')).toBeVisible();

      // the same plot number with another street: one updated plot, and the plot shows the new street
      await page.goto('/imports');
      await chooseFile(page, `acc-${stamp}.xlsx`, xlsx(sheet('Orchard St')));
      await checkFile(page);
      await expect(countsRow(page, 'Ділянки').getByRole('cell')).toHaveText(['Ділянки', '0', '1', '0']);
      await main(page).getByRole('button', { name: 'Імпортувати', exact: true }).click();
      await expect(main(page).getByRole('heading', { name: 'Імпорт завершено' })).toBeVisible();
      await page.goto(`/plots/${items[0]!.id}`);
      await expect(main(page).getByText('Orchard St')).toBeVisible();
      await expect(main(page).getByText('Garden St')).toHaveCount(0);
    } finally {
      if (leftovers.length === 0) {
        // the lookup above may have failed after the commit: find the plot by its unique number
        const found = await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`).catch(() => undefined);
        try {
          const items =
            (JSON.parse(found?.body ?? '{}') as { items?: { id: string; number: string }[] }).items ?? [];
          leftovers.push(...items.filter((item) => item.number === number).map(({ id }) => ({ id, number })));
        } catch {
          // nothing to find: the import may not have created the plot
        }
      }
      await archiveLeftovers(page, leftovers, [], kyivDay());
    }
  });

  test('§2.2 #4, #5: a CSV and a file with more than 2,000 plots are refused with clear messages and no preview', async ({
    page,
  }) => {
    await signInInUkrainian(page);
    await page.goto('/imports');

    await chooseFile(page, 'plots.csv', Buffer.from('number,kind\n1,PLOT\n'), 'text/csv');
    await expect(main(page).getByText('Це не файл Excel. Оберіть файл з розширенням .xlsx.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Перевірити файл' })).toBeDisabled();

    const rows = [['number', 'kind', 'area_m2']];
    for (let i = 0; i < 2001; i++) rows.push([`E2E-LIM-${i}`, 'PLOT', '100']);
    await chooseFile(page, 'too-many.xlsx', xlsx([{ name: 'Plots', rows }]));
    await checkFile(page);
    await expect(
      main(page).getByText('У файлі забагато рядків: до 2 000 ділянок і 3 000 членів кооперативу.'),
    ).toBeVisible();
    await expect(main(page).getByRole('heading', { name: 'Результат перевірки' })).toHaveCount(0);
  });

  test('§2.4 #4: a different owner for an owned demo plot is an error that points to "Change owner"', async ({
    page,
  }) => {
    const stamp = Date.now().toString(36);
    await signInInUkrainian(page);
    await page.goto('/imports');
    await chooseFile(
      page,
      `owner-${stamp}.xlsx`,
      xlsx([
        {
          name: 'Members',
          rows: [
            ['external_id', 'last_name', 'first_name', 'email'],
            ['k1', `E2eBuyer${stamp}`, 'Buyer', `buyer.${stamp}@e2e.example`],
          ],
        },
        {
          name: 'Ownership',
          rows: [
            ['plot_number', 'member_external_id', 'share', 'valid_from'],
            ['9', 'k1', '1', '01.06.2015'],
          ],
        },
      ]),
    );
    await checkFile(page);

    const problems = main(page).getByRole('table', { name: 'Проблеми у файлі' });
    await expect(problems).toContainText(
      'Ділянка 9 уже має інших власників — змініть власника на картці ділянки.',
    );
    await expect(main(page).getByRole('button', { name: 'Імпортувати', exact: true })).toBeDisabled();
  });
});
