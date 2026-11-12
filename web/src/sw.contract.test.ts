import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HELP_SHOTS_MAX_ENTRIES, HELP_SHOTS_PATH } from './pwa/cacheNames';

// The worker must never cache personal data: API answers, invoice PDFs and meter photos stay off the device. Its only
// runtime cache holds the public help screenshots.
// This reads the worker source, because a worker cannot be exercised in jsdom.
const source = readFileSync(resolve(process.cwd(), 'src', 'sw.ts'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

describe('sw.ts contract', () => {
  it('has exactly one runtime cache: CacheFirst for the help screenshots, with a size limit', () => {
    expect(source).not.toMatch(/runtimeCaching/);
    expect(source).not.toMatch(/\bcaches\.open\s*\(/);
    expect(source).not.toMatch(/\b(StaleWhileRevalidate|NetworkFirst|CacheOnly|NetworkOnly)\b/);
    expect(source.match(/new CacheFirst\(/g)).toHaveLength(1);
    expect(source).toContain('cacheName: HELP_SHOTS_CACHE');
    expect(source).toMatch(/HELP_SHOTS_PATH\.test\(url\.pathname\)/);
    expect(source).toMatch(/sameOrigin/);
    expect(source).toContain('maxEntries: HELP_SHOTS_MAX_ENTRIES');
    expect(source).toContain('maxAgeSeconds: HELP_SHOTS_MAX_AGE_SECONDS');
    expect(source).toContain("startsWith('image/')");
  });

  it('matches only help screenshots, never the API, PDFs, photos or other images', () => {
    for (const path of ['/help/img/uk/meter-tree.png', '/help/img/en/roles-new.png'])
      expect(HELP_SHOTS_PATH.test(path), path).toBe(true);
    for (const path of [
      '/api/v1/me',
      '/api/v1/readings/1/photo',
      '/api/v1/invoices/1/pdf',
      '/help/img/uk/../../api/x.png',
      '/help/img/de/a.png',
      '/help/uk/account/index.html',
      '/icon-192.png',
      '/x/help/img/uk/a.png',
    ])
      expect(HELP_SHOTS_PATH.test(path), path).toBe(false);
    expect(HELP_SHOTS_MAX_ENTRIES).toBeLessThanOrEqual(100);
  });

  it('registers exactly two routes: the navigation fallback and the help screenshots', () => {
    expect(source.match(/registerRoute\s*\(/g)).toHaveLength(2);
    expect(source).toMatch(/registerRoute\(\s*new NavigationRoute\(/);
  });

  it('keeps the API, Keycloak and files out of the navigation fallback', () => {
    expect(source).toContain('denylist');
    expect(source).toContain('/^\\/api\\//');
    expect(source).toContain('/^\\/realms\\//');
    expect(source).toContain('/\\/[^/?]+\\.[^/]+$/');
  });

  it('precaches the build, cleans old caches and takes over only on SKIP_WAITING', () => {
    expect(source).toContain('precacheAndRoute(self.__WB_MANIFEST)');
    expect(source).toContain('cleanupOutdatedCaches()');
    expect(source).toContain("'SKIP_WAITING'");
    // no automatic activation at install time
    expect(source).not.toMatch(/addEventListener\(\s*'install'/);
    expect(source).not.toMatch(/clientsClaim/);
  });

  it('adds only the push and notificationclick listeners', () => {
    const events = [...source.matchAll(/addEventListener\(\s*'([a-z]+)'/g)].map((m) => m[1]);
    expect(events.sort()).toEqual(['message', 'notificationclick', 'push']);
  });
});
