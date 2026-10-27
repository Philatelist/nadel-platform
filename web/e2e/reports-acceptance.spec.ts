// @layer: e2e
// @spec: 013-reports
//
// Acceptance of the access rules of reports: the Debtors report needs MEMBERS_VIEW besides REPORTS_VIEW, and the
// Treasurer, who has both, builds it. The electrician takes a temporary custom role with REPORTS_VIEW only and gets
// their own roles back in `finally`.
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, expectCreated } from './support/api';
import { loginAs } from './support/login';

const t = messages('en');
const ELECTRICIAN_MEMBER_ID = 'aaaaaaaa-0000-0000-0000-000000000004';
const ELECTRICIAN_ROLE_ID = 'cccccccc-0000-0000-0000-000000000001';

interface RoleEntry {
  id: string;
  name: string;
  baseCode?: string;
}

async function signedIn(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

test.describe('Reports access', () => {
  test('REPORTS_VIEW without MEMBERS_VIEW: no Debtors card, /reports/debtors shows no access', async ({
    browser,
  }) => {
    const chairman = await signedIn(browser, 'chairman@demo.local');
    let roleId: string | undefined;
    let memberRoleId: string | undefined;
    try {
      const roles = expectCreated<RoleEntry[]>(await api(chairman, 'GET', '/roles'), 200);
      memberRoleId = roles.find((r) => r.baseCode === 'MEMBER')?.id;
      expect(memberRoleId, 'the Member base role').toBeTruthy();
      const created = expectCreated<RoleEntry>(
        await api(chairman, 'POST', '/roles', {
          name: `E2E reports only ${Date.now()}`,
          permissions: ['REPORTS_VIEW'],
        }),
      );
      roleId = created.id;
      expectCreated(
        await api(chairman, 'PUT', `/members/${ELECTRICIAN_MEMBER_ID}/roles`, {
          roleIds: [memberRoleId, ELECTRICIAN_ROLE_ID, roleId],
        }),
        200,
      );

      const electrician = await signedIn(browser, 'electrician@demo.local');
      try {
        await electrician.goto('/reports');
        await expect(electrician.getByRole('heading', { level: 1, name: t.reports.title })).toBeVisible();
        const cards = electrician.getByRole('list', { name: t.reports.title });
        await expect(cards.getByRole('link', { name: t.reports['electricity-tree'].title })).toBeVisible();
        await expect(cards.getByRole('link', { name: t.reports.debtors.title, exact: true })).toHaveCount(0);

        await electrician.goto('/reports/debtors');
        await expect(electrician.getByRole('heading', { level: 1, name: t.access.denied })).toBeVisible();
      } finally {
        await electrician.context().close();
      }
    } finally {
      await api(chairman, 'PUT', `/members/${ELECTRICIAN_MEMBER_ID}/roles`, {
        roleIds: [memberRoleId, ELECTRICIAN_ROLE_ID].filter((id) => id !== undefined),
      }).catch(() => undefined);
      if (roleId !== undefined) await api(chairman, 'DELETE', `/roles/${roleId}`).catch(() => undefined);
      await chairman.context().close();
    }
  });

  test('the Treasurer sees the Debtors report and its preview', async ({ browser }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/reports');
      await treasurer
        .getByRole('list', { name: t.reports.title })
        .getByRole('link', { name: t.reports.debtors.title, exact: true })
        .click();
      await expect(treasurer).toHaveURL(/\/reports\/debtors/);
      await expect(treasurer.getByRole('heading', { level: 1, name: t.reports.debtors.title })).toBeVisible();
      await expect(
        treasurer.getByRole('table').first().or(treasurer.getByText(t.reports.noRows)),
      ).toBeVisible();
      await expect(treasurer.getByText(t.state.error)).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });
});
