# 0003. Keycloak for sign-in and roles

**Status:** accepted

## Context
We need sign-in by email or phone, password reset, invitations, 2FA for admins, passkeys, roles and mobile sign-in with PKCE. Building this ourselves is expensive and risky.

## Decision
Keycloak 26, realm `nadel`. Clients `nadel-web` and `nadel-mobile` (public + PKCE). The backend is a resource server that validates JWTs.
Roles are realm roles. Cooperative membership is the user attribute `tenant_id`, mapped into the token by a protocol mapper.

## Consequences
- Standard OIDC: the provider can be replaced without rewriting clients.
- A person who belongs to several cooperatives currently needs a separate account per cooperative. Later we move to Keycloak Organizations with cooperative selection at sign-in (separate ADR).
