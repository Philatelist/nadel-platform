// @layer: unit
// @spec: 020-go-live-polish
// @regression
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = resolve(__dirname, '..', '..', '..');

describe.each(['uk', 'en'])('owner manual (%s): invitations', (lang) => {
  const html = readFileSync(resolve(root, 'docs', 'guide', lang, 'owner-manual.html'), 'utf8');

  it('explains the invitations client, where its secret lives and the first-chairman command', () => {
    expect(html).toContain('nadel-backend-admin');
    expect(html).toContain('NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET');
    expect(html).toContain('first-chairman.sh');
  });

  it('no longer tells the owner to create a member account by hand in the sign-in service', () => {
    // the only "Add user" left is the chairman's fallback when the command is not available
    expect(html.match(/Users → Add user/g)?.length ?? 0).toBeLessThanOrEqual(1);
    expect(html).not.toMatch(
      /(?:create|add|створіть|додайте)[^.<]{0,40}(?:member|член)[^.<]{0,40}(?:Keycloak|обліковий запис)/i,
    );
  });
});
