# 014. Personal data encryption

Roadmap: a cross-cutting feature between phase 2 and phase 3. Input for `/feature-pipeline`. Requires 002 (members),
003 (audit), 008 (Excel import) and 013 (reports, the debtors report).

## 1. Goal

Members' contacts and notes are stored in the database encrypted. A database dump, a backup or a read-only leak of the
database then exposes names but no phones, e-mails, addresses, messenger handles or notes. Phase 3 will store bank
statements and payers, so encryption has to be in place before it. Payments then write personal data encrypted from
their first day.

**Agreed with the owner (22.10.2026):**
- Encrypt: phones, e-mail, postal address, Telegram username, Viber phone and notes.
- Names stay in plaintext, so search by part of a surname and sorting keep working.
- Search by phone and by e-mail uses a blind index and matches the exact value only.
- The master key lives only in the server environment. Each cooperative has its own data key. The master key is kept
  apart from database backups.
- The system still never stores a tax number (РНОКПП), passport data or a date of birth.

## Context for the pipeline

Read together with:
- `CLAUDE.md`, in particular the security rules: secrets only from environment variables, no personal data in logs;
- the READMEs of `members`, `audit`, `imports`, `identity`, `tenancy` and `reports`;
- ADRs 0002 (RLS), 0003 (Keycloak), 0007 (events and outbox), 0008 (operator data access) and 0012 (Excel import);
- `docs/features/backlog/deployment.md`;
- the guides in `docs/guide/`.

General rules as in earlier features:
- contract-first;
- schema changes only through Flyway migrations;
- tenant-scoped tables with RLS and an isolation test;
- permissions, not role names;
- Problem Details with a `code`;
- i18n for every text;
- audit through the outbox;
- the verification levels and the guides rules of `CLAUDE.md`.

## 2. Cryptography

### 2.1 Primitives

- **Encryption:** AES-256-GCM from the JDK (`javax.crypto`), with a random 96-bit nonce per value and a 128-bit tag. No
  new crypto library, and no `pgcrypto`: keys never travel to the database in a query.
- **Associated data (AAD):** `tenant_id | table | column | row id`. A ciphertext copied into another row, column or
  cooperative fails to decrypt instead of showing someone else's data.
- **Stored format:** `bytea` = format version (1 byte), data key version (2 bytes), nonce, ciphertext and tag. A
  multi-valued field (phones) is encrypted as one value: a JSON array.
- **Blind index:** HMAC-SHA-256 with the cooperative's index key over the normalised value, stored as `bytea` (32
  bytes). Normalisation is the one the code already uses:
  - e-mail trimmed and in lower case;
  - a phone in international format (`PhoneNumber`).

### 2.2 Keys

- **Master key:** `NADEL_PD_MASTER_KEY`, 32 random bytes in base64, read from the environment only.
  - With the `prod` profile, the application refuses to start without it or with a malformed one, and the message
    names the variable but not the value.
  - The master key is never written to the database, the logs, the repository or a backup.
- **Data keys per cooperative:** table `tenant_data_key`, holding:
  - the data key and the index key, both wrapped (AES-GCM) with the master key;
  - the master key's id: the first 8 bytes of SHA-256 of the master key;
  - the version;
  - `created_at`.

  The keys are created with the cooperative, and lazily for existing ones. The table is not tenant-scoped data for
  members: only the application reads it, through the cipher. It still has RLS, so one cooperative's request can unwrap
  only that cooperative's keys.
- **Master key rotation:**
  - `NADEL_PD_MASTER_KEY_PREVIOUS` (optional) holds the old key.
  - At start-up, every data key wrapped with the previous master key is re-wrapped with the current one, in one
    transaction per cooperative.
  - Once every key is re-wrapped (the start-up log says so, in counts only), the owner removes the previous key from the
    environment.
  - Data keys themselves are not rotated in this feature. The version field leaves room for that.
- **A wrong master key:** a key-check at start-up unwraps one data key. If the master key is wrong, the application
  refuses to start, with the code `PD_MASTER_KEY_MISMATCH` in the log. It never starts and then fails per request.
