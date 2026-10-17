import { expect, type Page } from '@playwright/test';

const PASSWORD = process.env.E2E_PASSWORD ?? 'nadel';

/** Signs in through the Keycloak form and waits until the app navigation is visible. */
export async function loginAs(page: Page, email: string): Promise<void> {
  // English UI regardless of the stored choice; set before the app loads.
  await page.addInitScript(() => {
    try {
      window.localStorage.setItem('nadel.locale', 'en');
    } catch {
      // storage unavailable: the browser locale (en-US) is used
    }
  });
  await page.goto('/');

  const signInButton = page.getByRole('button', { name: 'Sign in' });
  const username = page.locator('#username');
  await expect(signInButton.or(username)).toBeVisible();
  if (await signInButton.isVisible()) await signInButton.click();

  await username.fill(email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL('http://localhost:5173/**');
  await expect(page.getByRole('navigation')).toBeVisible();
}
