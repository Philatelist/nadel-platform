import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// The worker must never cache personal data: API answers, invoice PDFs and meter photos stay off the device.
// This reads the worker source, because a worker cannot be exercised in jsdom.
const source = readFileSync(resolve(process.cwd(), 'src', 'sw.ts'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

describe('sw.ts contract', () => {
  it('has no runtime caching', () => {
    expect(source).not.toMatch(/runtimeCaching/);
    expect(source).not.toMatch(/\bcaches\.open\s*\(/);
    expect(source).not.toMatch(/\b(StaleWhileRevalidate|CacheFirst|NetworkFirst|CacheOnly)\b/);
    expect(source).not.toMatch(/workbox-strategies/);
  });

  it('registers exactly one route: the navigation fallback', () => {
    expect(source.match(/registerRoute\s*\(/g)).toHaveLength(1);
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

  it('adds only the push and notificationclick listeners, still without caching', () => {
    const events = [...source.matchAll(/addEventListener\(\s*'([a-z]+)'/g)].map((m) => m[1]);
    expect(events.sort()).toEqual(['message', 'notificationclick', 'push']);
  });
});
