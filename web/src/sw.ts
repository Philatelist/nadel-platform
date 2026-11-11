/// <reference lib="webworker" />
// Service worker: precache of the build output and the app-shell navigation fallback, nothing else.
// There are NO runtime caches: personal data (API answers, invoice PDFs, meter photos) must never reach the device
// storage through the worker. `sw.contract.test.ts` reads this file and enforces that. A `push` handler is added by
// the push slice next to the `message` handler.
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';

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
