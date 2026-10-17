import { expect, type Page } from '@playwright/test';

const PASSWORD = process.env.E2E_PASSWORD ?? 'nadel';

export interface LoginOptions {
  /** Leave the stored/browser language alone (the default is to force English). */
  keepLocale?: boolean;
  /** Do not wait for the signed-in header (for people who belong to no cooperative or are not linked). */
  skipNavigationWait?: boolean;
}

/** Signs in through the Keycloak form and waits until the signed-in header (Sign out) is visible. */
export async function loginAs(page: Page, email: string, options: LoginOptions = {}): Promise<void> {
  if (!options.keepLocale) {
    // English UI regardless of the stored choice; set before the app loads.
    await page.addInitScript(() => {
      try {
        window.localStorage.setItem('nadel.locale', 'en');
      } catch {
        // storage unavailable: the browser locale (en-US) is used
      }
    });
  }
  await page.goto('/');

  const signInButton = page.getByRole('button', { name: /^(Sign in|Увійти)$/ });
  const username = page.locator('#username');
  await expect(signInButton.or(username)).toBeVisible();
  if (await signInButton.isVisible()) await signInButton.click();

  await username.fill(email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();

  await page.waitForURL('http://localhost:5173/**');
  if (!options.skipNavigationWait)
    await expect(page.getByRole('banner').getByRole('button', { name: /^(Sign out|Вийти)$/ })).toBeVisible();
}
