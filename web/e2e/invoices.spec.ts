// @layer: e2e
// @spec: context/spec/016-invoices-and-documents
// @regression
//
// Invoices, debt certificates and cash receipts in a browser (requirement 016 section 15): (1) the treasurer builds the
// invoices of the demo closed month and downloads plot 9's PDF; (1a) the treasurer clicks «Invoice for today» on plot 9
// and gets its PDF, and a second click without a change returns the same version; (2) the chairman issues a
// certificate for plot 9,
// finds it in the register and annuls it; (3) the treasurer prints a cash receipt; (4) the member downloads their own
// invoice. In the dev/demo stack the backend has already built the invoices of the demo closed month and issued the
// certificate Д-{year}-1 at startup, so nothing here assumes an empty month or a certificate number: the build result
// is matched by shape ("unchanged" rows exist), and the certificate number is read from the screen and then found in
// the register. A PDF is asserted by the download event, the `%PDF-` magic bytes and its size.
import { readFile } from 'node:fs/promises';
import { expect, test, type Browser, type Download, type Page } from '@playwright/test';
import { format, messages } from '@nadel/shared';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  createPlot,
  demoMonths,
  expectCreated,
  kyivDay,
  monthLabel,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

const t = messages('en');
const main = (page: Page) => page.getByRole('main');

async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

/** The bytes of a finished download, after checking that it is a PDF of a sensible size. */
async function expectPdf(download: Download, namePattern: RegExp): Promise<void> {
  expect(download.suggestedFilename()).toMatch(namePattern);
  const path = await download.path();
  const bytes = await readFile(path);
  expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  expect(bytes.length).toBeGreaterThan(2000);
}

/** Clicks the button and waits for the file it downloads. */
async function download(page: Page, click: () => Promise<void>): Promise<Download> {
  const [file] = await Promise.all([page.waitForEvent('download'), click()]);
  return file;
}

async function plotIdByNumber(page: Page, number: string): Promise<string> {
  const found = expectCreated<{ items: { id: string; number: string }[] }>(
    await api(page, 'GET', `/plots?q=${encodeURIComponent(number)}`),
    200,
  );
  const plot = found.items.find((p) => p.number === number);
  if (!plot) throw new Error(`demo plot ${number} not found`);
  return plot.id;
}

