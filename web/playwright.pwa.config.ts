import { defineConfig, devices } from '@playwright/test';

// The PWA specs need the production build (the service worker and the manifest exist only there): `make e2e-pwa` builds
// the web, starts the backend and `vite preview` on :5173 (same /api proxy as the dev server) and runs this config.
export default defineConfig({
  testDir: './e2e/pwa',
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'en-US',
    serviceWorkers: 'allow',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
