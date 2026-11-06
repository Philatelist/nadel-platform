# Technical Specification: Ready for real use — inviting members, tariff fixes, small review items

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Completed
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

- **Invitations** live in the `members` module. The Keycloak Admin API client lives in `identity` (it already owns
  everything about Keycloak) behind a public port. Letters and Telegram messages are sent synchronously through a new
  members SPI implemented by `notifications` (the same shape as `TelegramLinkStatus`), so the one-time link never enters
  the outbox, the audit log or a log line. Disabling a Keycloak user after archive or a disable, and updating its
  e-mail after an e-mail change, go through id-only domain events and an idempotent listener (ADR 0007).
- The **set-password link** is the application's own single-use token (user decision): 32 random bytes, only its
  SHA-256 hash stored, 72 h, the newest invalidates older ones. A public web page `/set-password` posts the token and
  the password to public endpoints; the backend sets the password through the Admin API.
- **Access status is stored** in the database (`member_access`), updated at every transition, so the card works without
  Keycloak calls and during a Keycloak outage.
- **First chairman:** an `ApplicationRunner` activated by a property, run as a one-off container, calling the same
  invite code; with no mailbox it prints the link once to stdout (user decision).
- **Tariffs:** physical delete for tariffs never charged (reversed charges included); archive by an end date that
  reaches every calculation through one choke point (`VersionTerms.validTo`).
- **Web:** member access block, the public set-password page, tariff delete/archive and form hints, payer
  preselect, allocation table layout, duplicate-attachment check; help pages and e2e.
- **Infra:** a confidential realm client with a service account limited to user management in realm `nadel`, its
  secret from the environment, password policy `length(8)`.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Architecture and modules

| Piece | Module / path | Responsibility |
|---|---|---|
| `identity.SignInAccounts` (public port) + `identity.internal.KeycloakAdminClient` | `identity` | Admin API calls: find by login, create, get, update (email/username, emailVerified, enabled, requiredActions), reset password, logout. JDK `HttpClient` + Jackson, pattern of `notifications/internal/telegram/TelegramClient` |
| `members.InvitationDelivery` (public SPI) | `members` root package | `boolean emailReady(tenantId)`; `DeliveryResult deliver(tenantId, memberId, email, URI link, Instant expiresAt)` |
| `notifications.internal.InvitationLetters` | `notifications` | Implements the SPI: letter through `Letters`/`SmtpHostPolicy` from the cooperative mailbox; best-effort Telegram through `TelegramClient` when the member has an active link. Texts `invitation.*` in `notifications/messages_uk.properties` (ADR 0012) |
| `MemberInvitations`, `InvitationTokens`, `MemberAccessState`, `InvitationController`, `PublicInvitationController`, `MemberAccessListener` | `members/internal` | Invite / resend / disable / accept, status, listeners for `MemberAccessRevoked` and `MemberLoginChanged` |
| `FirstChairmanCommand` | `members/internal` | `ApplicationRunner`, `@ConditionalOnProperty(nadel.command=first-chairman)` |
| `tenancy.TenantProvisioning` (public API) | `tenancy` | `ensure(id, name)`: insert tenant if absent, `seed_base_roles` |
| `tariffs.TariffUsage` (port) + `ledger.internal.LedgerTariffUsage` | `tariffs` / `ledger` | `everCharged(...)` (any charge or charge part, reversed included), `lastChargedDay(...)` (unreversed) |

Dependency graph: `members → identity` (exists), `notifications → members` (exists), `members → tenancy` (new, allowed;
tenancy is a base module). No cycles. `ModularityTests` must stay green.

**Invite / resend (two-phase, a failed letter costs nothing):**
1. Outside a transaction: `ensureAccount` in Keycloak (rules below).
2. Transaction: lock the member `FOR UPDATE`, check state; insert `member_invitation` with `sent_at` null; set
   `member.keycloak_subject` if null.
