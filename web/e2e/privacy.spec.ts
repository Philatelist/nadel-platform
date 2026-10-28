// @layer: e2e
// @spec: 014-personal-data-encryption
// @regression
//
// Acceptance of the audit log over encrypted contacts (§2.4): a person whose role may read the audit log but not view
// members sees that a phone changed with the word "hidden" instead of the values, while the chairman sees both
// values. The electrician takes a temporary custom role with AUDIT_VIEW only and gets their own roles back in
// `finally`; the member is archived afterwards.
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

/** The text of the main area without spaces, brackets and dashes, so that a formatted phone still matches. */
async function compactText(page: Page): Promise<string> {
  return (await page.getByRole('main').innerText()).replace(/[\s()-]/g, '');
}

test.describe('audit log and personal data', () => {
  test('AUDIT_VIEW without MEMBERS_VIEW sees "hidden" for a changed phone, the chairman sees the values', async ({
    browser,
  }) => {
    const chairman = await signedIn(browser, 'chairman@demo.local');
    const oldPhone = `+38093${String(Math.floor(Math.random() * 10_000_000)).padStart(7, '0')}`;
    const newPhone = `+38067${String(Math.floor(Math.random() * 10_000_000)).padStart(7, '0')}`;
    const lastName = `Auditpriv${Date.now()}`;
    let roleId: string | undefined;
    let memberRoleId: string | undefined;
    let memberId: string | undefined;
    try {
      memberId = expectCreated<{ id: string }>(
        await api(chairman, 'POST', '/members', { lastName, firstName: 'Test', phones: [oldPhone] }),
      ).id;
      expectCreated(
        await api(chairman, 'PUT', `/members/${memberId}`, {
          lastName,
          firstName: 'Test',
          phones: [newPhone],
        }),
        200,
      );

      const roles = expectCreated<RoleEntry[]>(await api(chairman, 'GET', '/roles'), 200);
      memberRoleId = roles.find((r) => r.baseCode === 'MEMBER')?.id;
      expect(memberRoleId, 'the Member base role').toBeTruthy();
      roleId = expectCreated<RoleEntry>(
        await api(chairman, 'POST', '/roles', {
          name: `E2E audit only ${Date.now()}`,
          permissions: ['AUDIT_VIEW'],
        }),
      ).id;
      expectCreated(
        await api(chairman, 'PUT', `/members/${ELECTRICIAN_MEMBER_ID}/roles`, {
          roleIds: [memberRoleId, ELECTRICIAN_ROLE_ID, roleId],
        }),
        200,
      );

      // the chairman: both phones are shown (the audit page is not live: reload until the outbox wrote the entries)
      await chairman.goto(`/audit?entityId=${memberId}`);
      await expect(async () => {
        await chairman.reload();
        // reload resolves before the list is fetched: wait for the page to render the entries instead of reading "Loading…"
        await expect.poll(() => compactText(chairman), { timeout: 5000 }).toContain(oldPhone);
        expect(await compactText(chairman)).toContain(newPhone);
      }).toPass({ timeout: 15_000 });
      await expect(chairman.getByTestId('audit-hidden')).toHaveCount(0);

      // the electrician with AUDIT_VIEW only: the same entries, the word instead of the values
      const electrician = await signedIn(browser, 'electrician@demo.local');
      try {
        await electrician.goto(`/audit?entityId=${memberId}`);
        await expect(async () => {
          await electrician.reload();
          await expect(electrician.getByTestId('audit-hidden').first()).toHaveText(t.audit.hiddenValue, {
            timeout: 1500,
          });
        }).toPass({ timeout: 15_000 });
        const text = await compactText(electrician);
        expect(text).not.toContain(oldPhone);
        expect(text).not.toContain(newPhone);
        // other fields are shown as before: the record is still named
        expect(text).toContain(lastName);
      } finally {
        await electrician.context().close();
      }
    } finally {
      await api(chairman, 'PUT', `/members/${ELECTRICIAN_MEMBER_ID}/roles`, {
        roleIds: [memberRoleId, ELECTRICIAN_ROLE_ID].filter((id) => id !== undefined),
      }).catch(() => undefined);
      if (roleId !== undefined) await api(chairman, 'DELETE', `/roles/${roleId}`).catch(() => undefined);
      if (memberId !== undefined)
        await api(chairman, 'POST', `/members/${memberId}/archive`).catch(() => undefined);
      await chairman.context().close();
    }
  });
});
