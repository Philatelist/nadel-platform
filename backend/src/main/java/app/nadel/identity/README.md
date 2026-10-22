# identity

Who is calling. Validates Keycloak JWTs, resolves the member behind the token and turns that member's roles into
`PERM_*` authorities (ADR 0009). Keycloak says who the person is, which cooperative they belong to (`tenant_id` claim)
and whether they are the `PLATFORM_OPERATOR`; everything else is data in the database.

## Public API

| Type | Use |
|---|---|
| `Permission` | The fixed list of fifteen permissions; `authority()` is `PERM_<name>`. Check them with `@PreAuthorize("hasAuthority('PERM_...')")`, never role names |
| `AccessProfile` | Tenant, member id, Keycloak subject, display name, permissions, roles, operator flag |
| `RoleRef` | A role of a member (`baseCode` is set for Chairman, Treasurer, Member; `nameCustomized` is false while a base role still has its standard name, so clients show the catalog name, and true for a renamed base role and every custom role) |
| `CurrentUser` | Inject into controllers and services: `profile()`, `memberId()`, `can(permission)` |
| `AccessProfileSource` | SPI implemented by `members`. Keeps the dependency one-way: `members` -> `identity`, never the opposite |
| `OwnedPlotsSource`, `OwnedPlot` | SPI implemented by `plots`: the plots the signed-in member owns now, returned as `ownedPlots` by `GET /me` (empty for the platform operator). Keeps the dependency one-way: `plots` -> `identity` |
| `Roles.PLATFORM_OPERATOR` | The only role name left in code |

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

`GET /me` is served here from the `AccessProfile`.

The web client calls `GET /me` first and builds navigation and route guards from `permissions`.

Depends on `problem` and `tenancy`.