3. Outside a transaction: `InvitationDelivery.deliver`.
4. Transaction: set `sent_at`, revoke older open invitations, state `INVITED` + `invited_at`, publish audit
   `MEMBER_INVITED` / `MEMBER_INVITE_RESENT` (details: `channels` only).
   On delivery failure a compensating transaction revokes the pending row; answer 502 `INVITATION_NOT_SENT`, no audit,
   the previous link still works.

**`ensureAccount`:** `SignInAccounts.findByLogin(email)` (`GET users?email=X&exact=true` and `?username=X&exact=true`;
realm has `duplicateEmailsAllowed=false`).
- match with another or no `tenant_id` → 409 `EMAIL_IN_OTHER_COOPERATIVE` (no details, no letter);
- match with this `tenant_id` → reuse: enable, link `keycloak_subject`; if another member row holds that subject → 409
  `SIGN_IN_ACCOUNT_IN_USE`;
- no match → `POST users` (`username=email`, `email`, names, `enabled:true`, `emailVerified:false`,
  `attributes.tenant_id`, no credentials). A 409 from Keycloak (race) → search again.

**Tenant guard in the admin client:** every mutating call on an existing user first reads it and checks
`attributes.tenant_id` equals the current cooperative; otherwise refuse (`SIGN_IN_ACCOUNT_IN_USE`). The client never
calls role-mapping endpoints.

**Accept (public):** hash token → `members_invitation_tenant(hash)` → `TenantContext.runAs(tenant)` → one transaction:
lock invitation `FOR UPDATE`, check sent / not used / not revoked / not expired (`Clock`), member active, state
`INVITED`, invitation `email_bidx` equals the member's current one; Keycloak guard, `reset-password`
(`temporary:false`), `PUT {emailVerified:true, requiredActions:[], enabled:true}`; set `used_at`, state `ACTIVE`,
`activated_at`. The password lives only in the request DTO (redacted `toString`) and the outgoing body.

**Disable / archive:** in the transaction set state `DISABLED` + `disabled_at`, revoke open invitations, publish
`MemberAccessRevoked(tenantId, memberId)` (ids only). Listener (`@Async @TransactionalEventListener` → `runAs` →
`TransactionTemplate`) re-reads the state and, only if still `DISABLED`, calls `enabled=false` + logout. Restore does not
touch access. `resolve` refuses non-`ACTIVE` states immediately, independent of Keycloak.

**E-mail change** (state `INVITED` or `ACTIVE`): synchronous pre-check (`EMAIL_IN_OTHER_COOPERATIVE`,
`SIGN_IN_ACCOUNT_IN_USE`); in the transaction state `INVITED`, revoke invitations, publish `MemberLoginChanged`. Listener:
`PUT /users/{id}` with new `username`, `email`, `emailVerified:false`, then, if `emailReady`, issue and send a new link
(a failure throws so the outbox redelivers; each retry makes a new token).

**Sign-in (`MemberAccessProfileSource.resolve`):** reads `member_access`; `INVITED`/`DISABLED` → not linked (403);
`NONE` linked by verified e-mail → `ACTIVE` (legacy accounts created by hand). Last sign-in:
`UPDATE member_access SET last_sign_in_at = now() WHERE member_id = ? AND (last_sign_in_at IS NULL OR last_sign_in_at <
now() - interval)` in its own short transaction; interval `nadel.identity.last-sign-in-interval` (default 1 h).

**First-chairman command:**
`docker compose run --rm --no-deps backend java -jar /app/nadel-backend.jar --spring.profiles.include=command
--nadel.command=first-chairman --nadel.first-chairman.cooperative="…" --nadel.first-chairman.email=…
--nadel.first-chairman.last-name=… --nadel.first-chairman.first-name=… [--nadel.first-chairman.resend=true]`
(wrapper script `infra/stand/first-chairman.sh`). Profile `command`: no web server, JobRunr server off, Telegram bot off.
Tenant id = `UUID.nameUUIDFromBytes("nadel:cooperative:" + lower(email))` so a re-run is idempotent. Steps:
`TenantProvisioning.ensure` → find or create the member with CHAIRMAN (existing command code, audited) →
`MemberInvitations.invite` with a stdout sink when `!emailReady`. `ACTIVE` → prints "already has access", exit 0;
`INVITED` → prints the date, no new link unless `--resend`. Audit actor `system:first-chairman`. Non-zero exit on any
problem.

