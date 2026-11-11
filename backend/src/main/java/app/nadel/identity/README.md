# identity

Who is calling. Validates Keycloak JWTs, resolves the member behind the token and turns that member's roles into
`PERM_*` authorities (ADR 0009). Keycloak says who the person is, which cooperative they belong to (`tenant_id` claim)
and whether they are the `PLATFORM_OPERATOR`; everything else is data in the database.

## Public API

| Type | Use |
|---|---|
| `Permission` | The fixed list of thirty-two permissions (`MEMBERS_INVITE` since feature 020); `authority()` is `PERM_<name>`. Check them with `@PreAuthorize("hasAuthority('PERM_...')")`, never role names |
| `AccessProfile` | Tenant, member id, Keycloak subject, display name, permissions, roles, operator flag. `operator(...)` is the platform operator; `ownerOnly(tenantId, memberId)` (feature 017) a member acting without a token (the cooperative's Telegram bot answering a linked member): no permissions, no roles, empty subject and display name |
| `RoleRef` | A role of a member (`baseCode` is set for Chairman, Treasurer, Member; `nameCustomized` is false while a base role still has its standard name, so clients show the catalog name, and true for a renamed base role and every custom role) |
| `CurrentUser` | Inject into controllers and services: `profile()`, `memberId()`, `can(permission)` |
| `AccessProfileSource` | SPI implemented by `members`. Keeps the dependency one-way: `members` -> `identity`, never the opposite |
| `OwnedPlotsSource`, `OwnedPlot` | SPI implemented by `plots`: the plots the signed-in member owns now, returned as `ownedPlots` by `GET /me` (empty for the platform operator). Keeps the dependency one-way: `plots` -> `identity` |
| `Roles.PLATFORM_OPERATOR` | The only role name left in code |
| `SignInAccounts`, `SignInAccount`, `SignInAccountChange`, `SignInAccountMissingException` | Port (feature 020) to the Keycloak accounts of members, used by `members` to invite them: `findByLogin`, `create`, `get`, `update` (`SignInAccountChange.enable()`, `disable()`, `newLogin(e-mail)`, `activated()`), `resetPassword`, `removeCredentials`, `logout`. Implemented by `KeycloakAdminClient` (below) |

## Authentication flow

`MemberAuthenticationConverter` replaces the default JWT converter:

1. Realm role `PLATFORM_OPERATOR`: operator token (`ROLE_PLATFORM_OPERATOR`, no permissions), **no database access**
   (ADR 0008).
2. No valid `tenant_id` claim (or no subject): 403 `MEMBER_NOT_LINKED`.
3. `TenantContext.runAs(tenant, () -> source.resolve(...))`: the active member with this Keycloak subject; otherwise,
   only for a verified e-mail, the active unlinked member with this e-mail (case-insensitive), which is then linked to
   the subject. A member linked to another subject is never relinked; archived members are never returned.
4. No member: 403 `MEMBER_NOT_LINKED`. Otherwise the permission union becomes the authorities.

Nothing is cached across requests, so a role change applies on the next request. A failure of the lookup itself is
answered as 500 `INTERNAL_ERROR`, not as a bad token: the converter wraps it in an `AuthenticationServiceException`, and
`SecurityConfiguration` sets the bearer token filter's failure handler not to rethrow it, so it reaches the entry point
(`MemberLookupFailureTests`). `MemberAuthenticationToken` extends `JwtAuthenticationToken`, so
`TenantContext.currentTenantId()` keeps reading the claim.

Security failures are written with `ProblemWriter`: 401 `UNAUTHENTICATED` (with `WWW-Authenticate`),
403 `MEMBER_NOT_LINKED`, 403 `PERMISSION_DENIED`.

Feature 020: `members` refuses a member whose stored access is `INVITED` (no password yet) or `DISABLED` in step 3,
whatever Keycloak says (403 `MEMBER_NOT_LINKED`). `/api/v1/public/**` is open without a token (`SecurityConfiguration`,
before `/api/**`): the set-password page of an invitation calls it with the invitation token in the body.
`SecurityConfiguration` (with method security) exists only in a servlet web application: the one-shot commands of the
profile `command` (`spring.main.web-application-type=none`, the first-chairman command in `members`) have no HTTP
surface.

`GET /me` is served here from the `AccessProfile`.

The web client calls `GET /me` first and builds navigation and route guards from `permissions`.

## Keycloak Admin API (feature 020)

`KeycloakAdminClient` implements `SignInAccounts` with the JDK `HttpClient` and Jackson (the pattern of the Telegram
client of `notifications`; no Keycloak library).

- **Service account.** The confidential client `nadel-backend-admin` of realm `nadel` (`infra/keycloak/nadel-realm.json`)
  has a service account with only `realm-management` `manage-users`, `view-users` and `query-users`, no realm role and
  `fullScopeAllowed: false`. It cannot read or change the realm, its clients or the `master` realm
  (`KeycloakServiceAccountTests`). The client never calls a role-mapping endpoint: cooperative roles live in the
  database.
- **Token.** `client_credentials` at `{url}/realms/{realm}/protocol/openid-connect/token` with HTTP Basic, kept until
  30 s before `expires_in`; an admin call answered 401 fetches a new token and is sent once more.
- **Calls** (`{url}/admin/realms/{realm}`): `GET /users?email=…&exact=true` and `?username=…&exact=true` (find by login),
  `POST /users` (user name and e-mail = the member's e-mail in lower case, names, enabled, e-mail not verified, attribute
  `tenant_id` = the bound cooperative, no password; a 409 looks the login up again), `GET /users/{id}`,
  `PUT /users/{id}` (the whole representation read before, with the changed user name/e-mail, `emailVerified`,
  `enabled`, `requiredActions`), `PUT /users/{id}/reset-password` (`temporary: false`), `GET /users/{id}/credentials`
  and `DELETE /users/{id}/credentials/{credentialId}` for each (remove every credential; a 404 on one is already gone),
  `POST /users/{id}/logout`.
- **Tenant guard.** Every change to an existing user reads it first and refuses with 409 `SIGN_IN_ACCOUNT_IN_USE` when
  its `tenant_id` attribute is not `TenantContext`'s cooperative.
- **Errors.** No connection, a timeout, a 5xx, a refused token (401/403) or any other unexpected answer: 503
  `SIGN_IN_SERVICE_UNAVAILABLE`, logged as the operation and the HTTP status or exception class only. A
  `reset-password` 400 with an `invalidPassword…` error: 400 `VALIDATION_FAILED`, field `password`, code
  `PASSWORD_POLICY` (realm policy `length(8)`). A 404 on a stored id: `get` is empty, a change throws
  `SignInAccountMissingException`. Bodies, URLs (a search URL holds the e-mail), the token and the secret are never
  logged.
- **Configuration** (`KeycloakAdminProperties`, `application.yml`): `nadel.keycloak.admin.url`
  (`NADEL_KEYCLOAK_ADMIN_URL`, `http://localhost:8180`; the stand uses `http://keycloak:8080/auth` inside its network),
  `.realm` (`NADEL_KEYCLOAK_REALM`, `nadel`), `.client-id` (`NADEL_KEYCLOAK_ADMIN_CLIENT_ID`, `nadel-backend-admin`),
  `.client-secret` (`NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET`), `.connect-timeout` (5 s), `.read-timeout` (10 s). The secret
  has a default only in the `dev` profile (the dev realm's `nadel-backend-admin-dev-secret`, used when the variable is
  not set at all); a blank or missing secret stops the start-up in every profile, so a stand never falls back to the
  dev value.
- **Tests.** `TestKeycloak` (test sources) runs `quay.io/keycloak/keycloak:26.8.1` once per test JVM with the realm
  file imported; only classes tagged `keycloak` (`KeycloakIntegrationTest`) use it. Every other Spring test gets the
  in-memory `FakeSignInAccounts` (`TestSecurityConfiguration`, property `nadel.test.keycloak` unset).
  `KeycloakAdminClientFailureTests` covers the error mapping and the token cache against a local HTTP server.

Depends on `problem` and `tenancy`.
