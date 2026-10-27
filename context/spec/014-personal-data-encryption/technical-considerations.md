# Technical Specification: Personal data encryption

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Requirement:** `docs/features/014-personal-data-encryption.md`
- **Status:** Implemented — pending manual check
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

A new Spring Modulith module `privacy` owns AES-256-GCM field encryption (JDK `javax.crypto`, no new library), HMAC-SHA-256
blind indexes and the key hierarchy: one master key from the environment wraps a per-cooperative data key and index key
stored in `tenant_data_key` (RLS). `members` seals and opens contacts in its own mapper layer, where the row id for the
AAD is known; `audit` stores sealed values as `{"enc": …}` and opens them only for `PERM_MEMBERS_VIEW`; `imports` seals
the whole pending plan. Owner-side work (encrypting existing data, the start-up key-check, master key rotation, demo
contacts) runs inside Flyway on the owner connection through Java migrations and callbacks, because `nadel_app` sees one
cooperative only. Schema follows expand (V21) → Java encryption (21.1–21.3) → contract (V22). Three small items ride
along: a water-connection plot guard, the meters e2e clean-up and the plot-search debounce bug behind the ownership e2e
failures. Decision record: ADR 0016.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module `privacy` (`backend/src/main/java/app/nadel/privacy`)

**Public API (root package):**

| Type | Responsibility |
|---|---|
| `PersonalDataCipher` | `byte[] encrypt(UUID tenantId, FieldRef, String)`, `String decrypt(UUID tenantId, FieldRef, byte[])` (throws `PersonalDataUnreadableException`, message without values), `byte[] blindIndex(UUID tenantId, IndexKind, String normalised)`. Asserts `tenantId` equals `TenantContext.currentTenantId()`. |
| `FieldRef(table, column, rowId)` | AAD coordinates; names validated against `[a-z_.]+` (never contain `|`). |
| `IndexKind` | `EMAIL`, `PHONE`, `PHONE_LAST4`. |
| `OwnerKeyring` | For owner-connection code only (Flyway Java migrations and callbacks): `open(java.sql.Connection)` → session; `session.forTenant(UUID)` gives encrypt/decrypt/blindIndex, creating missing keys on that connection. Depends only on `MasterKeys`, never on JPA. |
| `PersonalDataUnreadableException` | Mapped to 500 Problem `PERSONAL_DATA_UNREADABLE` (no values). |

**Internal:** `PrivacyProperties` (strings `nadel.privacy.master-key: ${NADEL_PD_MASTER_KEY:}`,
`previous-master-key: ${NADEL_PD_MASTER_KEY_PREVIOUS:}`, `dev-key-file`; validated by hand so no binder error echoes a
value), `MasterKeys` (current, optional previous, ids = first 8 bytes of SHA-256), `Aead`, `KeyWrapper`, `TenantKeys`,
`TenantKeyStore` (JdbcTemplate on the transaction's connection), `TenantKeyCache` (`ConcurrentHashMap` + injected
`Clock`, entry TTL 10 minutes; no Caffeine), `DefaultPersonalDataCipher`, `KeyCheckCallback`, `DevMasterKeySource`.

**Formats:**

| Item | Format |
|---|---|
| Sealed value (`bytea`) | `[0x01 format][u16 BE key version][12-byte nonce][ciphertext ‖ 16-byte tag]` |
| AAD | the 3 header bytes ‖ UTF-8 `tenantId|table|column|rowId` (UUIDs lower case) |
| Multi-valued (phones) | one JSON array, sealed once |
| Blind index | HMAC-SHA-256(index key, `kind.name() + 0x00 + normalised`), 32 bytes |
| Wrapped key | same layout, version 0; AAD `tenant_data_key|tenantId|version|data` or `|index` |

Normalisation: e-mail `strip().toLowerCase(ROOT)` (as `MemberData`); phone `PhoneNumber` international form; last 4 =
last 4 digits of the normalised phone.