### 2.2 Keycloak admin client and configuration

- Token: `client_credentials` at `{url}/realms/{realm}/protocol/openid-connect/token`, HTTP Basic, cached until
  `expires_in - 30 s`, one refresh on 401.
- Endpoints (`{url}/admin/realms/{realm}`): `GET /users?…`, `POST /users`, `GET /users/{id}`, `PUT /users/{id}`,
  `PUT /users/{id}/reset-password`, `POST /users/{id}/logout`.
- Errors: connect failure / timeout / 5xx / token 401-403 → 503 `SIGN_IN_SERVICE_UNAVAILABLE` (log: status code only);
  reset-password 400 `invalidPassword*` → 400 `VALIDATION_FAILED` field `password` code `PASSWORD_POLICY`; 404 on a
  stored id → account missing (invite recreates). Bodies are never logged.

| Property | Env var | Default |
|---|---|---|
| `nadel.keycloak.admin.url` | `NADEL_KEYCLOAK_ADMIN_URL` | `http://localhost:8180`; stand `http://keycloak:8080/auth` (Caddy blocks `/auth/admin`) |
| `nadel.keycloak.admin.realm` | `NADEL_KEYCLOAK_REALM` | `nadel` |
| `nadel.keycloak.admin.client-id` | `NADEL_KEYCLOAK_ADMIN_CLIENT_ID` | `nadel-backend-admin` |
| `nadel.keycloak.admin.client-secret` | `NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET` | dev value only in the `dev` profile; other profiles refuse to start without it |
| `nadel.keycloak.admin.connect-timeout` / `read-timeout` | — | 5 s / 10 s |
| `nadel.web.url` | `NADEL_WEB_URL` | `http://localhost:5173` (base of `{web}/set-password`) |
| `nadel.invitations.ttl` | — | `72h` |

### 2.3 Realm and infra

- `infra/keycloak/nadel-realm.json`: client `nadel-backend-admin` (confidential, `serviceAccountsEnabled`, standard flow,
  implicit and direct grants off, `fullScopeAllowed:false`, no `tenant_id` mapper), dev secret
  `nadel-backend-admin-dev-secret`; user `service-account-nadel-backend-admin` with `clientRoles.realm-management:
  [manage-users, view-users, query-users]`, no realm roles. Realm `passwordPolicy: "length(8)"` (checked only on
  set/reset, so the demo password `nadel` still imports). After the change: `docker compose -f infra/docker-compose.yml
  down -v`.
