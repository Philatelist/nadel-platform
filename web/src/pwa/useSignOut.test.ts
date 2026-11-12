import { describe, expect, it, vi } from 'vitest';
import { HELP_SHOTS_CACHE } from './cacheNames';
import { deleteUserCaches, isPrecacheName, isPublicCacheName, signOutCleanly } from './useSignOut';

describe('deleteUserCaches', () => {
  it('keeps the Workbox precache and the help screenshots and deletes every other cache', async () => {
    const names = ['workbox-precache-v2-https://app.example/', HELP_SHOTS_CACHE, 'api-cache', 'pdfs'];
    const deleted: string[] = [];
    await deleteUserCaches({
      keys: async () => names,
      delete: async (name) => {
        deleted.push(name);
        return true;
      },
    });
    expect(deleted).toEqual(['api-cache', 'pdfs']);
    expect(isPrecacheName(names[0]!)).toBe(true);
    expect(isPublicCacheName(HELP_SHOTS_CACHE)).toBe(true);
    expect(isPublicCacheName('nadel-help-shots-v0')).toBe(false);
  });
});

describe('signOutCleanly', () => {
  it('cleans up, then clears sessionStorage, then redirects', async () => {
    const order: string[] = [];
    await signOutCleanly({
      cleanups: [
        async () => {
          order.push('caches');
        },
      ],
      clearSession: () => order.push('session'),
      signoutRedirect: async () => {
        order.push('redirect');
      },
    });
    expect(order).toEqual(['caches', 'session', 'redirect']);
  });

  it('signs out even when a clean-up fails or hangs past the cap', async () => {
    vi.useFakeTimers();
    try {
      const redirect = vi.fn().mockResolvedValue(undefined);
      const done = signOutCleanly({
        cleanups: [() => Promise.reject(new Error('boom')), () => new Promise(() => undefined)],
        clearSession: vi.fn(),
        signoutRedirect: redirect,
        capMs: 3000,
      });
      await vi.advanceTimersByTimeAsync(3000);
      await done;
      expect(redirect).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
