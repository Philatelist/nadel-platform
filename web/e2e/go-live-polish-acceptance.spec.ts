// @layer: e2e
// @spec: 020-go-live-polish
// @regression
// Acceptance checks of feature 020 that no other spec covers (FS 2.3): on a phone-sized screen (360 px) the payer of a
// one-owner plot is preselected, "Allocated X of Y" stays inside the screen without horizontal scrolling, and an amount above the open amount of a line
// shows "More than the remaining ..." above that amount. The jsdom unit tests cannot measure a layout, so the overflow
// rule is checked in the browser. The plot, owner and opening balance are created through the API and tidied after.
import { expect, test, type Page } from '@playwright/test';
import {
  api,
  archiveLeftovers,
  changeOwners,
  createMember,
  createPlot,
  expectCreated,
  kyivDay,
  type CreatedMember,
  type CreatedPlot,
} from './support/api';
import { loginAs } from './support/login';

async function signedIn(browser: import('@playwright/test').Browser, email: string, width?: number) {
  const context = await browser.newContext({
    baseURL: 'http://localhost:5173',
    locale: 'en-US',
    ...(width === undefined ? {} : { viewport: { width, height: 800 } }),
  });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

async function tidy(chairman: Page, plot: CreatedPlot, member: CreatedMember): Promise<void> {
  const account = await api(chairman, 'GET', `/plots/${plot.id}/account`).catch(() => undefined);
  if (account?.status === 200)
    for (const charge of (JSON.parse(account.body) as { charges: { id: string; reversible: boolean }[] })
      .charges)
      if (charge.reversible)
        await api(chairman, 'POST', `/charges/${charge.id}/reverse`, { reason: 'E2E clean-up' }).catch(
          () => undefined,
        );
  await archiveLeftovers(chairman, [plot], [member], kyivDay(0));
}

test.describe('020 payment form on a 360 px screen', () => {
  test('the only owner pays by default, the total stays on one line and an over-amount is explained above the amount', async ({
    browser,
  }) => {
    const chairman = await signedIn(browser, 'chairman@demo.local');
    const phone = await signedIn(browser, 'treasurer@demo.local', 360);
    const suffix = Date.now().toString(36);
    const plot: CreatedPlot = { id: '', number: `E2E-P-L${suffix}` };
    const member = await createMember(chairman, `E2eLive${suffix}`);
    try {
      Object.assign(plot, await createPlot(chairman, plot.number));
      await changeOwners(chairman, plot.id, kyivDay(-10), [{ memberId: member.id, share: '1/1' }]);
      const types = expectCreated<{ id: string; code?: string }[]>(
        await api(chairman, 'GET', '/charge-types'),
        200,
      );
      const membership = types.find((type) => type.code === 'MEMBERSHIP');
      if (!membership) throw new Error('the membership charge type is missing');
      expectCreated(
        await api(chairman, 'POST', `/plots/${plot.id}/opening-balances`, {
          chargeTypeId: membership.id,
          amount: '1000.00',
          date: kyivDay(-3),
          reason: 'E2E opening balance',
        }),
      );

      await phone.goto(`/plots/${plot.id}`);
      await phone.getByRole('link', { name: 'Enter payment', exact: true }).click();
      await expect(phone.getByRole('heading', { level: 1, name: 'Enter payment' })).toBeVisible();

      // the only current owner is already the payer
      await expect(phone.getByRole('main')).toContainText(member.lastName);
      await expect(phone.getByRole('button', { name: 'Change', exact: true })).toBeVisible();

      // the allocation lines are cards on a phone: no horizontal scrolling, the total is inside the screen
      await phone.getByLabel('Amount', { exact: true }).fill('400.00');
      const proposal = phone.getByRole('table', { name: 'Allocation of the payment to charges' });
      await expect(proposal.getByLabel(/^Amount to allocate:/)).toHaveValue('400.00');
      const total = proposal.getByRole('rowheader', { name: /^Allocated/ });
      await expect(total).toContainText('of');
      const overflow = await proposal.evaluate((el) => {
        const area = el.parentElement!;
        return { scroll: area.scrollWidth, client: area.clientWidth };
      });
      expect(overflow.scroll).toBeLessThanOrEqual(overflow.client);
      const box = await total.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(360);
      const fieldBox = await proposal.getByLabel(/^Amount to allocate:/).boundingBox();
      expect(fieldBox!.x + fieldBox!.width).toBeLessThanOrEqual(360);

      // more than the open amount: the explanation sits above the input
      const input = proposal.getByLabel(/^Amount to allocate:/);
      await input.fill('1500.00');
      const warning = proposal.getByText(/^More than the remaining/);
      await expect(warning).toBeVisible();
      const warningBox = await warning.boundingBox();
      const inputBox = await input.boundingBox();
      expect(warningBox!.y + warningBox!.height).toBeLessThanOrEqual(inputBox!.y + 1);
    } finally {
      await tidy(chairman, plot, member);
      await phone.context().close();
      await chairman.context().close();
    }
  });
});