test.describe('invoices, certificates and receipts', () => {
  test('the treasurer builds the invoices of the closed month and downloads the PDF of plot 9 @smoke', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    const { closed } = await demoMonths(page);
    await page.goto(`/charges/months/${closed}`);
    const panel = main(page).locator('section', {
      has: page.getByRole('heading', { name: t.invoices.monthPanel.title, exact: true }),
    });
    await expect(panel).toBeVisible();

    // the preview counts: something to look at, and the demo month already has its invoices
    const counts = panel.getByRole('list', { name: t.invoices.monthPanel.previewTitle });
    await expect(counts).toContainText(/Plots with an owner: [1-9]\d*/);
    await expect(counts).toContainText(/Already invoiced .*: [1-9]\d*/);

    // the build: the result shows, and running it again over unchanged books creates nothing new
    await panel
      .getByRole('button', { name: `Build invoices for ${monthLabel(closed)}`, exact: true })
      .click();
    const result = panel.getByText(/^Built: \d+\. New versions: \d+\. Unchanged: \d+\. Skipped: \d+\.$/);
    await expect(result).toBeVisible();
    await expect(result).toContainText(/Unchanged: [1-9]\d*\./);

    // plot 9's row, with its PDF
    const list = panel.getByRole('list', { name: t.invoices.monthPanel.listTitle });
    const row = list.getByRole('listitem').filter({ has: page.getByText('Plot 9', { exact: true }) });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(`${closed}/9`);
    const file = await download(page, () =>
      row.getByRole('button', { name: t.invoices.download, exact: true }).click(),
    );
    await expectPdf(file, /^invoice_.*plot-9_v\d+\.pdf$/);
  });

  test('"Invoice for today" on plot 9 downloads its PDF, and a second click makes no new version', async ({
    page,
  }) => {
    await loginAs(page, 'treasurer@demo.local');
    const plotId = await plotIdByNumber(page, '9');
    await page.goto(`/plots/${plotId}`);
    await page.getByRole('tab', { name: t.plot.tabs.invoices, exact: true }).click();
    const button = page.getByRole('button', { name: t.invoices.issueToday, exact: true });

    /** Clicks «Invoice for today»; the issued row, the PDF response and the downloaded file. */
    const issueToday = async () => {
      const issued = page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().endsWith(`/api/v1/plots/${plotId}/invoices`),
      );
      const pdf = page.waitForResponse(
        (r) => r.request().method() === 'GET' && /\/api\/v1\/invoices\/[0-9a-f-]{36}\/pdf$/.test(r.url()),
      );
      const file = await download(page, () => button.click());
      const issuedResponse = await issued;
      const pdfResponse = await pdf;
      expect(pdfResponse.status()).toBe(200);
      expect(pdfResponse.headers()['content-type']).toContain('application/pdf');
      await expectPdf(file, /^invoice_\d{4}-\d{2}_plot-9_v\d+\.pdf$/);
      const row = (await issuedResponse.json()) as { id: string; number: string; version: number };
      expect(pdfResponse.url()).toContain(`/invoices/${row.id}/pdf`);
      return { status: issuedResponse.status(), row, fileName: file.suggestedFilename() };
    };

    const first = await issueToday();
    expect([200, 201]).toContain(first.status);
    await expect(button).toBeEnabled();

    // nothing was booked meanwhile: the same version comes back (200), nothing new in the list
    const second = await issueToday();
    expect(second.status).toBe(200);
    expect(second.row.id).toBe(first.row.id);
    expect(second.fileName).toBe(first.fileName);
    // one row for the month (its latest version), the earlier versions folded
    const list = page.getByRole('list', { name: t.invoices.plotTitle });
    const monthRows = list.getByRole('listitem').filter({ hasText: first.row.number });
    await expect(monthRows).toHaveCount(1);
    await expect(monthRows).toContainText(format(t.invoices.version, { version: first.row.version }));
    if (first.row.version > 1) {
      await expect(
        monthRows.getByRole('button', {
          name: format(t.invoices.earlierVersions, { count: first.row.version - 1 }),
          exact: true,
        }),
      ).toHaveAttribute('aria-expanded', 'false');
    }
  });

  test('the treasurer opens the PDF for printing of the closed month', async ({ page }) => {
    await loginAs(page, 'treasurer@demo.local');
    const { closed } = await demoMonths(page);
    await page.goto(`/charges/months/${closed}`);
    await page.getByRole('radio', { name: t.invoices.monthPanel.scopeALL }).check();
    const file = await download(page, () =>
      page.getByRole('button', { name: t.invoices.monthPanel.print, exact: true }).click(),
    );
    await expectPdf(file, new RegExp(`^invoices_${closed}_all\\.pdf$`));
  });

  test('the chairman issues a certificate for plot 9, finds it in the register and annuls it', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const plotId = await plotIdByNumber(page, '9');
    await page.goto(`/plots/${plotId}`);
    await page.getByRole('tab', { name: t.plot.tabs.certificates, exact: true }).click();
    await page.getByRole('button', { name: t.certificates.issue, exact: true }).click();

    // the number is the next one of the year: read from the screen, never assumed to be 1
    const status = page.getByRole('status').filter({ hasText: /Certificate issued: / });
    await expect(status).toBeVisible();
    const number = /Certificate issued: (Д-\d{4}-\d+)/.exec((await status.textContent()) ?? '')?.[1];
    if (!number) throw new Error('no certificate number on the screen');
    const file = await download(page, () =>
      status.getByRole('button', { name: t.certificates.download, exact: true }).click(),
    );
    await expectPdf(file, /^certificate_.*\.pdf$/);

    // the register lists it as issued, with the demo certificate (or earlier ones) before it
    await page.goto('/documents/certificates');
    await expect(page.getByRole('heading', { level: 1, name: t.certificates.title })).toBeVisible();
    const registerRow = page.getByRole('row').filter({ hasText: number });
    await expect(registerRow).toHaveCount(1);
    await expect(registerRow).toContainText(t.certificates.status.ISSUED);
    expect(await page.getByRole('row').count()).toBeGreaterThan(2);

    // annulling needs a reason; then the row says so and the PDF still opens
    await registerRow.getByRole('button', { name: t.certificates.annul, exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: t.certificates.annulConfirm, exact: true }).click();
    await expect(dialog.getByText(t.validation.REQUIRED_reason)).toBeVisible();
    await dialog.getByLabel(t.certificates.annulReasonLabel).fill('Помилково видано (e2e)');
    await dialog.getByRole('button', { name: t.certificates.annulConfirm, exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(registerRow).toContainText(t.certificates.status.ANNULLED);
    await expect(registerRow).toContainText('Помилково видано (e2e)');
    await expect(registerRow.getByRole('button', { name: t.certificates.annul, exact: true })).toHaveCount(0);
    const annulledFile = await download(page, () =>
      registerRow.getByRole('button', { name: t.certificates.download, exact: true }).click(),
    );
    await expectPdf(annulledFile, /^certificate_.*\.pdf$/);
  });

  test('the treasurer prints the receipt of a cash payment, and the card of the payment has the button too', async ({
    page,
    browser,
  }) => {
    const chairman = await signedInPage(browser, 'chairman@demo.local');
    const stamp = Date.now().toString(36);
    const plot: CreatedPlot = { id: '', number: `E2E-D-${stamp}` };
    const owner: CreatedMember = await createMember(chairman, `E2eDoc${stamp}`);
    let paymentId = '';
    try {
      Object.assign(plot, await createPlot(chairman, plot.number));
      await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId: owner.id, share: '1/1' }]);
      const types = expectCreated<{ id: string; code?: string }[]>(
        await api(chairman, 'GET', '/charge-types'),
        200,
      );
      const membership = types.find((type) => type.code === 'MEMBERSHIP');
      if (!membership) throw new Error('the membership charge type is missing');
      expectCreated(
        await api(chairman, 'POST', `/plots/${plot.id}/opening-balances`, {
          chargeTypeId: membership.id,
          amount: '100.00',
          date: kyivDay(-3),
          reason: 'E2E opening balance',
        }),
      );

      await loginAs(page, 'treasurer@demo.local');
      await page.goto(`/plots/${plot.id}`);
      await page.getByRole('link', { name: 'Enter payment', exact: true }).click();
      await page.getByLabel('Amount', { exact: true }).fill('100.00');
      await expect(page.getByRole('table', { name: 'Allocation of the payment to charges' })).toBeVisible();
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(page.getByTestId('receipt-number')).toHaveText(/^Receipt № \d+$/);

      const number = /(\d+)$/.exec((await page.getByTestId('receipt-number').textContent()) ?? '')?.[1];
      const file = await download(page, () =>
        page.getByRole('button', { name: t.receipts.print, exact: true }).click(),
      );
      await expectPdf(file, new RegExp(`^receipt_${number}\\.pdf$`));

      // the same button on the card of the payment
      await page.getByRole('link', { name: 'Open the payment card', exact: true }).click();
      paymentId = /\/payments\/([0-9a-f-]{36})/.exec(page.url())?.[1] ?? '';
      const again = await download(page, () =>
        main(page).getByRole('button', { name: t.receipts.print, exact: true }).click(),
      );
      await expectPdf(again, new RegExp(`^receipt_${number}\\.pdf$`));
    } finally {
      if (paymentId)
        await api(chairman, 'POST', `/payments/${paymentId}/reverse`, { reason: 'E2E clean-up' }).catch(
          () => undefined,
        );
      const account = await api(chairman, 'GET', `/plots/${plot.id}/account`).catch(() => undefined);
      if (account?.status === 200)
        for (const charge of (JSON.parse(account.body) as { charges: { id: string; reversible: boolean }[] })
          .charges)
          if (charge.reversible)
            await api(chairman, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
              () => undefined,
            );
      await archiveLeftovers(chairman, [plot], [owner], kyivDay(0));
      await chairman.context().close();
    }
  });

  test('the member downloads their own invoice from the account, and has no certificates menu item', async ({
    page,
    browser,
  }) => {
    await loginAs(page, 'member@demo.local');
    // The demo member's plots (7 and 15) have nothing due in the demo closed month, so the month build skipped them
    // and the account is empty. The treasurer then issues plot 7's invoice on request (the plot card's "Invoice for
    // today"), which is what a board does for a member who asks.
    const mine = await api(page, 'GET', '/me/invoices');
    if (expectCreated<unknown[]>(mine, 200).length === 0) {
      const treasurer = await signedInPage(browser, 'treasurer@demo.local');
      try {
        const { closed } = await demoMonths(treasurer);
        const plot7 = await plotIdByNumber(treasurer, '7');
        expectCreated(await api(treasurer, 'POST', `/plots/${plot7}/invoices`, { month: closed }));
      } finally {
        await treasurer.context().close();
      }
    }
    await page.goto('/account');
    const section = main(page).locator('section', {
      has: page.getByRole('heading', { name: t.invoices.myTitle, exact: true }),
    });
    await expect(section).toBeVisible();
    const latest = section.getByRole('list', { name: t.invoices.latest });
    await expect(latest.getByRole('listitem').first()).toBeVisible();
    const file = await download(page, () =>
      latest
        .getByRole('listitem')
        .first()
        .getByRole('button', { name: t.invoices.download, exact: true })
        .click(),
    );
    await expectPdf(file, /^invoice_\d{4}-\d{2}_plot-.+_v\d+\.pdf$/);

    // a member never sees certificates: no menu item, and the screen refuses
    await expect(page.getByRole('link', { name: t.nav.certificates, exact: true })).toHaveCount(0);
    await page.goto('/documents/certificates');
    await expect(page.getByRole('heading', { level: 1, name: t.certificates.title })).toHaveCount(0);
  });
});
