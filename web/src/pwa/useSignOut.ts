import { useCallback } from 'react';
import { useAuth } from 'react-oidc-context';

const CLEANUP_CAP_MS = 3000;

/** Workbox keeps its precache under a name containing `-precache-`; every other cache must not outlive a user. */
export function isPrecacheName(name: string): boolean {
  return name.includes('-precache-');
}

/** Deletes every cache except the Workbox precache (static files only). */
export async function deleteUserCaches(
  storage: Pick<CacheStorage, 'keys' | 'delete'> | undefined,
): Promise<void> {
  if (!storage) return;
  const names = await storage.keys();
  await Promise.allSettled(names.filter((name) => !isPrecacheName(name)).map((name) => storage.delete(name)));
}

export interface SignOutSteps {
  /** Device clean-up steps that run together (caches now; the push slice adds the device and the subscription). */
  cleanups: Array<() => Promise<unknown>>;
  clearSession: () => void;
  signoutRedirect: () => Promise<unknown>;
  capMs?: number;
}

/**
 * Clean-up first, then the redirect: every clean-up runs with allSettled under a time cap, so a failure or a hang never
 * blocks signing out. `sessionStorage` is cleared after the clean-ups and right before the redirect.
 */
export async function signOutCleanly(steps: SignOutSteps): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const cap = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, steps.capMs ?? CLEANUP_CAP_MS);
  });
  try {
    await Promise.race([Promise.allSettled(steps.cleanups.map((step) => step())), cap]);
  } finally {
    clearTimeout(timer);
  }
  try {
    steps.clearSession();
  } finally {
    await steps.signoutRedirect();
  }
}

/** Sign-out with device clean-up; used by the header button and the full-page messages. */
export function useSignOut(): () => Promise<void> {
  const auth = useAuth();
  const { signoutRedirect, user } = auth;
  return useCallback(async () => {
    // sessionStorage holds the sign-in; keep the id token for the redirect so Keycloak ends the session without a prompt
    const idToken = user?.id_token;
    await signOutCleanly({
      // extension point: the push slice adds «delete this device on the server» and «unsubscribe» here
      cleanups: [() => deleteUserCaches(typeof caches === 'undefined' ? undefined : caches)],
      clearSession: () => window.sessionStorage.clear(),
      signoutRedirect: () => signoutRedirect(idToken ? { id_token_hint: idToken } : undefined),
    });
  }, [signoutRedirect, user]);
}
