import { expect, test } from '@playwright/test';
import { loginAs } from './support/login';

test.describe('members access', () => {
  test('treasurer sees the member list and a read-only card @smoke', async ({ page }) => {
    await loginAs(page, 'treasurer@demo.local');

    await page.locator('#app-menu nav').getByRole('link', { name: 'Members' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible();

    const rows = page.getByRole('main').locator('tbody tr');
    await expect(rows.first()).toBeVisible();

    await rows.first().getByRole('link').first().click();
    await expect(page.getByRole('link', { name: 'Back to members' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    await expect(page.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Archive' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Restore' })).toHaveCount(0);
  });

  test('member has no Members item and is denied the page', async ({ page }) => {
    await loginAs(page, 'member@demo.local');

    await expect(page.locator('#app-menu nav').getByRole('link', { name: 'Members' })).toHaveCount(0);

    await page.goto('/members');
    await expect(page.getByText("You don't have access to this page")).toBeVisible();
  });
});
