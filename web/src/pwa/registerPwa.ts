import { useSyncExternalStore } from 'react';

// Service worker registration and the «new version» state. Production builds only: no worker in `vite dev`.
// `registerType: 'prompt'`: a new worker waits until `applyUpdate()` (the user pressed «Update»).
type UpdateServiceWorker = (reloadPage?: boolean) => Promise<void>;

let needRefresh = false;
let updateServiceWorker: UpdateServiceWorker | null = null;
let applying = false;
const listeners = new Set<() => void>();

const UPDATE_CHECK_MS = 60 * 60 * 1000;

function setNeedRefresh(value: boolean): void {
  needRefresh = value;
  for (const listener of listeners) listener();
}

export async function registerPwa(enabled: boolean = import.meta.env.PROD): Promise<void> {
  if (!enabled || !('serviceWorker' in navigator)) return;
  const { registerSW } = await import('virtual:pwa-register');
  updateServiceWorker = registerSW({
    onNeedRefresh: () => setNeedRefresh(true),
    onRegisteredSW: (_url, registration) => {
      // an installed app can stay open for days: look for a new version now and then
      if (registration)
        window.setInterval(() => void registration.update().catch(() => undefined), UPDATE_CHECK_MS);
    },
  });
}

/** True while the address carries an OIDC authorization response (`code`/`state`): the sign-in must not be interrupted. */
export function hasOidcCallback(search: string): boolean {
  const params = new URLSearchParams(search);
  return params.has('code') || params.has('state');
}

/** The update may be applied only when no form holds unsaved input and no sign-in callback is in the address. */
export function canApplyUpdate(isDirty: boolean, search: string): boolean {
  return !isDirty && !hasOidcCallback(search);
}

/**
 * Activates the waiting worker; the page reloads once when it takes control (workbox-window).
 * Returns false when the update was not applied (blocked, not available, or already applying).
 */
export async function applyUpdate(
  isDirty: boolean,
  search: string = window.location.search,
): Promise<boolean> {
  if (!needRefresh || applying || !updateServiceWorker || !canApplyUpdate(isDirty, search)) return false;
  applying = true;
  try {
    await updateServiceWorker(true);
  } catch {
    applying = false;
    return false;
  }
  return true;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** True when a new version is waiting. */
export function useUpdateAvailable(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => needRefresh,
    () => false,
  );
}

/** Test helper: resets module state. */
export function resetPwaForTests(): void {
  needRefresh = false;
  updateServiceWorker = null;
  applying = false;
}
