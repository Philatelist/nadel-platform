import { expect, type Page } from '@playwright/test';

const PASSWORD = process.env.E2E_PASSWORD ?? 'nadel';

export interface LoginOptions {
  /** Leave the stored/browser language alone (the default is to force English). */
  keepLocale?: boolean;
  /** Do not wait for the signed-in header (for people who belong to no cooperative or are not linked). */
  skipNavigationWait?: boolean;
}

async function submitKeycloakForm(page: Page, email: string, password: string): Promise<void> {
  await page.locator('#username').fill(email);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
}

/** Fills the Keycloak form that is already open (after «Sign in» on the app or the set-password page) and waits for the app. */
export async function signInAtKeycloak(page: Page, email: string, password: string): Promise<void> {
  await expect(page.locator('#username')).toBeVisible();
  await submitKeycloakForm(page, email, password);
  await page.waitForURL('http://localhost:5173/**');
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

  await submitKeycloakForm(page, email, PASSWORD);

  await page.waitForURL('http://localhost:5173/**');
  if (!options.skipNavigationWait)
    // desktop shows Sign out in the header; below md it is inside the menu and the header holds the Menu button
    await expect(
      page
        .getByRole('banner')
        .getByRole('button', { name: /^(Sign out|Вийти)$/ })
        .or(page.getByRole('banner').getByRole('button', { name: /^(Menu|Меню)$/ })),
    ).toBeVisible();
}
