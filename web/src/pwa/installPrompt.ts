import { useSyncExternalStore } from 'react';

// The browser fires `beforeinstallprompt` once, early; keep it from module load until the account page asks for it.
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export const INSTALL_STORAGE_KEY = 'nadel.install.v1';

export type InstallOffer = 'hidden' | 'prompt' | 'ios';

let deferred: BeforeInstallPromptEvent | null = null;
let version = 0;
const listeners = new Set<() => void>();

function emit(): void {
  version += 1;
  for (const listener of listeners) listener();
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    deferred = event as BeforeInstallPromptEvent;
    emit();
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    emit();
  });
}

/** Safari on iPhone/iPad only: other iOS browsers (Chrome, Firefox, Edge) cannot add to the home screen. */
export function isIosSafari(userAgent: string, maxTouchPoints = 0): boolean {
  const ios = /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && maxTouchPoints > 1);
  return ios && /Safari/.test(userAgent) && !/CriOS|FxiOS|EdgiOS|OPiOS|OPT\//.test(userAgent);
}

export interface InstallContext {
  standalone: boolean;
  dismissed: boolean;
  hasPrompt: boolean;
  userAgent: string;
  maxTouchPoints?: number;
}

/** What the account page shows: the browser's install prompt, the iOS steps, or nothing. */
export function installOffer(context: InstallContext): InstallOffer {
  if (context.standalone || context.dismissed) return 'hidden';
  if (context.hasPrompt) return 'prompt';
  if (isIosSafari(context.userAgent, context.maxTouchPoints)) return 'ios';
  return 'hidden';
}

export function isStandalone(): boolean {
  const iosStandalone = (navigator as Navigator & { standalone?: boolean }).standalone === true;
  return (
    iosStandalone ||
    (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches)
  );
}

function isDismissed(): boolean {
  try {
    return window.localStorage.getItem(INSTALL_STORAGE_KEY) === 'dismissed';
  } catch {
    return false;
  }
}

/** «Do not show again»: remembered on this device (kept at sign-out). */
export function dismissInstallOffer(): void {
  try {
    window.localStorage.setItem(INSTALL_STORAGE_KEY, 'dismissed');
  } catch {
    // storage unavailable: the offer comes back next time
  }
  emit();
}

/** Opens the browser's own install dialog; the saved event is single-use. */
export async function promptInstall(): Promise<void> {
  const event = deferred;
  if (!event) return;
  deferred = null;
  emit();
  await event.prompt();
  await event.userChoice;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useInstallOffer(): InstallOffer {
  useSyncExternalStore(
    subscribe,
    () => version,
    () => 0,
  );
  return installOffer({
    standalone: isStandalone(),
    dismissed: isDismissed(),
    hasPrompt: deferred !== null,
    userAgent: navigator.userAgent,
    maxTouchPoints: navigator.maxTouchPoints,
  });
}
