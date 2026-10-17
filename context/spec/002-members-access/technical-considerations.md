# Technical Specification: Members and Access Control

- **Functional Specification:** [functional-spec.md](functional-spec.md) (source: `docs/features/002-members-access.md`)
- **Status:** Completed
- **Author(s):** Alex Usov (drafted by the feature pipeline from spring-backend, postgres-database, react-frontend and devops-infra design input)

---

## 1. High-Level Technical Approach

Implements ADR 0009 on top of the phase 0 foundation:

- **Backend** gains two modules, `members` (members, roles, role permissions, member roles) and `audit`
  (append-only audit log fed by domain events over the Spring Modulith outbox), plus a tiny `problem` module for
  Problem Details. `identity` owns the `Permission` enum and the authentication flow; it resolves a signed-in
  person to a member through an SPI implemented by `members`, so dependencies stay one-way.
- **Authorization** is `PERM_*` authorities resolved from the database on every request, checked with
  `@PreAuthorize`. Keycloak keeps identity, the `tenant_id` claim and `PLATFORM_OPERATOR` only.
- **Database**: Flyway V2 (members and roles, seed function) and V3 (audit entry, event publication), all tenant
  tables under RLS; demo data extended.
- **Contract first**: `api/openapi.yaml` gets `/me` changes, `/members*`, `/permissions`, `/roles*`, `/audit`, a
  richer `Problem`, shared error responses and paging; `make gen` regenerates the client.
- **Web**: My account, Members list and card, Roles, Audit log, permission-driven navigation and route guard,
  language switcher; map leaves navigation.
- **Infra/CI**: realm loses cooperative realm roles; compose Keycloak healthcheck; Playwright E2E job in CI and
  `make e2e`.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module layout and dependencies

```
members --> identity --> problem          audit --> tenancy, problem
members --> audit        identity --> tenancy
```

No cycles; `identity` never depends on `members` (dependency inversion through `AccessProfileSource`).

| Module | Public API (root package) | Internal |
|---|---|---|
| `identity` | `Permission` enum (8 values, `authority()` = `PERM_<name>`), `AccessProfile` record (tenantId, memberId, subject, displayName, permissions, roles, platformOperator), `RoleRef`, `CurrentUser` (inject into controllers), SPI `AccessProfileSource`, `Roles.PLATFORM_OPERATOR` only | `MemberAuthenticationConverter`, `MemberAuthenticationToken extends JwtAuthenticationToken` (keeps `TenantContext.currentTenantId()` working, `TenantContext.java:32`), entry point + access-denied handler, `SecurityConfiguration`, `MeController` |
| `members` | nothing yet (004/005 will add a lookup API) | entities `Member`, `Role`, `RolePermission`, `MemberRole`; `PhoneNumber`, `DeliveryChannel`, `LastChairmanGuard`, `PermissionUnion`, `MemberSnapshot` (diff); services; `MembersController`, `RolesController`, `PermissionsController`; `AccessProfileSource` implementation |
| `audit` | `AuditEvent` record, `FieldChange`, `Actor` | `AuditListener`, `AuditEntry`, repository, `AuditController` |
| `problem` | `ApiProblemException(status, code, detail, List<FieldProblem>)`, `ProblemWriter` (servlet response writer) | `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` |

