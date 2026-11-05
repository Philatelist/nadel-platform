import { WebStorageStateStore } from 'oidc-client-ts';
import type { AuthProviderProps } from 'react-oidc-context';

// Keycloak sign-in: Authorization Code + PKCE, public client nadel-web
export const OIDC_AUTHORITY = import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/nadel';
export const OIDC_CLIENT_ID = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'nadel-web';

export const oidcConfig: AuthProviderProps = {
  authority: OIDC_AUTHORITY,
  client_id: OIDC_CLIENT_ID,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: true,
  onSigninCallback: (user) => {
    const returnTo = returnPathOf(user?.state);
    if (returnTo) {
      // back to the page the visitor signed in from (the public help); a reload keeps the stored session and drops ?code
      window.location.replace(returnTo);
      return;
    }
    // remove ?code=…&state=… from the address bar after sign-in
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};

/** The `returnTo` path carried through the sign-in redirect; only a same-origin absolute path is accepted. */
export function returnPathOf(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('returnTo' in state)) return null;
  const { returnTo } = state;
  return typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//')
    ? returnTo
    : null;
}
