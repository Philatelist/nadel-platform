import type { PushDevice, PushSubscriptionInput } from '@nadel/api-client';

/** Browser side of «Notifications on this device». The push address and keys are never logged. */
export const DEVICE_KEY = 'nadel.push.device.v1';

export type PushState = 'unsupported' | 'ios-needs-install' | 'denied' | 'off' | 'on' | 'busy';

export function storedDeviceId(): string | null {
  try {
    return window.localStorage.getItem(DEVICE_KEY);
  } catch {
    return null;
  }
}

function storeDeviceId(id: string | null): void {
  try {
    if (id) window.localStorage.setItem(DEVICE_KEY, id);
    else window.localStorage.removeItem(DEVICE_KEY);
  } catch {
    // not persisted; the next visit reconciles with the server
  }
}

function isIos(): boolean {
  const ua = navigator.userAgent;
  return /iPad|iPhone|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
}

function isStandalone(): boolean {
  const nav = navigator as Navigator & { standalone?: boolean };
  return nav.standalone === true || window.matchMedia?.('(display-mode: standalone)').matches === true;
}

/** `unsupported`, `ios-needs-install` or `null` when the browser can subscribe. */
export function blockingState(): 'unsupported' | 'ios-needs-install' | null {
  if (isIos() && !isStandalone()) return 'ios-needs-install';
  if (
    typeof Notification === 'undefined' ||
    !('serviceWorker' in navigator) ||
    typeof PushManager === 'undefined'
  ) {
    return 'unsupported';
  }
  return null;
}

/** A short name the member recognises, for example «Android · Chrome». */
export function deviceLabel(ua: string = navigator.userAgent): string {
  const system = /Android/i.test(ua)
    ? 'Android'
    : /iPhone|iPad|iPod/i.test(ua)
      ? 'iOS'
      : /Windows/i.test(ua)
        ? 'Windows'
        : /Mac OS X|Macintosh/i.test(ua)
          ? 'macOS'
          : /Linux/i.test(ua)
            ? 'Linux'
            : '';
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /OPR\/|Opera/.test(ua)
      ? 'Opera'
      : /Firefox\/|FxiOS/.test(ua)
        ? 'Firefox'
        : /Chrome\/|CriOS/.test(ua)
          ? 'Chrome'
          : /Safari\//.test(ua)
            ? 'Safari'
            : '';
  return [system, browser].filter(Boolean).join(' · ').slice(0, 80);
}

export function decodeKey(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(base64Url.length / 4) * 4, '=');
  const raw = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function sameKey(a: ArrayBuffer | null, b: Uint8Array): boolean {
  if (!a) return false;
  const left = new Uint8Array(a);
  return left.length === b.length && left.every((value, i) => value === b[i]);
}

async function currentSubscription(): Promise<PushSubscription | null> {
  const registration = await navigator.serviceWorker.getRegistration();
  return registration ? ((await registration.pushManager.getSubscription()) ?? null) : null;
}

async function dropSubscription(subscription: PushSubscription | null): Promise<void> {
  try {
    await subscription?.unsubscribe();
  } catch {
    // an already dropped subscription is what we want
  }
}

/**
 * Initial state with reconciliation: a subscription made with another server key is dropped, and so is one whose device
 * the server no longer lists. `devices` come from the server; `null` means the list could not be read (keep as is).
 */
export async function reconcile(
  publicKey: string,
  devices: readonly PushDevice[] | null,
): Promise<PushState> {
  const blocked = blockingState();
  if (blocked) return blocked;
  // the dev server has no worker; without a registration there is nothing to subscribe with
  const registration = await navigator.serviceWorker.getRegistration();
  if (!registration) return 'unsupported';
  const subscription = (await registration.pushManager.getSubscription()) ?? null;
  const id = storedDeviceId();
  if (subscription) {
    if (!sameKey(subscription.options.applicationServerKey, decodeKey(publicKey))) {
      await dropSubscription(subscription);
      storeDeviceId(null);
    } else if (devices && (!id || !devices.some((device) => device.id === id))) {
      await dropSubscription(subscription);
      storeDeviceId(null);
    } else {
      return 'on';
    }
  } else if (id) {
    storeDeviceId(null);
  }
  return Notification.permission === 'denied' ? 'denied' : 'off';
}

export interface PushApi {
  register: (input: PushSubscriptionInput) => Promise<PushDevice>;
  remove: (id: string) => Promise<unknown>;
}

/** Turn on. Call it from the click handler: the permission prompt needs the user gesture. Returns the new state. */
export async function turnOn(api: PushApi, publicKey: string, language: 'uk' | 'en'): Promise<PushState> {
  const permission = await Notification.requestPermission();
  if (permission === 'denied') return 'denied';
  if (permission !== 'granted') return 'off';
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: decodeKey(publicKey),
  });
  const json = subscription.toJSON();
  try {
    const device = await api.register({
      endpoint: subscription.endpoint,
      keys: { p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' },
      label: deviceLabel(),
      language,
    });
    storeDeviceId(device.id);
    return 'on';
  } catch (error) {
    await dropSubscription(subscription);
    throw error;
  }
}

/** Turn off: the server first, then the browser; the local id is cleared in every case. */
export async function turnOff(api: PushApi): Promise<PushState> {
  const id = storedDeviceId();
  try {
    if (id) await api.remove(id);
  } finally {
    // the subscription is dropped even when the server call failed; the next visit reconciles
    await dropSubscription(await currentSubscription().catch(() => null));
    storeDeviceId(null);
  }
  return Notification.permission === 'denied' ? 'denied' : 'off';
}

/** Sign-out clean-up: delete this device while the token is valid, then unsubscribe. Never throws. */
export async function signOutCleanup(remove: (id: string) => Promise<unknown>): Promise<void> {
  const id = storedDeviceId();
  if (!id || typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
  try {
    await remove(id);
  } catch {
    // the server drops dead subscriptions by itself; signing out must not wait
  }
  await dropSubscription(await currentSubscription().catch(() => null));
  storeDeviceId(null);
}