- **Development and tests:**
  - `make up` generates a random key into the git-ignored `infra/.env` when there is none, so no key is ever committed
    and gitleaks stays green.
  - Tests generate a key per run.
  - The demo data is encrypted at start-up like any other data (2.4).
- **Crypto-shredding:** deleting a cooperative's row in `tenant_data_key` makes its personal data unreadable. Phase 7
  (SaaS off-boarding) will use this. No endpoint does it in this feature.

### 2.3 Module

A new module `privacy` owns the cipher. Its public API:
- `PersonalDataCipher`: `encrypt(tenantId, FieldRef, plaintext)`, `decrypt(tenantId, FieldRef, bytes)` and
  `blindIndex(tenantId, IndexKind, normalised)`;
- `FieldRef`: table, column, row id.

Data keys are cached per cooperative in memory for at most 10 minutes, and never in a shared cache.

The pipeline decides in Phase 1 where encryption happens in `members`: in the repository/mapper layer, because a JPA
`AttributeConverter` does not see the row id needed for the AAD. The decision is recorded in ADR 0016 "Personal data
encryption", together with the key hierarchy, the format and the rotation.

### 2.4 Existing data

1. A Java Flyway migration (running as `nadel_owner`, reading the master key from the environment) creates the data
   keys and encrypts:
   - every member row;
   - the personal values in the audit log (3.3);
   - the import plans (3.4).
2. The next SQL migration drops the plaintext columns and the old indexes on them (`member_tenant_email_uq` and
   `member_phones_gin`).

There is no production data yet; the migration still has to be correct for the demo cooperative and for a database
with several cooperatives, and it is tested on one.

## 3. What changes where

### 3.1 `member`

| Field | Now | After 014 |
|---|---|---|
| `last_name`, `first_name`, `middle_name` | text | unchanged (plaintext) |
| `phones` | `text[]` | `phones_enc bytea` (JSON array), and the table `member_phone_index` (`tenant_id`, `member_id`, `phone_bidx`, `last4_bidx`) |
| `email` | text | `email_enc bytea`, `email_bidx bytea`; unique index `(tenant_id, email_bidx) WHERE archived_at IS NULL AND email_bidx IS NOT NULL` replaces `member_tenant_email_uq` |
| `postal_address`, `telegram_username`, `viber_phone`, `notes` | text | `*_enc bytea` |
| `delivery_channel`, `keycloak_subject` | text | unchanged |

The CHECK `member_channel_contact_ck` moves to `*_enc IS NOT NULL`. The rule "e-mail in lower case" is enforced in
`MemberData` (it already is) and by a test, since the database can no longer see the value.

### 3.2 Lookups that used the plaintext

- **Member search** (`GET /members?q=`):
  - names: substring, case-insensitive, as now;
  - e-mail: exact match, case ignored, through `email_bidx`;
  - phone: the full number in any format (through `phone_bidx`), or exactly its last 4 digits (through `last4_bidx`).

  Substring search inside an e-mail or inside a phone's middle digits is gone. The search field's hint says so («ПІБ,
  повний телефон або останні 4 цифри, точний email» — "full name, full phone or its last 4 digits, exact e-mail").
- **First sign-in** (`MemberAccessProfileSource`): an active member is found by verified e-mail through `email_bidx`.
- **E-mail uniqueness and 409 `EMAIL_TAKEN`:** through the new unique index.
- **Excel import matching** (`MemberImportService`): by `email_bidx`, and by name plus `phone_bidx`, in chunks of 500 as
  now.
- **`MemberDirectory.contacts(ids)`:** decrypts inside `members`. Its signature does not change, so 013's debtors report
  and every other caller keep working.

### 3.3 Audit log

- The change values of the encrypted fields (`phones`, `email`, `postalAddress`, `telegramUsername`, `viberPhone`,
  `notes`) are encrypted by `members` before the event is published. They are stored in the audit as
  `{"enc": "<base64>"}`, with the AAD on the audit entry id and the field. The outbox (`event_publication`) therefore
  never holds them in plaintext either.
- `GET /audit` decrypts them only for a reader who also has `MEMBERS_VIEW`. Any other reader with `AUDIT_VIEW` gets the
  field with `"hidden": true` and no values, and the web shows «приховано» ("hidden").
