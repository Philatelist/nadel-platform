/// <reference lib="webworker" />
// Service worker: precache of the build output (app shell and help text), the app-shell navigation fallback, and one
// runtime cache for the public help screenshots, nothing else. Personal data (API answers, invoice PDFs, meter photos)
// must never reach the device storage through the worker: the only runtime route matches `/help/img/<lang>/<id>.png`
// and keeps only image answers. `sw.contract.test.ts` reads this file and enforces that. The `push` and
// `notificationclick` handlers only show a notification and open a page; they never store anything.
import { ExpirationPlugin } from 'workbox-expiration';
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
import { CacheFirst } from 'workbox-strategies';
import {
  HELP_SHOTS_CACHE,
  HELP_SHOTS_MAX_AGE_SECONDS,
  HELP_SHOTS_MAX_ENTRIES,
  HELP_SHOTS_PATH,
} from './pwa/cacheNames';
import { buildPushView, safeUrl } from './pwa/pushPayload';

declare let self: ServiceWorkerGlobalScope;

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

// Navigations get the app shell, except the API, Keycloak and anything that looks like a file (PDF, image, asset).
registerRoute(
  new NavigationRoute(createHandlerBoundToURL('/index.html'), {
    denylist: [/^\/api\//, /^\/realms\//, /\/[^/?]+\.[^/]+$/],
  }),
);

// Help screenshots: not precached (they would make the first install about 27 MiB larger); each one is cached the first
// time it is shown. Same origin, GET, the help image path only, and only a 200 image answer is stored (a fallback
// page is not). Offline, a screenshot not yet seen fails and the help shows its alt text instead.
registerRoute(
  ({ url, request, sameOrigin }) =>
    sameOrigin && request.method === 'GET' && HELP_SHOTS_PATH.test(url.pathname),
  new CacheFirst({
    cacheName: HELP_SHOTS_CACHE,
    plugins: [
      {
        cacheWillUpdate: async ({ response }) =>
          response.status === 200 && (response.headers.get('content-type') ?? '').startsWith('image/')
            ? response
            : null,
      },
      new ExpirationPlugin({
        maxEntries: HELP_SHOTS_MAX_ENTRIES,
        maxAgeSeconds: HELP_SHOTS_MAX_AGE_SECONDS,
        purgeOnQuotaError: true,
      }),
    ],
  }),
);

// The page asks a waiting worker to take over only when the user presses «Update».
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') void self.skipWaiting();
});

// Push: the message carries a code and parameters; the text comes from the bundled push texts. A visible notification
// is always shown (userVisibleOnly).
self.addEventListener('push', (event) => {
  const view = buildPushView(event.data?.text());
  event.waitUntil(
    self.registration.showNotification(view.title, {
      body: view.body,
      icon: view.icon,
      badge: view.badge,
      tag: view.tag,
      data: { url: view.url },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = safeUrl((event.notification.data as { url?: unknown } | null)?.url);
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      const existing = windows.find((client) => new URL(client.url).origin === self.location.origin);
      if (existing) {
        await existing.focus();
        if ('navigate' in existing) await existing.navigate(url);
      } else {
        await self.clients.openWindow(url);
      }
    })(),
  );
});
