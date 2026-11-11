// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// Paying from the phone (feature 021 section 2.4): on a 360x740 touch screen the member opens «My account», sees the
// payment buttons of an invoice with something due and uses them: the bank link is an NBU payment link opened in a new
// tab, «Copy IBAN» and «Copy payment purpose» put the values of the API's `payment` into the clipboard and say «Copied»,
// the page has no side scroll and the buttons are at least 44 px tall. An invoice without `payment` shows no buttons.
// The demo member's plots have nothing due in the demo closed month, so when none of the member's invoices carries
// payment details the treasurer issues invoices on request for the member's plots (the way invoices.spec.ts does);
// invoices cannot be deleted, so nothing is cleaned up (the on-request issue is idempotent while nothing changes).
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, demoMonths, expectCreated } from './support/api';
import { loginAs } from './support/login';

const t = messages('en');
const main = (page: Page) => page.getByRole('main');

test.use({ viewport: { width: 360, height: 740 }, isMobile: true, hasTouch: true });

interface MyInvoice {
  id: string;
  payment?: { link: string; iban: string; purpose: string };
}

async function myInvoices(page: Page): Promise<MyInvoice[]> {
  return expectCreated<MyInvoice[]>(await api(page, 'GET', '/me/invoices'), 200);
}

/** Makes sure the demo member has an invoice with something due, issued by the treasurer on request. */
async function ensurePayableInvoice(page: Page, browser: Browser): Promise<MyInvoice> {
  const found = (await myInvoices(page)).find((r) => r.payment !== undefined);
  if (found) return found;
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const treasurer = await context.newPage();
  try {
    await loginAs(treasurer, 'treasurer@demo.local');
    const { open, closed } = await demoMonths(treasurer);
    const plots: string[] = [];
    for (const number of ['7', '15']) {
      const found = expectCreated<{ items: { id: string; number: string }[] }>(
        await api(treasurer, 'GET', `/plots?q=${encodeURIComponent(number)}`),
        200,
      );
      const plot = found.items.find((p) => p.number === number);
      if (plot) plots.push(plot.id);
    }
    for (const plotId of plots)
      for (const month of [open, closed])
        await api(treasurer, 'POST', `/plots/${plotId}/invoices`, { month });
  } finally {
    await context.close();
  }
  const issued = (await myInvoices(page)).find((r) => r.payment !== undefined);
  if (!issued) throw new Error('the demo member has no invoice with something due, even after issuing');
  return issued;
}

async function openMyInvoices(page: Page) {
  await page.goto('/account');
  const section = main(page).locator('section', {
    has: page.getByRole('heading', { name: t.invoices.myTitle, exact: true }),
  });
  await expect(section).toBeVisible();
  return section;
}

test.describe('paying from the phone', () => {
  test('the member pays from an invoice: bank link, copy IBAN and copy purpose @smoke', async ({
    page,
    browser,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await loginAs(page, 'member@demo.local');
    const invoice = await ensurePayableInvoice(page, browser);
    const payment = invoice.payment;
    if (!payment) throw new Error('unreachable: the invoice has payment details');
    const section = await openMyInvoices(page);
    const row = section.getByRole('listitem').filter({
      has: page.getByRole('link', { name: t.invoices.pay.payInBank, exact: true }),
    });
    await expect(row.first()).toBeVisible();

    const link = row.first().getByRole('link', { name: t.invoices.pay.payInBank, exact: true });
    await expect(link).toHaveAttribute('href', payment.link);
    expect(payment.link.startsWith('https://qr.bank.gov.ua/')).toBe(true);
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', /noopener/);

    const clipboard = () => page.evaluate(() => navigator.clipboard.readText());
    await row.first().getByRole('button', { name: t.invoices.pay.copyIban, exact: true }).click();
    await expect(row.first().getByText(t.invoices.pay.copied, { exact: true })).toBeVisible();
    expect(await clipboard()).toBe(payment.iban);

    await row.first().getByRole('button', { name: t.invoices.pay.copyPurpose, exact: true }).click();
    await expect(row.first().getByText(t.invoices.pay.copied, { exact: true })).toBeVisible();
    expect(await clipboard()).toBe(payment.purpose);

    // a phone layout: no side scroll, buttons a finger can hit
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
    for (const target of [
      link,
      row.first().getByRole('button', { name: t.invoices.pay.copyIban, exact: true }),
      row.first().getByRole('button', { name: t.invoices.pay.copyPurpose, exact: true }),
    ]) {
      const box = await target.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
  });

  test('an invoice without payment details shows no payment buttons', async ({ page }) => {
    await page.route('**/api/v1/me/invoices**', async (route) => {
      const response = await route.fetch();
      const rows = (await response.json()) as Record<string, unknown>[];
      await route.fulfill({
        response,
        json: rows.map((row) => {
          const withoutPayment = { ...row };
          delete withoutPayment.payment;
          return withoutPayment;
        }),
      });
    });
    await loginAs(page, 'member@demo.local');
    const section = await openMyInvoices(page);
    await expect(
      section.getByRole('button', { name: t.invoices.download, exact: true }).first(),
    ).toBeVisible();
    await expect(section.getByRole('link', { name: t.invoices.pay.payInBank, exact: true })).toHaveCount(0);
    await expect(section.getByRole('button', { name: t.invoices.pay.copyIban, exact: true })).toHaveCount(0);
    await expect(section.getByRole('button', { name: t.invoices.pay.copyPurpose, exact: true })).toHaveCount(
      0,
    );
  });
});
