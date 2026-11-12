// @layer: e2e
// @spec: 021-member-pwa
// @regression
//
// Keycloak served on the app origin under /auth/ (like the stand behind Caddy): with the service worker in control, the
// sign-in and sign-out redirects must reach Keycloak, not the app shell. `make e2e-pwa` builds the web with
// VITE_OIDC_AUTHORITY=http://localhost:5173/auth/realms/nadel and starts `vite preview` with the /auth proxy.
import { expect, test } from '@playwright/test';
import { loginAs } from '../support/login';

// Runs only when the stack serves Keycloak under /auth/ (as `make e2e-pwa` sets it up: the build with the /auth authority
// and `vite preview` with NADEL_E2E_KEYCLOAK_UNDER_AUTH=1). A preview without the /auth proxy cannot show the bug.
test.beforeAll(async ({ request }) => {
  const discovery = await request.get('/auth/realms/nadel/.well-known/openid-configuration');
  const underAuth =
    discovery.ok() && (discovery.headers()['content-type'] ?? '').includes('application/json');
  test.skip(
    !underAuth,
    'Keycloak is not served under /auth/: build with VITE_OIDC_AUTHORITY=http://localhost:5173/auth/realms/nadel and start vite preview with NADEL_E2E_KEYCLOAK_UNDER_AUTH=1 (make e2e-pwa does both)',
  );
});

test('the installed app signs in again and signs out with the worker in control', async ({
  page,
  context,
}) => {
  await loginAs(page, 'member@demo.local');
  expect(page.url()).toContain('localhost:5173');
  await page.evaluate(async () => (await navigator.serviceWorker.ready).scope);

  // the worker controls the page after a reload
  await page.reload();
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true);

  // sign out: the end-session redirect goes to Keycloak under /auth/ and lands back on the app's sign-in
  await page
    .getByRole('banner')
    .getByRole('button', { name: /^(Sign out|Вийти)$/ })
    .click();
  await expect(page.getByRole('button', { name: /^(Sign in|Увійти)$/ })).toBeVisible();

  // close the page and open the app again, the worker now controlling it: sign in once more
  await page.close();
  const again = await context.newPage();
  await again.goto('/');
  await expect.poll(() => again.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true);
  await again.getByRole('button', { name: /^(Sign in|Увійти)$/ }).click();
  await expect(again.locator('#username')).toBeVisible();
  expect(new URL(again.url()).pathname).toContain('/auth/realms/nadel/');
  await again.locator('#username').fill('member@demo.local');
  await again.locator('#password').fill(process.env.E2E_PASSWORD ?? 'nadel');
  await again.locator('#kc-login').click();
  await expect(again.getByRole('banner').getByRole('button', { name: /^(Sign out|Вийти)$/ })).toBeVisible();

  // and sign out again
  await again
    .getByRole('banner')
    .getByRole('button', { name: /^(Sign out|Вийти)$/ })
    .click();
  await expect(again.getByRole('button', { name: /^(Sign in|Увійти)$/ })).toBeVisible();
});
