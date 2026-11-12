/**
 * Path prefixes on the app's own origin that belong to Keycloak and must never get the app shell: the sign-in and
 * sign-out redirects are navigations to them. `/realms/` is the local dev default (Keycloak on its own port serves
 * it, and a same-origin proxy may too). When the authority is on the same origin as the app (the stand serves Keycloak
 * under `/auth/`), the path of the authority up to `/realms/` is excluded as well. A cross-origin authority has no
 * same-origin path, so only the default stays.
 */
export function keycloakPathPrefixes(authority: string | undefined, appOrigin?: string): string[] {
  const prefixes = ['/realms/'];
  if (!authority) return prefixes;
  try {
    const url = new URL(authority, appOrigin ?? 'http://localhost');
    if (appOrigin !== undefined && url.origin !== new URL(appOrigin).origin) return prefixes;
    const path = url.pathname.endsWith('/') ? url.pathname : `${url.pathname}/`;
    const base = path.includes('/realms/') ? path.slice(0, path.indexOf('/realms/') + 1) : path;
    if (base !== '/' && !prefixes.includes(base)) prefixes.push(base);
    if (base !== '/' && !prefixes.includes(`${base}realms/`)) prefixes.push(`${base}realms/`);
  } catch {
    // an unparsable authority: keep the default
  }
  return prefixes;
}

/** Navigation denylist patterns for the given prefixes (each prefix and everything below it). */
export function keycloakDenylist(prefixes: readonly string[]): RegExp[] {
  return prefixes.map((prefix) => new RegExp(`^${prefix.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`));
}