- Existing entries are encrypted by the migration of 2.4. `audit_entry` is append-only for `nadel_app`. The migration
  runs as the owner, and this is the one documented exception. The audit README says so.

### 3.4 Excel import

- `import_run.plan` holds the normalised rows, contacts included, for 30 minutes. It is encrypted as a whole (`plan_enc
  bytea`).
- The expired plans are still dropped as now.
- The uploaded file is not stored; this stays so.

### 3.5 Logs, errors, metrics

There is a test that a member create, update, search, import and sign-in write no phone, e-mail or note to the logs. It
captures the log output and searches for the test values. Problem Details never echo a contact value.

## 4. Small items done in the same feature

1. **Water connection guard.** Archiving a plot, or turning it into common land, is refused while it has a lasting water
   connection: 409 `PLOT_HAS_WATER_CONNECTION` with `errors[{field: kind}]` or on archive. This is the known gap in the
   `meters` README.
2. **Clean-up of the 010 e2e test.** If the test fails between a member's submitted reading and its approval, its
   clean-up leaves a test plot active in the demo data. The clean-up must run whatever the test's outcome.
3. **The two flaky e2e tests.** The pipeline finds them in the CI history of the last 20 runs on `main`, fixes the
   cause (not a retry or a longer timeout) and names them in the PR.

## 5. Permissions

No new permission. `MEMBERS_VIEW` now also decides whether personal values in the audit log are shown (3.3). The platform
operator still has no database access (ADR 0008) and cannot decrypt anything.

## 6. Web UI

- **Members list:** the new search hint.
- **Audit log:** hidden personal values shown as «приховано».
- **Member card and form:** nothing visible changes.

## 7. Docs and guides

- ADR 0016.
- `privacy` module README; `members`, `audit` and `imports` READMEs.
- `docs/features/backlog/deployment.md`: the master key in the server's secret store, a copy kept offline (a password
  manager or a sealed paper copy) and never with the backups; restore drills need the key.
- **Owner manual, both languages:**
  - the security chapter: what is encrypted, the key hierarchy, generating a key (`openssl rand -base64 32`), rotation
    with `NADEL_PD_MASTER_KEY_PREVIOUS`, and the warning that "a lost master key = lost contacts";
  - troubleshooting for `PD_MASTER_KEY_MISMATCH`;
  - local run: `make up` creates the key.
- **Board guide, both languages:**
  - the members chapter: how search works now;
  - the security note: contacts are stored encrypted.

  As before, the board guide does not mention other cooperatives or the platform.
- The PDFs are rebuilt.

## 8. Out of scope

- encrypting names;
- data key rotation and re-encryption of all values;
- crypto-shredding endpoints (phase 7);
- encrypting Keycloak's own copy of the e-mail (Keycloak's database, deployment phase);
- field-level encryption of any other table: phase 3 uses `privacy` for payer data in its own spec.

## 9. Acceptance criteria

- [ ] No plaintext phone, e-mail, address, Telegram username, Viber phone or note remains in `member`, `audit_entry`,
      `import_run` or `event_publication`. A test reads the tables as `nadel_owner` and searches for the test values.
- [ ] A ciphertext copied into another row or another cooperative fails to decrypt (AAD test).
- [ ] Search by name (substring), full phone in different formats, last 4 digits and exact e-mail works.
      `EMAIL_TAKEN`, first sign-in by e-mail and import matching work as before. The existing member, import and
      identity tests stay green.
- [ ] The debtors report of 013 still shows contacts. The audit log shows personal values only with `MEMBERS_VIEW`.
- [ ] Start-up fails clearly without a key (`prod` profile) and with a wrong key. Rotation with the previous key
      re-wraps every data key. Tests cover all three.
- [ ] The migration encrypts existing data for two cooperatives and is tested.
- [ ] No contact value in the logs (3.5). `make up` creates a key, and gitleaks finds nothing.
- [ ] Section 4 is done: the water guard has a test, and the e2e clean-up and the two flaky tests are fixed.
- [ ] Docs and both guides (two languages) are updated.
- [ ] `make check` is green. e2e: the chairman finds a member by the last 4 digits of the phone (`@smoke`); a custom role
      with `AUDIT_VIEW` and without `MEMBERS_VIEW` sees «приховано» in the audit log.
