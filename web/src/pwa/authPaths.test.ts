import { describe, expect, it } from 'vitest';
import { keycloakDenylist, keycloakPathPrefixes } from './authPaths';

describe('keycloakPathPrefixes', () => {
  it('keeps /realms/ for the local default and no authority', () => {
    expect(keycloakPathPrefixes(undefined)).toEqual(['/realms/']);
    expect(keycloakPathPrefixes('http://localhost:8180/realms/nadel')).toEqual(['/realms/']);
  });

  it('adds the /auth/ base when Keycloak is served under it on the app origin', () => {
    const p = keycloakPathPrefixes('https://nadel.example/auth/realms/nadel', 'https://nadel.example');
    expect(p).toEqual(['/realms/', '/auth/', '/auth/realms/']);
  });

  it('adds nothing for a cross-origin authority', () => {
    expect(keycloakPathPrefixes('https://sso.example/auth/realms/nadel', 'https://nadel.example')).toEqual([
      '/realms/',
    ]);
  });

  it('survives a garbage authority', () => {
    expect(keycloakPathPrefixes('http://[bad', 'https://nadel.example')).toEqual(['/realms/']);
  });
});

describe('keycloakDenylist', () => {
  it('matches the sign-in and sign-out paths but not app routes', () => {
    const list = keycloakDenylist(keycloakPathPrefixes('http://x.test/auth/realms/nadel', 'http://x.test'));
    const hit = (path: string) => list.some((re) => re.test(path));
    expect(hit('/auth/realms/nadel/protocol/openid-connect/auth')).toBe(true);
    expect(hit('/auth/realms/nadel/protocol/openid-connect/logout')).toBe(true);
    expect(hit('/realms/nadel/x')).toBe(true);
    expect(hit('/account')).toBe(false);
    expect(hit('/authors')).toBe(false);
  });
});