**Keys:**

- Lazy creation: cache miss → SELECT in the caller's transaction; if absent, `TransactionTemplate(REQUIRES_NEW)` (tenant
  from `TenantContext`, so `TenantAwareTransactionManager` sets `app.tenant_id`) does `INSERT … ON CONFLICT DO NOTHING`
  and re-reads. Needed because read-only transactions (search, sign-in) cannot insert.
- Master key resolution: env value if set. Otherwise, only in the `dev` profile (`nadel.privacy.dev-key-file:
  infra/.env,../infra/.env`): read `NADEL_PD_MASTER_KEY=` from the first existing file; else, if an `infra/` directory
  exists, generate with `SecureRandom`, write `infra/.env` with mode 0600 and WARN with the path (never the key); else
  fail with a hint. No in-memory-only key, no committed key. Every non-dev profile (including the new `prod`) fails
  fast on a missing or malformed key: "NADEL_PD_MASTER_KEY is missing or is not 32 bytes of base64" (no cause
  attached).
- **Assumption:** CI e2e (`java -jar`, dev profile, repo root) gets its key through the dev file fallback, so
  `.github/workflows/ci.yml` is not changed; an explicit CI key step is proposed to the owner as a follow-up.

**Start-up (`KeyCheckCallback`, Flyway `AFTER_MIGRATE`, owner connection, not in a transaction):** for each
`tenant_data_key` row: id = current → unwrap (the key-check); id = previous → re-wrap, one transaction per cooperative;
any other id → ERROR log with `PD_MASTER_KEY_MISMATCH` and throw, so start-up fails. INFO log in counts only ("N keys
checked, M re-wrapped, 0 left on the previous key"). Flyway must be enabled (documented).

### 2.2 Data model / migrations

| Migration | Kind | Content |
|---|---|---|
| `V21__personal_data_keys.sql` | SQL, expand | `tenant_data_key`; `member.*_enc`, `member.email_bidx`; `member_phone_index`; `import_run.plan_enc`; new unique index `member_tenant_email_bidx_uq` |
| `21.1` members | Java (`JavaMigration` bean in `members.internal`) | encrypt every member, fill `email_bidx` and `member_phone_index` (same `PhoneIndexValues` as runtime) |
| `21.2` audit | Java (`audit.internal`) | `audit_entry` rows of entity `MEMBER`: each string value of the six fields → `{"enc": base64}` with `FieldRef("audit_entry", "changes.<field>.old|new", entryId)` (field in snake case, `FieldRef` names match `[a-z_.]+`: `changes.postal_address.old`); UPDATE as owner (documented exception). Also re-seal unfinished `event_publication` rows of `AuditEvent`/`AuditBatch`; skip values already `enc` (idempotent) |
| `21.3` imports | Java (`imports.internal`) | `plan_enc = seal({"plan": …, "digest": …})`, `plan = NULL` |
| `V22__drop_plaintext_contacts.sql` | SQL, contract | `DO` guard raising if any plaintext remains beside a null `*_enc`; drop `phones, email, postal_address, telegram_username, viber_phone, notes`, `member_tenant_email_uq`, `member_phones_gin`, `member_email_normalized_ck`, `import_run.plan`, `import_run.plan_digest`; rewrite `member_channel_contact_ck` on `*_enc` and `import_run_plan_ck` on `plan_enc` |

**Numbering (tasks.md):** the plan splits these per slice — V21 keys; V22 member expand, Java 22.1, V23 member contract;
Java 24 audit; V25 import expand, Java 25.1, V26 import contract — so each slice stays runnable.

Java migrations log counts only, run in Flyway's transaction, return a stable checksum, and depend only on
`OwnerKeyring`.

**`tenant_data_key`:** `tenant_id uuid` FK tenant, `version smallint`, `data_key_wrapped bytea`, `index_key_wrapped
bytea`, `master_key_id bytea` (CHECK 8 bytes), `created_at timestamptz`; PK `(tenant_id, version)` (leading column is
the tenant index); RLS + `tenant_isolation` (USING and WITH CHECK); `nadel_app`: SELECT, INSERT only. Re-wrap and
crypto-shredding are owner operations. Not granted to the operator role.

**`member_phone_index`:** `tenant_id`, `member_id`, `phone_bidx bytea`, `last4_bidx bytea`; PK `(member_id,
phone_bidx)`; FK `(tenant_id, member_id)` → `member(tenant_id, id)` ON DELETE CASCADE; indexes `(tenant_id,
phone_bidx)`, `(tenant_id, last4_bidx)`; RLS + `tenant_isolation`; `nadel_app`: SELECT, INSERT, DELETE (replace = delete +
insert).

**`member`:** `phones_enc` (NULL when no phones), `email_enc`, `postal_address_enc`, `telegram_username_enc`,
`viber_phone_enc`, `notes_enc`, `email_bidx` (CHECK 32 bytes); unique `(tenant_id, email_bidx) WHERE archived_at IS NULL
AND email_bidx IS NOT NULL`.

**Demo data:** `R__demo_data.sql` keeps names and archive state only and inserts members with `delivery_channel =
'PAPER'` (the channel CHECK cannot be met without ciphertext). The plaintext demo contacts and channels move to a dev
resource `db/demo/demo-contacts.json`; `members.internal.DemoContactsCallback` (Flyway `AFTER_EACH_MIGRATE`, only after
`R__demo_data.sql`) seals contacts, sets the channel, `email_bidx` and phone index through `OwnerKeyring`. Demo sign-in
by e-mail keeps working through `email_bidx`.

### 2.3 `members`

- `Member` maps only the `byte[]` columns; ids are app-generated, so known before sealing.
- `MemberCrypto` (internal): `open(Member) → OpenedContacts` (used by `MemberDtoMapper`, `MemberSnapshot`,
  `contacts()`); `seal(Member, opened, MemberData)` re-seals only changed fields and rewrites `member_phone_index` on a
  phone change.
- **Search** (`GET /members?q=`): names by LIKE as now, OR `email_bidx = :emailBidx`, OR exists phone index row with
  `phone_bidx = :phoneBidx` or `last4_bidx = :last4Bidx`. Candidates from trimmed `q`: contains `@` → e-mail; exactly 4
  digits → last 4; parses as `PhoneNumber` → full phone. A missing candidate binds a 32-byte zero array.
- `EMAIL_TAKEN`: `EMAIL_INDEX = "member_tenant_email_bidx_uq"`; pre-check by `email_bidx`.
- `MemberDirectory.contacts(ids)`: signature unchanged; decrypts with `TenantContext.currentTenantId()`.
- First sign-in (`MemberAccessProfileSource`): `findActiveByEmailBidx`.
- Import matching (`MemberImportService`): `email_bidx IN (:chunk)`; phone: join `member_phone_index` on `phone_bidx IN
  (:chunk)`, then filter by name; chunks of 500.
- `MemberAuditPublisher`: generates the event id first, seals the six fields with the audit `FieldRef` on that id, then
  builds the `AuditEvent`. `FieldChange` gains `boolean sealed` with a 3-argument constructor kept.
- Decrypt failure → `PERSONAL_DATA_UNREADABLE` 500 (never empty fields that a save would wipe).

### 2.4 `audit`

- `audit` → `privacy` dependency (audit never depends on members).
- `AuditWriter` stores sealed values as `{"enc": "<base64>"}`.
- `AuditController` passes `revealPersonal = hasAuthority(PERM_MEMBERS_VIEW)`; `AuditQueries.changesOf` opens every
  `{"enc"}` node when allowed, else omits both values and sets `hidden: true` (generic, reused by phase 3). A decrypt
  failure → `hidden` plus a log line with the entry id only.

### 2.5 `imports`

`import_run.plan_enc` sealed with `FieldRef("import_run", "plan_enc", runId)`; the digest moves inside the sealed
envelope (the unkeyed SHA-256 over contacts goes away). Expired-plan clearing unchanged.

### 2.6 API contract (`api/openapi.yaml`)

- `AuditFieldChange.hidden: boolean` (default false); when true, `oldValue`/`newValue` are absent.
- Problem codes: `PLOT_HAS_WATER_CONNECTION` (plot update `errors[{field: kind}]` and archive), `PERSONAL_DATA_UNREADABLE`.
- `GET /members` `q` description: exact e-mail, full phone or last 4 digits; names by substring.

### 2.7 Web (`web/`, `packages/shared`)

- Members: new `members.searchHint` («ПІБ, повний телефон або останні 4 цифри, точний email» / "Name, full phone or last 4
  digits, exact email") under the search field, linked with `aria-describedby`.
- Audit: when `change.hidden`, render `<span data-testid="audit-hidden">{t.audit.hiddenValue}</span>` («приховано» /
  "hidden").
- i18n for `PLOT_HAS_WATER_CONNECTION` (+ `_kind` variant) and `PERSONAL_DATA_UNREADABLE`.
- **Plot 9 e2e failures root cause:** `PlotsPage` (and Members, Meters, ReadingsRound pages) debounce the search 300 ms
  and then `setParams(…, {replace: true})`. The link "9" is already on the unfiltered page, so the test clicks it before
  the debounce fires; the late `replace` drags the browser back to `/plots?q=9`. Fix in the app: the debounce timer is
  cleared on unmount / navigation and writes only while the page is mounted (shared hook); the ownership specs wait for
  `?q=9` before clicking. No retries, no longer timeouts.
- **Meters e2e clean-up:** extract `cleanupMeterPlot` (decide pending reading, retire meter, archive plot) into
  `web/e2e/support/api.ts`; use it in `meters.spec.ts`'s `finally` and its `beforeAll` leftover sweep (as
  `meters-acceptance.spec.ts` does).
- New e2e: `members-search` (chairman finds a member by last 4 digits, `@smoke`; a unique phone created through the API);
  `privacy.spec.ts` (custom role with `AUDIT_VIEW` only sees «приховано»; the chairman sees values).

### 2.8 Water connection guard (`meters`)

`meters/internal/WaterConnectionPlotGuard implements plots.PlotChangeGuard`: for `ARCHIVE` and `BECOME_COMMON_LAND`,
vetoes plots with a lasting connection (`validTo IS NULL`) via `WaterConnectionRepository.plotIdsWithLastingConnection`;
`@Order` after `MeterPlotGuard` for a deterministic code. Meters README "known gaps" updated.

### 2.9 Infra and local run

- Makefile: file target `infra/.env` (umask 077, `NADEL_PD_MASTER_KEY=$(openssl rand -base64 32)`, never overwrites);
  `LOAD_ENV := set -a; . infra/.env; set +a`; `backend`, `up` and `e2e` depend on `infra/.env` and source it for the
  backend process only. Compose files do not reference the key.
- `application.yml`: `prod` profile document; `dev` sets `dev-key-file`. Tests: `IntegrationTest` adds a random key per
  JVM through `@DynamicPropertySource`.
- gitleaks: nothing to allowlist; docs use a placeholder `<output of: openssl rand -base64 32>`.

### 2.10 Docs

ADR `docs/adr/0016-personal-data-encryption.md`; `privacy/README.md`; members, audit (the owner-migration exception),
imports and meters READMEs; `context/product/architecture.md` (module list); `docs/features/backlog/deployment.md`
(secret store, offline copy, never with backups, restore drills with the key, rotation steps); owner manual uk/en
(security chapter, troubleshooting `PD_MASTER_KEY_MISMATCH`, local run key, new-cooperative recipe sets
`keycloak_subject` instead of an e-mail by SQL); board guide uk/en (search rules, encrypted contacts); PDFs rebuilt.
`infra/README.md` mentions `infra/.env`. A CLAUDE.md line about `infra/.env` is proposed to the owner (protected file).

---

## 3. Impact and Risk Analysis

- **System dependencies:** `members`, `audit`, `imports` → `privacy`; `privacy` → `tenancy`. `reports` (debtors) unchanged
  behind `MemberDirectory.contacts`. `meters` → `plots` SPI (existing).
- **Risks & mitigations:**
  - Lost master key = lost contacts → offline copy, docs, restore drills.
  - Plaintext dropped without encryption (Java migrations not registered) → V22 `DO` guard.
  - Flyway Java migrations/callbacks pulling JPA into Flyway init → depend only on `OwnerKeyring`/`MasterKeys`.
  - Server forgotten in the default `dev` profile → dev fallback only writes a key when `infra/` exists; `prod` documented.
  - Large test-fixture churn (≈14 files, `IntegrationTest.newMember` used by 51) → one sealing helper in `IntegrationTest`.
  - JaCoCo floor 0.974 → tests for every failure path (mismatch, unreadable, malformed key, dev key file).
  - Previous release incompatible after V22 → no production data; stated in ADR and PR.
  - `last4_bidx` has 10⁴ values → brute-forceable only with the index key, which is wrapped; stated in ADR.
  - `REQUIRES_NEW` key creation uses a second pooled connection once per cooperative.

---

## 4. Testing Strategy

- **Unit:** cipher round trip; AAD tampering (row, column, tenant, header); HMAC kind separation; `MasterKeys`
  validation (message names the variable, never the value); `DevMasterKeySource` with a temp dir; search candidate
  parsing; `TenantKeyCache` TTL with a fixed clock.
- **Integration (Testcontainers):** isolation tests for `tenant_data_key` and `member_phone_index`; AAD copy test across
  members and cooperatives; plaintext scan as `nadel_owner` (`row_to_json(t)::text`) over `member`, `audit_entry`,
  `import_run`, `event_publication`; member search, `EMAIL_TAKEN`, first sign-in, import matching; audit hidden vs
  revealed; debtors contacts; log capture (`OutputCaptureExtension`) over create/update/search/import/sign-in; water
  guard.
- **Migration test:** fresh container, Flyway target 20, two cooperatives with members, audit entries, a preview import
  and a pending publication; start the Spring context on it; assert decryption round-trips and no plaintext remains.
- **Start-up tests:** `prod` without / with malformed key fails; key B on a DB keyed with A fails with
  `PD_MASTER_KEY_MISMATCH`; B with previous A starts and re-wraps; B alone then starts.
- **Web:** Vitest for the hint and «приховано»; e2e `members-search` (`@smoke`), `privacy.spec.ts`, fixed ownership and
  meters specs; smoke set locally.
- Slice checks: `cd backend && ./mvnw test -Dtest='app.nadel.<module>.**'`; `pnpm -r typecheck && pnpm -r lint && pnpm -r
  test`; `pnpm --filter web exec playwright test <spec>`. Full: `make check`, then `make e2e`.

---

## Assumptions (pipeline mode, not user-confirmed)

- Owner-side work runs in Flyway Java migrations/callbacks (no owner DataSource bean in the app).
- Dev key fallback via `infra/.env` (read or create), no in-memory-only key; CI unchanged.
- Demo members inserted as `PAPER` by SQL; contacts and channels applied by `DemoContactsCallback`.
- `plan_digest` moves inside the sealed plan.
- Decrypt failures are a 500 `PERSONAL_DATA_UNREADABLE`, never silent empty values.
- New cooperative recipe: link the first chairman by `keycloak_subject`.
- Plot 9 e2e failures are fixed by clearing the search debounce on navigation (app) plus waiting for `?q=` (tests).