- Each new module gets `package-info.java` with `@ApplicationModule` and a module README (as the existing ones).
- `@EnableAsync` is added to `NadelApplication` (needed for module listeners).
- Code naming is **Member** (glossary updated from `Person`; ADR 0009's `person_role` becomes `member_role`).

### 2.2 Authentication flow

`MemberAuthenticationConverter` replaces `SecurityConfiguration.jwtAuthenticationConverter()` (`SecurityConfiguration.java:46-64`):

1. Realm role `PLATFORM_OPERATOR` → token with `ROLE_PLATFORM_OPERATOR`, operator profile, **no DB access** (ADR 0008).
2. No `tenant_id` claim → `MemberNotLinkedException` (an `AuthenticationException`).
3. `TenantContext.runAs(tenantId, () -> source.resolve(tenantId, sub, email, emailVerified))`; `resolve` is
   `@Transactional` (read-write) in `members`, so `TenantAwareTransactionManager.doBegin` sets `app.tenant_id`.
4. Resolution: (a) active member with this `keycloak_subject`; else (b) active member with
   `lower(email) = lower(jwt.email)` and `keycloak_subject is null`, only when `email_verified` is true → set
   `keycloak_subject` with a conditional update; (c) an email match on a member linked to another subject is refused.
   A unique violation from a concurrent first call is retried as a lookup by subject. Archived members are never
   returned.
5. Empty → `MemberNotLinkedException`. Otherwise authorities = `PERM_*` of the permission union.

- `oauth2ResourceServer` gets a custom `AuthenticationEntryPoint` (`MemberNotLinkedException` → 403
  `MEMBER_NOT_LINKED`; others → 401 `UNAUTHENTICATED` keeping `WWW-Authenticate`) and `AccessDeniedHandler` (403
  `PERMISSION_DENIED`), both via `ProblemWriter`.
- No cross-request cache: a permission change applies on the next request. One or two indexed queries per request.
- Personal data (email, names) is never logged on linking failure; log the tenant id and subject only.

### 2.3 Authorization rules

| Endpoint | Check |
|---|---|
| `GET /me` | authenticated (works for the operator) |
| `GET /members` | `PERM_MEMBERS_VIEW` else 403 |
| `GET /members/{id}` | authenticated; own id → card; else without `MEMBERS_VIEW` → 404 **before any lookup**; else lookup (RLS hides other cooperatives → 404) |
| `POST /members`, `PUT /members/{id}`, `POST /members/{id}/archive`, `POST /members/{id}/restore` | `PERM_MEMBERS_EDIT` |
| `GET /permissions`, `GET /roles`, `POST /roles`, `PUT /roles/{id}`, `DELETE /roles/{id}`, `PUT /members/{id}/roles` | `PERM_ROLES_MANAGE` |
| `GET /audit` | `PERM_AUDIT_VIEW` |

- `@PreAuthorize` on controller methods (class proxies work with generated interfaces). Writes return 403 before
  looking at the id.
- `notes` is filled only by `MemberDtoMapper.toDto(member, viewer)` when the viewer has `MEMBERS_VIEW`; the key is
  absent otherwise. **Assumption:** on `PUT` by a caller without `MEMBERS_VIEW`, `notes` in the body is ignored and
  the stored value kept.
- The operator has no `PERM_*`, so every cooperative endpoint returns 403 for them.

### 2.4 Domain rules

- **PhoneNumber** value object: strip spaces, dashes, brackets, dots; `0` + 9 digits → `+380…`; `380` + 9 digits →
  `+380…`; `+` + 8–15 digits kept; anything else → `PHONE_INVALID`. Duplicates removed; at most 10 phones
  (**assumption**). Same type for the Viber phone. No library (no new dependency).
- **DeliveryChannel**: EMAIL needs `email`, TELEGRAM needs `telegramUsername`, VIBER needs `viberPhone`; failure →
  400 `VALIDATION_FAILED` with `errors[{field, code: CHANNEL_CONTACT_MISSING}]`.
- **Email**: trimmed, lower-cased; unique among active members per tenant (pre-check + partial unique index →
  409 `EMAIL_TAKEN` with `errors[{field: email}]`); restore re-checks.
- **PermissionUnion**: union of role permissions; a role with `base_code = CHAIRMAN` yields all `Permission` values in
  code regardless of rows, so a later permission needs no migration for the chairman.
- **Base roles**: rename/delete and editing Chairman's permissions → 409 `BASE_ROLE_IMMUTABLE`. Treasurer and Member
  permissions are editable with `ROLES_MANAGE`.
- **Role name**: trimmed, 1–60 characters, unique per tenant case-insensitively → 409 `ROLE_NAME_TAKEN`.
- **Role delete**: refused while assigned → 409 `ROLE_IN_USE` (FK `ON DELETE RESTRICT` backstop).
- **LastChairmanGuard**: for removing Chairman, archiving a chairman, replacing a chairman's roles. Takes
  `pg_advisory_xact_lock(hashtextextended(tenant_id::text, 0))` (parameterised native query inside the transaction)
  then counts active chairmen; violation → 409 `LAST_CHAIRMAN`.

### 2.5 Data model (Flyway; never edit V1)

**V2__members_and_roles.sql**

| Table | Key columns | Constraints / indexes |
|---|---|---|
| `member` | `id uuid` PK, `tenant_id`, `last_name`, `first_name`, `middle_name`, `phones text[] not null default '{}'`, `email`, `postal_address`, `delivery_channel text default 'PAPER'`, `telegram_username`, `viber_phone`, `notes`, `keycloak_subject`, `archived_at`, `created_at`, `updated_at` | CHECKs: non-blank names, channel in 4 values, channel→contact backstop, `email = lower(btrim(email))`; unique `(tenant_id, lower(email)) where archived_at is null and email is not null`; unique `(tenant_id, keycloak_subject) where keycloak_subject is not null`; `(tenant_id, lower(last_name), lower(first_name))`; GIN `phones`; `unique (tenant_id, id)` |
| `role` | `id`, `tenant_id`, `base_code` (CHAIRMAN/TREASURER/MEMBER, null for custom), `name`, `description`, `created_at`, `updated_at` | unique `(tenant_id, lower(name))`; unique `(tenant_id, base_code) where base_code is not null`; `unique (tenant_id, id)` |
| `role_permission` | `tenant_id`, `role_id`, `permission text` | PK `(role_id, permission)`; FK `(tenant_id, role_id)` → role ON DELETE CASCADE; no CHECK on `permission` (Java enum is the source of truth) |
| `member_role` | `tenant_id`, `member_id`, `role_id`, `assigned_at` | PK `(member_id, role_id)`; FKs `(tenant_id, member_id)`, `(tenant_id, role_id)` (RESTRICT); index on `role_id` |

- Every table: `tenant_id uuid not null` FK `tenant`, RLS enabled, policy `tenant_isolation` (USING and WITH CHECK
  `tenant_id = current_tenant_id()`), index on `tenant_id`.
- Composite FKs make cross-tenant links impossible.
- `seed_base_roles(p_tenant uuid)`: not SECURITY DEFINER; deterministic ids `md5(tenant || ':' || code)::uuid`;
  inserts the three base roles and their permissions (Chairman: all eight rows, for display; Treasurer:
  MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW; Member: none) with `ON CONFLICT DO NOTHING` (never overwrites
  customisations); EXECUTE granted to `nadel_app`. V2 calls it for existing tenants (no-op on a fresh DB).
- **Adding a permission later:** enum value + migration that inserts `role_permission` for the base roles that
  should get it and `CREATE OR REPLACE FUNCTION seed_base_roles` with the new matrix; a test compares the DB matrix
  with the Java base-role definition.

**V3__audit_and_event_publication.sql**

| Table | Key columns | Notes |
|---|---|---|
| `audit_entry` | `id uuid` PK (= event id, idempotency), `tenant_id`, `occurred_at`, `actor_member_id` (no FK), `actor_subject`, `action`, `entity_type`, `entity_id`, `entity_label`, `changes jsonb not null` (`[{field, old, new}]`) | RLS + `tenant_isolation`; indexes `(tenant_id, occurred_at desc)`, `(tenant_id, entity_type, entity_id)`, `(tenant_id, actor_member_id)`; `REVOKE UPDATE, DELETE, TRUNCATE ON audit_entry FROM nadel_app` right after creation (V1 default privileges grant them) |
| `event_publication` | Spring Modulith 2.1.1 JPA schema, copied from `schema-postgresql.sql` in `spring-modulith-events-jpa-2.1.1.jar` | **Deliberate exception: no `tenant_id`, no RLS** — infrastructure rows that resubmission at startup must see without a tenant. Documented in the migration comment and an ADR 0007 note. A guard test asserts no RLS and that `nadel_app` can complete a publication with no tenant set. |

**Demo data** (`R__demo_data.sql`, repeatable, English per CLAUDE.md, so names are transliterated Ukrainian):
`seed_base_roles` for both tenants; about 35 members in the demo tenant with fixed UUIDs and `ON CONFLICT (id) DO
UPDATE`; members whose emails equal the Keycloak users (chairman, treasurer, member, electrician @demo.local;
member@other.local in the other tenant, plus one more member there); custom roles Secretary (MEMBERS_VIEW,
MEMBERS_EDIT, PLOTS_VIEW, PLOTS_EDIT, OWNERSHIP_EDIT) assigned to one regular member and Electrician (no
permissions) assigned with Member to the electrician user; a mix of channels, one archived member, one without
email, one with two phones; `keycloak_subject` left null. No seeded audit rows (system changes are not audited).

### 2.6 Domain events and audit

- `AuditEvent(eventId, tenantId, occurredAt, Actor(memberId, subject, displayName), action, entityType, entityId,
  entityLabel, List<FieldChange(field, oldValue, newValue)>)`. Values are strings; multi-valued fields (phones, role
  names) are sorted and joined. Actions: `MEMBER_CREATED`, `MEMBER_UPDATED`, `MEMBER_ARCHIVED`, `MEMBER_RESTORED`,
  `MEMBER_ROLES_CHANGED`, `ROLE_CREATED`, `ROLE_UPDATED`, `ROLE_DELETED`. Entity types `MEMBER`, `ROLE`.
- Services build the event inside their `@Transactional` method from `MemberSnapshot.diff(before, after)` and publish
  it with `ApplicationEventPublisher`; the publication is stored in the same transaction.
- **Listener and tenant context:** the async listener thread has no security context, and a `@Transactional`
  listener begins its transaction before the body runs. The listener therefore wraps its work as
  `TenantContext.runAs(event.tenantId(), () -> transactionTemplate.execute(...))`. Preferred form:
  `@Async @TransactionalEventListener` without `@Transactional` (option A); if the publication registry does not
  mark completion for that form, use `@ApplicationModuleListener` with an inner `REQUIRES_NEW` template (option B).
  **Assumption:** verified by a spike test in the audit slice; the chosen form is recorded in the module README.
- Idempotent insert: `insert ... on conflict (id) do nothing` (parameterised native query).
- Dependencies/config: `org.springframework.modulith:spring-modulith-starter-jpa` (BOM 2.1.1);
  `spring.modulith.events.republish-outstanding-events-on-restart: true`; completion mode `delete` so personal data
  does not linger in the RLS-less table (exact property name verified against 2.1.1); Modulith schema
  initialisation off (Flyway owns it).

### 2.7 Problem Details

`application/problem+json` with `type`, `title`, `status`, `detail` (English, no personal data), `code`, optional
`errors[{field, code, message}]`. The advice overrides `handleMethodArgumentNotValid` and
`handleHandlerMethodValidationException`, handles `ApiProblemException`, `AccessDeniedException`, and a catch-all 500
without internals.

| Code | Status |
|---|---|
| `VALIDATION_FAILED` (field codes `REQUIRED`, `TOO_LONG`, `INVALID`, `PHONE_INVALID`, `CHANNEL_CONTACT_MISSING`) / `MALFORMED_REQUEST` | 400 |
| `UNAUTHENTICATED` | 401 |
| `MEMBER_NOT_LINKED`, `PERMISSION_DENIED` | 403 |
| `NOT_FOUND` | 404 |
| `EMAIL_TAKEN`, `ROLE_NAME_TAKEN`, `ROLE_IN_USE`, `LAST_CHAIRMAN`, `BASE_ROLE_IMMUTABLE` | 409 |

### 2.8 API contract (`api/openapi.yaml`)

Explicit `operationId`s so generated hook names are stable. Tags: `me`, `members`, `roles`, `permissions`, `audit`.

| Method & path | operationId | Request | Response |
|---|---|---|---|
| `GET /me` | `getMe` | — | `Me {userId, email, fullName, memberId?, platformOperator, tenant?, roles: RoleRef[], permissions: Permission[]}` |
| `GET /members` | `listMembers` | `q?`, `includeArchived=false`, `page=0`, `size=20 (max 100)` | `MemberPage {items: MemberSummary[], page, size, totalItems, totalPages}` |
| `GET /members/{id}` | `getMember` | — | `Member` |
| `POST /members` | `createMember` | `MemberInput` | 201 `Member` |
| `PUT /members/{id}` | `updateMember` | `MemberInput` | `Member` |
| `POST /members/{id}/archive` / `restore` | `archiveMember` / `restoreMember` | — | `Member` |
| `PUT /members/{id}/roles` | `setMemberRoles` | `{roleIds: uuid[]}` | `Member` |
| `GET /permissions` | `listPermissions` | — | `PermissionInfo[] {code, name, description}` |
| `GET /roles` | `listRoles` | — | `RoleDetails[] {id, name, description, baseCode?, permissions[], memberCount}` |
| `POST /roles` / `PUT /roles/{id}` | `createRole` / `updateRole` | `RoleInput {name, description?, permissions[]}` | `RoleDetails` |
| `DELETE /roles/{id}` | `deleteRole` | — | 204 |
| `GET /audit` | `listAuditEntries` | `entityType?`, `entityId?`, `actorMemberId?`, `from?`, `to?` (dates, inclusive, Europe/Kyiv), `page`, `size=50` | `AuditPage {items: AuditEntry[], …}` |

- Schemas: `Member` (all fields; `notes` optional), `MemberSummary` (name, phones, email, channel, roles, archived),
  `MemberInput` (Bean Validation: required names, `maxLength` 100/500/2000, `email` format, `maxItems` 10 phones),
  enums `Permission`, `DeliveryChannel`, `AuditAction`, `AuditEntityType`, `BaseRole`; `Problem` with `code` and
  `errors`; shared responses `BadRequest`, `Unauthorized`, `Forbidden`, `NotFound`, `Conflict`. The `Role` enum is
  replaced by `RoleRef {id, name, baseCode?}`.
- Search `q`: case-insensitive substring of last/first/middle name or email, or digits contained in a phone.

### 2.9 Web (`web/`, `packages/shared`, `packages/api-client`)

- **api-client**: `src/index.ts` exports the new tag files (`generated/members/members`, `roles/roles`,
  `permissions/permissions`, `audit/audit`); generated output committed after `make gen`.
- **Routing** (`App.tsx`): `/` → `/account`; `/account` `AccountPage` (replaces `CabinetPage`); `/members`,
  `/members/new`, `/members/:id`; `/roles`; `/audit`; `*` `NotFoundPage`. Map route and nav item removed (MapPage file
  kept for phase 6). Pages lazy-loaded with named exports.
- **Permission context** `web/src/me.tsx`: `MeProvider` (loading, error, 403 `MEMBER_NOT_LINKED` → full-page "not
  linked" with sign-out, operator → "no cooperative"), `useMe()`, `useCan(permission)`.
- **Guards**: `RequirePermission` layout route renders `NoAccess` in place; API 404 renders `NotFoundPage`.
- **Components**: `QueryBoundary` (loading/empty/error with retry), `Pagination`, `Field` + inputs with
  `aria-invalid`, `PageHeader`, `NoAccess`, `Banner`, `ConfirmDialog`, `MemberDetails` (shared by card and account),
  `LocaleSwitch` (I18nProvider holds locale state; persisted in localStorage inside try/catch).
- **Forms**: controlled state; client checks only blank names; server `errors[]` mapped to fields by `field`
  (array prefix match for `phones[i]`), messages by `code` from i18n; unknown codes → generic text; server text never
  shown. TanStack Query `retry` skips 4xx.
- **List/audit pages**: filters and page in URL search params, debounced search via a `useDebouncedValue` hook,
  `keepPreviousData`.
- **i18n** (`packages/shared/src/i18n/en.ts` then `uk.ts`): groups `nav`, `state`, `access`, `pagination`,
  `account`, `members`, `member.fields`, `channel`, `permission.<CODE>.{name,description}`, `rolesPage`, `audit`
  (actions, entity types), `validation.<CODE>`, `problem.<CODE>`; `roles` keeps CHAIRMAN, TREASURER, MEMBER,
  PLATFORM_OPERATOR (ELECTRICIAN removed). `format(template, vars)` helper in `@nadel/shared` with a unit test.
  Base role names are localised by `baseCode`; custom role names shown as stored.
- No new runtime dependencies.

### 2.10 Infra, CI and E2E

- **Realm** (`infra/keycloak/nadel-realm.json`): remove realm roles CHAIRMAN, TREASURER, ELECTRICIAN, MEMBER and their
  user assignments; keep PLATFORM_OPERATOR; users keep email, `emailVerified: true`, `tenant_id`. Local realm changes
  need `docker compose down -v` (documented).
- **Compose**: Keycloak healthcheck (management port `/health/ready`, `KC_HEALTH_ENABLED=true`) so `up --wait` means
  ready.
- **Playwright** (`web/playwright.config.ts`, `web/e2e/*.spec.ts`, `web/e2e/support/login.ts`, `web/e2e/tsconfig.json`):
  chromium only, baseURL `http://localhost:5173`, `locale: 'en-US'`, workers 1, retries 1 in CI, trace/screenshot on
  failure; login helper fills the Keycloak form (`#username`, `#password`, `#kc-login`); password from
  `E2E_PASSWORD` defaulting to the dev value. Specs: treasurer opens Members and a card (no edit controls); member has
  no Members nav item and `/members` shows "no access". Dev dependencies `@playwright/test` and `@types/node`, exact
  versions at least two weeks old; ESLint node globals for `e2e/**`; `playwright-report/`, `test-results/` ignored by
  git, Prettier and ESLint.
- **Makefile**: `e2e-install` (chromium) and `e2e` (starts infra, backend jar and web in the background with a trap,
  waits for readiness, runs the specs). Not part of `make check`.
- **CI** (`.github/workflows/ci.yml`): new `e2e` job — install, compose up `--wait`, `./mvnw -B -DskipTests package`
  then `java -jar` (dev profile), `vite dev` in background, curl readiness loops (8080 actuator, 5173, Keycloak
  well-known), cached chromium install, run specs, upload report/traces/backend log on failure, 20 min timeout.
  Everything on `localhost` so the token issuer matches.

### 2.11 Documentation in the same change

ADR 0009 (implemented; base roles Chairman/Treasurer/Member; `member_role`), ADR 0007 note (`event_publication`
without RLS, listener tenant handling), `docs/domain/glossary.md` (Member → `Member`, Electrician no longer a base
role), `context/product/product-definition.md` and `context/product/architecture.md` (base roles, auth flow, E2E),
`docs/features/001-foundation.md` "Next" links, `README.md` and `web/README.md` (`make e2e`), module READMEs.
**CLAUDE.md** edits (base roles, test-user note, `make e2e`) are proposed in the final report only.

---

## 3. Impact and Risk Analysis

- **System dependencies:** `/me` shape changes break the web and the generated client, so contract, backend and web
  change together; features 003–005 build on `Permission`, `AuditEvent` and the archive rules; `GET /plots` keeps
  working for any linked member; `TenantIsolationTests` must stay green.

| Risk | Mitigation |
|---|---|
| **JDK 25 is not installed on this machine** (default 17) — nothing in the backend compiles | Blocking environment issue raised with the user before implementation |
| Async listener runs without tenant → RLS rejects the audit insert; redelivery has no user | Events carry `tenantId` and actor; `runAs` + transaction template; spike test; redelivery test |
| Permission lookup happens before the security context exists | Explicit `runAs` with the claim's tenant inside a transaction |
| V1 default privileges grant UPDATE/DELETE on `audit_entry` | Explicit REVOKE + grant-inspection test asserting SQLSTATE 42501 on UPDATE/DELETE |
| `event_publication` without RLS holds personal data in payloads | Completion mode delete, small payloads, documented exception, guard test |
| Modulith 2.1.1 table schema recalled from memory | Copy from the jar; `ddl-auto: validate` fails fast on mismatch |
| Account takeover via a changed email | Link only when `email_verified`, only unlinked members; never relink a different subject |
| Last-chairman race | Per-tenant advisory transaction lock; two-thread test |
| Flyway order: V2 sees no tenants on a fresh DB | Demo script and tests call `seed_base_roles` |
| Keycloak not ready when compose `--wait` returns | Healthcheck in compose |
| Playwright supply chain (browser download outside the lockfile) | Exact pin, chromium only, explicit install step, PR note |
| One or two DB queries per request in the converter | Indexed lookups; cache later only if measured |

---

## 4. Testing Strategy

- **Per-slice check:** `cd backend && ./mvnw -B test -Dtest=<Classes>` (surefire, `*Tests` classes; `verify` with
  `-Dtest` would trip the coverage floor). Client slices: `pnpm -r typecheck && pnpm -r lint`.
- **Unit (no Spring):** `PhoneNumber` (parameterised), delivery channel rule, `PermissionUnion` (incl. Chairman),
  `LastChairmanGuard`, role name normalisation, `MemberSnapshot.diff`, `MemberAuthenticationConverter` with a fake
  source (operator, no tenant, unverified email, archived), `format` helper in shared.
- **Integration (Testcontainers, extends `IntegrationTest`):** add `spring-boot-starter-webmvc-test` (test, Boot BOM)
  and `@AutoConfigureMockMvc`; a `@TestConfiguration` with a `@Primary JwtDecoder` mapping test token strings to
  prepared `Jwt`s (`TestTokens.bearer(tenant, sub, email, roles…)`), so the real converter runs.
  - Linking: case-insensitive match, no match → 403 `MEMBER_NOT_LINKED`, archived → 403, survives email edit,
    second subject refused, operator `/me`.
  - Permission matrix per endpoint: required permission only → allowed; all other permissions → 403; no token → 401.
  - Member visibility: other card 404, list 403, own card without `notes` key.
  - Domain rules end to end: email taken, channel contact, last chairman (incl. concurrent), role in use, base role
    immutable, role name taken.
  - Audit: entries via the outbox, redelivery after a listener failure (`@MockitoSpyBean` throws once, then
    `IncompleteEventPublications.resubmitIncompletePublications`), duplicate event id → one entry; Awaitility.
  - Isolation per table (`member`, `role`, `role_permission`, `member_role`, `audit_entry`) following
    `TenantIsolationTests`; composite FK cross-tenant refusal; audit append-only grants; `event_publication` guard.
  - `seed_base_roles` idempotent, matches the Java matrix, keeps customisations.
- `ModularityTests` green; `coverage.minimum` raised to the new measured level (rounded down to 0.01).
- **E2E:** Playwright specs above, run locally with `make e2e` and in CI.
- **UI verification:** Claude in Chrome against `make up` (local compose only).

---

## 5. Assumptions (pipeline mode, not confirmed by the user)

1. Module split `identity` / `members` / `audit` / `problem` with the SPI direction above.
2. Chairman's permissions stored as rows for display but always resolved to all in code.
3. Audit idempotency by `audit_entry.id = event id`.
4. `event_publication` has no RLS (documented exception to the tenant-table rule).
5. Linking requires `email_verified` and never relinks a member bound to another subject.
6. `notes` in a `PUT` from a caller without View members is ignored.
7. At most 10 phones per member.
8. Audit date filters are whole days in Europe/Kyiv, inclusive.
9. Search matches name/email substrings and phone digits.
10. Demo names are transliterated Ukrainian (repository language rule).
11. E2E uses the backend jar plus `vite dev` in CI; `make e2e` is outside `make check`.
12. Listener form (option A or B) chosen by a spike test in the audit slice.
13. New dependencies: `spring-modulith-starter-jpa`, `spring-boot-starter-webmvc-test` (test), `@playwright/test`
    and `@types/node` (web dev) — each noted for the PR.