- `infra/stand/compose.yml` backend env: `NADEL_KEYCLOAK_ADMIN_URL`, `NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET:
  ${NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET:?…}`, `NADEL_WEB_URL`. `.env.example` gets the variable ("written by
  stand-init.sh"). `stand-init.sh` rotates the secret with kcadm (idempotent), writes it to `.env` (mode 600), sets
  `passwordPolicy=length(8)`, checks `STAND_USER_PASSWORD` length ≥ 8, and tells the operator to `docker compose up -d
  backend`.
- gitleaks: if the dev secret is flagged, allowlist that path/fingerprint with a reason (protected-file rule: proposal for
  the owner if it needs `.github/` or `.gitleaks*` changes outside our scope).

### 2.4 API contract (`api/openapi.yaml`, then `pnpm gen:api`)

- `Member.access?: MemberAccess` — present only for callers with `MEMBERS_VIEW` (a member never sees it on their own
  card). `MemberAccess {status: NOT_INVITED|INVITED|ACTIVE|DISABLED, invitedAt?, lastSignInAt?, disabledAt?,
  inviteBlockedBy?: EMAIL_MISSING|MAILBOX_NOT_CONFIGURED}`.
- Board endpoints (`PERM_MEMBERS_VIEW` + `PERM_MEMBERS_INVITE`), 200 `MemberAccess`, 404 for unknown or foreign member:
  - `POST /members/{id}/invitation` — 409 `MEMBER_ARCHIVED`, `MEMBER_EMAIL_MISSING`, `DELIVERY_NOT_CONFIGURED`,
    `INVITATION_PENDING`, `ACCESS_ALREADY_ACTIVE`, `EMAIL_IN_OTHER_COOPERATIVE`, `SIGN_IN_ACCOUNT_IN_USE`; 502
    `INVITATION_NOT_SENT` (`failureCode`); 503 `SIGN_IN_SERVICE_UNAVAILABLE`.
  - `POST /members/{id}/invitation/resend` — state must be `INVITED` (409 `NOT_INVITED`); same failures.
  - `POST /members/{id}/access/disable` — `INVITED`/`ACTIVE`; already `DISABLED` → 200 no-op without audit;
    `NOT_INVITED` → 409 `ACCESS_NOT_GRANTED`.
- Public (`security: []`, `/api/v1/public/**` permitAll), token in the body, not the URL:
  - `POST /public/invitations/inspect {token}` → 200 `{email}`;
  - `POST /public/invitations/accept {token, password (8..128)}` → 204;
  - 404 `INVITATION_INVALID` (one code for unknown/used/replaced/expired/disabled/archived), 400 `VALIDATION_FAILED`
    (`password`: `TOO_SHORT`, `PASSWORD_POLICY`), 503.
- Tariffs (`TARIFFS_VIEW` + `TARIFFS_MANAGE` for writes):
  - `DELETE /tariffs/{id}` → 204; 409 `TARIFF_IN_USE` (FK violation 23503 also mapped).
  - `POST /tariffs/{id}/archive {endsOn}` → 200 `Tariff`; 400 `VALIDATION_FAILED` `endsOn`
    `TARIFF_END_BEFORE_LAST_VERSION`; 409 `TARIFF_ARCHIVED`; 409 `TARIFF_CHARGED_AFTER_END`.
  - New versions, version edits and scope changes on an archived tariff → 409 `TARIFF_ARCHIVED`.
  - `GET /tariffs?status=ACTIVE|ARCHIVED` (default `ACTIVE`; archived = `archived_at` set) and optional `chargeTypeId`.
  - `TariffSummary`/`Tariff` gain `validUntil` (inclusive, `endsOn - 1`), `deletable`, `archivedAt`; the summary carries
    the plot labels needed for the form hint (reuse what the list already returns or add `plotNumbers`).
- `Permission` enum + `MEMBERS_INVITE`. `AuditAction` + `MEMBER_INVITED`, `MEMBER_INVITE_RESENT`,
  `MEMBER_ACCESS_DISABLED`, `TARIFF_DELETED`, `TARIFF_ARCHIVED`. `ProblemCodes` gets the new codes.

### 2.5 Database — `V35__tariff_delete_and_archive.sql` (tariff rows) and `V36__member_invitations.sql` (the rest)

| Table / object | Key columns | Notes |
|---|---|---|
| `member_access` | `member_id` PK, `tenant_id uuid not null`, `state` (CHECK `NONE|INVITED|ACTIVE|DISABLED`), `invited_at`, `activated_at`, `disabled_at`, `last_sign_in_at`, `updated_at` | FK `(tenant_id, member_id)` → `member`; RLS `tenant_isolation`; index on `tenant_id`; `nadel_app` SELECT/INSERT/UPDATE. No row = `NOT_INVITED`. Backfill `ACTIVE` for members with `keycloak_subject` |
| `member_invitation` | `id`, `tenant_id not null`, `member_id`, `token_hash bytea` (32 bytes, CHECK, UNIQUE), `email_bidx bytea not null`, `created_at`, `expires_at`, `sent_at`, `used_at`, `revoked_at`, `created_by` | Partial unique: one open link per member `WHERE sent_at IS NOT NULL AND used_at IS NULL AND revoked_at IS NULL`; RLS + index; UPDATE grant only on `sent_at`, `used_at`, `revoked_at` |
| `members_invitation_tenant(p_token_hash bytea) RETURNS uuid` | — | `SECURITY DEFINER`, `STABLE`, `SET search_path = public, pg_temp`, `REVOKE ALL FROM PUBLIC`, `GRANT EXECUTE TO nadel_app`; returns only the tenant id. Recorded in ADR 0020 (ADR 0018 requires an ADR per cross-tenant function) |
| `tariff` | + `valid_until date`, `archived_at timestamptz` | CHECK both null or both set; `GRANT DELETE ON tariff TO nadel_app` |
| `seed_base_roles` | — | `CREATE OR REPLACE` with V32 body + `('CHAIRMAN','MEMBERS_INVITE')`; targeted insert for existing chairman roles (pattern of V32:663-670) |

Only additions, so the previous release runs on the migrated database. Demo data (`R__demo_data.sql`): `member_access`
`ACTIVE` for the realm's demo users; one demo member with an e-mail and no Keycloak account, to show the invite.

### 2.6 Tariff delete and archive

- `TariffUsage.everCharged(tenantId, Map<tariffId, Set<versionId>>)` (soft-deleted versions included; any
  `charge.tariff_id` or `charge_part.tariff_version_id`, reversed included) — batched for the list's `deletable`;
  `lastChargedDay(tenantId, tariffId, versionIds)` from unreversed charges. Both in the caller's transaction with the
  tariff locked `FOR UPDATE` (the month run holds a shared lock).
- Delete: lock, `everCharged` → 409 if used, delete scope plots, all versions (incl. soft-deleted), the tariff; audit
  `TARIFF_DELETED` (details: name). RESTRICT FKs are the last guard.
- Archive: lock; `endsOn` after last version's `validFrom`; `lastChargedDay < endsOn`; set `valid_until = endsOn - 1`,
  `archived_at`; audit `TARIFF_ARCHIVED` (details: `endsOn`).
- End date reaches every calculation: `VersionTerms` gets `@Nullable validTo` (keep the old constructor);
  `TariffEntry` gets `validUntil` and puts it on the last version in `terms()`; `ChargeCalculator.overlaps` and
  `ledger.ReadingChargeCalculator` use `terms.validTo()`; `TariffResolver.resolve` skips tariffs whose `validUntil` is
  before the period. Month run, reading charges, recalculation, expected amounts and `TariffConflicts` then honour it.
  A meter tariff archived with no successor sends later intervals to the not-charged queue as `NO_TARIFF_FOR_ALL_DAYS`
  (accepted).
- `tariffs/README.md` updates the rules "never deleted" and "versions never end".

### 2.7 Web

- **Set-password:** `web/src/pages/SetPasswordPage.tsx` + `SetPasswordApp.tsx`, route `/set-password?token=…`, branch in
  `App()` before the auth gate (`!auth.isAuthenticated && pathname === '/set-password'`), shell extracted to
  `components/PublicShell.tsx` and shared with `PublicHelpApp`. States `loading | invalid | form | done`; token dropped
  from the address bar with `history.replaceState` after inspect; `<meta name="referrer" content="no-referrer">`; the
  generated public operations must not require a bearer token (check the mutator). «Увійти» →
  `auth.signinRedirect({extraQueryParams: {ui_locales}})`. Problem codes map through `setPassword.errors.*`.
- **Member card:** `components/members/MemberAccess.tsx` under `MemberDetails` in `MemberPage.tsx`; hidden when no
  `access` in the response; buttons by status with `useCan('MEMBERS_INVITE')`; reasons `EMAIL_MISSING` /
  `MAILBOX_NOT_CONFIGURED` as a visible helper line; disable through the existing confirm dialog; invalidate member
  queries on success; problem codes to i18n.
- **Tariffs:** `TariffPage.tsx` delete (confirm) or archive (`ArchiveTariffDialog.tsx`, date input) by `deletable`;
  archived tariff shows «Діє до {validUntil}», no edit/version actions. `TariffsPage.tsx` Active/Archived switch →
  `status` param. `TariffConflicts.tsx` new text. `TariffFormPage.tsx`: all types listed, inactive as
  «{name} (неактивний — увімкнути)» — choosing one calls the existing `PUT /charge-types/{id}` with `active:true`
  (disabled option without `TARIFFS_MANAGE`); active tariffs of the chosen type with their plots under the field (from
  the cached list query, filtered by `chargeTypeId`); link to `/charge-types`.
- **Payments:** hook `usePlotPayerOwners(plotId)` over `useListPlotOwnerships` returning `{owners, soleOwnerId}`;
  preselect only when exactly one current non-archived owner and the user has not touched the field (ref reset on plot
  change). `ProposalTable.tsx` footer: one line (`whitespace-nowrap`, label and amount in one cell / flex); exceed line
  above the amount input, `role="alert"`, existing `exceedsText` helper (no new money arithmetic).
- **Duplicate attachment:** `web/src/lib/fileFingerprint.ts` (SHA-256 over `name\0size\0` + bytes via `crypto.subtle`,
  no check when unavailable); `AttachmentPicker.tsx` keeps fingerprints of attached files, shows «Цей файл уже додано»,
  removes the fingerprint when an attachment is removed.
- **i18n** (`packages/shared/src/i18n/uk.ts`, `en.ts`): `members.access.*`, `setPassword.*`, permission
  `MEMBERS_INVITE`, audit actions, problem codes, `tariffs.delete.*`, `tariffs.archive.*`, `tariffs.form.*`, new
  conflict text, `cashbook` duplicate text.

### 2.8 Help, guides, ADR

- Help (`docs/help/{uk,en}`): `member-card.md` (access block; shot `member-card-access`), `tariff-card.md` (delete,
  archive), `tariffs.md` (archived switch, warning), `tariff-form.md` (hints, inactive type), `payments-enter.md`
  (payer default), `expense-form.md` (duplicate file), `getting-started.md` (how members get access), `roles.md` (new
  permission), new public page `set-password.md`. Shots via `make help-shots ID=…`, then `make guide`. The completeness
  check (`web/help/check.ts`) must pass.
- Owner manual `docs/guide/{uk,en}/owner-manual.html`: `#keycloak` (new client, secret, replaces the manual account
  procedure), `#tenants`/`#deploy` (first-chairman command), `#permissions` (32 permissions). `infra/README.md`,
  `infra/stand/README.md`, `docs/features/backlog/deployment.md` §4 (implemented; command no longer limited to
  prod/staging profiles — the safety is the explicit one-shot `run --rm` without HTTP), `.env.example` files.
- ADR `docs/adr/0020-member-invitations.md`: client and roles, secret handling, token as hash, single use 72 h,
  synchronous delivery SPI instead of the outbox, the cross-tenant function, stored access state, first-chairman stdout.
- Docs with the permission list/count: ADR 0009, `context/product/architecture.md`, `docs/domain/glossary.md`,
  module READMEs (`members`, `identity`, `tariffs`, `notifications`).

---

## 3. Impact and Risk Analysis

- **System dependencies:** Keycloak becomes a runtime dependency for invite/accept (not for sign-in or the card).
  Tariff end dates affect the month run, reading charges, recalculation and expected amounts. The permission count
  31 → 32 ripples into backend tests, e2e and docs (do not touch the unrelated "31" plot counts).
- **Risks & mitigations:**
  - *Leaking the link or password* → link only on the stack; token in request bodies, not URLs; DTO `toString`
    redacted; tests assert absence from audit, outbox rows and logs.
  - *Service account overreach* → only three realm-management roles; tenant guard on every mutation; no role-mapping
    calls; Testcontainers test proves 403 on master and realm settings.
  - *Cross-tenant token lookup* → one `SECURITY DEFINER` function returning only the tenant id, for the holder of a
    256-bit secret; ADR 0020.
  - *Keycloak down* → 503 with a code; card and sign-in unaffected; outbox redelivers listener work.
  - *Archive end date missed by a calculation* → single choke point in `VersionTerms`; month-run test.
  - *e2e side effects* → throwaway members for invite and archive flows; electrician role restore helper.
  - *Stand drift from the dev secret* → compose refuses to start without the rotated secret.
- **New test dependency:** none — Keycloak in tests uses a plain Testcontainers `GenericContainer` with the compose image
  `quay.io/keycloak/keycloak:26.8.1` (adds ~1–2 min to the backend job; noted in the PR).

---

## 4. Testing Strategy

User instruction for this run: tests are written with each slice but **not run** until all slices are implemented; one
verification pass at the end (`make check`, the feature's e2e specs and `@smoke`, then CI).

- **Backend unit:** token hashing/expiry, access state transitions, `TariffResolver`/`ChargeCalculator`/
  `ReadingChargeCalculator` with `validTo`, `LedgerTariffUsage`.
- **Backend integration (Testcontainers):**
  - `TestKeycloak` singleton (`GenericContainer`, `start-dev --import-realm`, realm json mounted, health on 9000); only
    Keycloak-tagged classes use it, others use a fake `SignInAccounts`.
  - Admin client: create, find, set password, disable, change login; error mapping (wrong secret, stopped port, policy).
  - Service account limits: 403 on `/admin/realms/master/users`, `/admin/realms/nadel/clients`, `PUT /admin/realms/nadel`.
  - Invite → inspect → accept → sign-in (password grant via a test client); reused / replaced / expired token
    (`MutableClock`) → 404; disable; archive → listener disables, restore keeps `DISABLED`; e-mail change; send failure
    → 502, state unchanged; Telegram-linked member → fake Bot API receives the link; `EMAIL_IN_OTHER_COOPERATIVE`
    (`member@other.local`); linking existing `member@demo.local`.
  - First-chairman command (`OutputCaptureExtension` + Logback list appender): link printed once, never logged;
    idempotent re-run; `--resend`.
  - Permissions allowed/denied (chairman, treasurer, member, operator); member's own card has no `access`; audit
    actions without link/password; last-sign-in throttling.
  - Isolation tests for `member_access`, `member_invitation`; tests for `members_invitation_tenant`.
  - Tariffs: delete unused / refused when used (incl. reversed charge, soft-deleted version); archive validations;
    status filter; versions frozen; month-run test (archive from 1 March: February charges, March does not).
  - Permission count updates (`RolesTests`, `LedgerMigrationTests`, `SeedBaseRolesTests`, `BaseRolesMatrixTests`, …).
- **Web unit (vitest):** `MemberAccess` button matrix, `SetPasswordPage` states, `fileFingerprint`, payer preselect hook,
  `ProposalTable` footer/exceed placement, `TariffFormPage` hints and inactive type.
- **e2e (Playwright):** `member-invite.spec.ts` (`@smoke`: chairman invites a throwaway member → letter in Mailpit →
  set password → sign in; plus resend/old link, no e-mail, treasurer, other cooperative), tariff delete and archive,
  payer preselected, duplicate attachment, `restoreElectrician` helper in specs that change the electrician, deflake
  `acceptance.spec.ts` member-to-archive (wait for refresh, 10 consecutive runs with `--repeat-each=10`), redesign the
  electrician archive test with a throwaway member, `help.spec.ts` opens `/set-password` signed out without a redirect.

## 5. Assumptions

- Client id `nadel-backend-admin`; env var names as in 2.2.
- Keycloak tests use `GenericContainer` (no new dependency) rather than `testcontainers-keycloak`.
- Stored access state; drift only through manual Keycloak edits (documented).
- First-chairman command is not restricted to `prod`/`staging` profiles (the stand runs `dev`); deployment.md §4 updated.
- Archived = `archived_at` set (even when `validUntil` is in the future, the tariff leaves the active list immediately
  and stops charging from `endsOn`).
- Archive cannot be undone in this feature.
- Invitation texts in the letter use the backend bundle (ADR 0012); the bot message reuses them.
