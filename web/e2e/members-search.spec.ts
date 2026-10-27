// @layer: e2e
// @spec: 014-personal-data-encryption
// @regression
//
// Acceptance of the member search over encrypted contacts (§2.2): the chairman finds a member by the last 4 digits of
// the phone and by the whole phone written another way, and does not find it by digits from the middle of the number.
// The member is created through the API with a unique phone and archived afterwards (a member with an audit
// history cannot be deleted).
import { expect, test } from '@playwright/test';
import { messages } from '@nadel/shared';
import { api, expectCreated } from './support/api';
import { loginAs } from './support/login';

const t = messages('en');

test.describe('member search by phone', () => {
  test('the chairman finds a member by the last 4 digits and by the whole phone in another writing @smoke', async ({
    page,
  }) => {
    await loginAs(page, 'chairman@demo.local');
    const tail = String(Math.floor(Math.random() * 10_000_000)).padStart(7, '0');
    const phone = `+38093${tail}`;
    const lastName = `Phonesearch${Date.now()}`;
    let memberId: string | undefined;
    try {
      memberId = expectCreated<{ id: string }>(
        await api(page, 'POST', '/members', { lastName, firstName: 'Test', phones: [phone] }),
      ).id;
      await page.goto('/members');
      const search = page.getByLabel(t.members.searchPlaceholder);
      const memberLink = page.getByRole('main').getByRole('link', { name: new RegExp(lastName) });

      // the last 4 digits
      await search.fill(phone.slice(-4));
      await expect(memberLink).toBeVisible();

      // the whole phone as people write it: 093 123 45 67
      await search.fill(
        `0${phone.slice(4, 6)} ${phone.slice(6, 9)} ${phone.slice(9, 11)} ${phone.slice(11)}`,
      );
      await expect(memberLink).toBeVisible();

      // negative: digits from the middle of the number find nothing
      await search.fill(phone.slice(5, 11));
      await expect(page.getByText(t.members.nothingFound)).toBeVisible();
      await expect(memberLink).toHaveCount(0);

      // negative: the last 3 digits are not the last 4
      await search.fill(phone.slice(-3));
      await expect(page.getByText(t.members.nothingFound)).toBeVisible();
    } finally {
      if (memberId !== undefined)
        await api(page, 'POST', `/members/${memberId}/archive`).catch(() => undefined);
    }
  });
});
