/// <reference lib="webworker" />
// Service worker: precache of the build output and the app-shell navigation fallback, nothing else.
// There are NO runtime caches: personal data (API answers, invoice PDFs, meter photos) must never reach the device
// storage through the worker. `sw.contract.test.ts` reads this file and enforces that. The `push` and `notificationclick`
// handlers only show a notification and open a page; they never store anything.
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
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
