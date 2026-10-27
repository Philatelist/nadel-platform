# 0016. Personal data encryption: per-cooperative keys under one master key, AES-GCM in the application, blind indexes

**Status:** accepted; introduced between phases 2 and 3 by spec 014 (`context/spec/014-personal-data-encryption/`)

## Context
Feature 014 stores members' contacts and notes (phones, e-mail, postal address, Telegram username, Viber phone, notes)
encrypted, so that a database dump, a backup or a read-only leak exposes names but no contact. Phase 3 will store bank
statements and payers, so the mechanism must exist before it. Names stay in plaintext (search by part of a surname and
sorting keep working). Requirements from the feature document (section 2): JDK crypto only, a value copied to another
row, column or cooperative must not open, exact search by e-mail and phone, a master key from the environment only and
kept apart from backups, a key per cooperative, rotation of the master key, a server with a wrong key must not start.

Questions:

1. Where do keys live and how are they protected?
2. What is the stored format, and what binds a value to its place?
3. How do search, uniqueness and sign-in work without the plaintext?
4. How is the master key rotated, and how is a wrong key detected?
5. Who encrypts the existing data, given that the application role sees one cooperative at a time?
6. Where in `members` does encryption happen?
7. Where does a developer's local key come from?

## Decision
- **A new module `privacy`** owns the cipher, the key hierarchy and the start-up checks (public API:
  `PersonalDataCipher`, `FieldRef`, `IndexKind`, `OwnerKeyring`, `PersonalDataUnreadableException`). It depends on
  `tenancy` and `problem`; `members`, `audit` and `imports` depend on it. The JDK's `javax.crypto` only: no crypto
  library and no `pgcrypto`, so keys never travel to the database in a query.
- **Key hierarchy.**
  - One **master key** `NADEL_PD_MASTER_KEY`: 32 random bytes in base64 (`openssl rand -base64 32`), read from the
    environment only, never written to the database, the logs, the repository or a backup. Its **id** is the first 8
    bytes of its SHA-256.
  - Per cooperative, table **`tenant_data_key`** (`tenant_id`, `version`, `data_key_wrapped`, `index_key_wrapped`,
    `master_key_id`, `created_at`; PK `(tenant_id, version)`; RLS `tenant_isolation`; the application role may only
    `SELECT` and `INSERT`): an AES-256 **data key** and an HMAC **index key**, each wrapped (AES-GCM) by the master key.
    Keys are created lazily on first use, in a `REQUIRES_NEW` transaction of the cooperative (the caller's may be
    read-only) with `insert ... on conflict do nothing`. Unwrapped keys are cached per instance for at most 10 minutes,
    never in a shared cache.
  - Data keys are not rotated in this feature (every cooperative has version 1); the version in every sealed value
    leaves room for it. Deleting a cooperative's row makes its personal data unreadable (crypto-shredding, phase 7).
- **Format.** A sealed value is `bytea`: `[0x01 format][u16 big-endian key version][12-byte random nonce][ciphertext ‖
  16-byte tag]` (AES-256-GCM, 128-bit tag). The **associated data** is the three header bytes followed by the UTF-8
  string `tenantId|table|column|rowId` (UUIDs in lower case; table and column names match `[a-z_.]+`, so they never
  contain the separator). A value copied into another row, column, table or cooperative, or with an edited header,
  fails to open: 500 `PERSONAL_DATA_UNREADABLE`, never an empty field that a later save would write back. A multi-valued
  field (phones) is one JSON array sealed once. A wrapped key uses the same layout with key version 0 and the associated
  data `tenant_data_key|tenantId|version|data` (or `|index`).
- **Blind indexes** for exact lookups: HMAC-SHA-256(index key, `kind` ‖ `0x00` ‖ normalised value), 32 bytes, with the
  kinds `EMAIL` (stripped, lower case), `PHONE` (international form of `PhoneNumber`) and `PHONE_LAST4` (the last four
  digits of that form). The kind in the input keeps kinds apart; the per-cooperative key keeps cooperatives apart.
  E-mail uniqueness becomes a unique index on `(tenant_id, email_bidx)`; sign-in and the Excel import match by index.
  Search inside an e-mail or inside the middle digits of a phone is no longer possible.
  - **The last-4 index has only 10⁴ values.** Whoever holds a cooperative's index key can find the last four digits of
    any phone by trying them all. The index key is stored wrapped by the master key, so this needs the master key as
    well as the database; with both, everything is readable anyway. Accepted for the "last 4 digits" search the board
    asked for.
- **Master key rotation.** The owner sets the new key as `NADEL_PD_MASTER_KEY` and the old one as
  `NADEL_PD_MASTER_KEY_PREVIOUS`. At start-up every key wrapped by the previous key is re-wrapped by the current one, one
  transaction per cooperative, and the log says how many (counts only: `Personal data keys: N checked, M re-wrapped, 0
  left on the previous master key`). The owner then removes the previous key.
- **Start-up key-check.** A Flyway callback (`AFTER_MIGRATE`, owner connection, outside a transaction) unwraps every
  key wrapped by the current master key; a key wrapped by neither the current nor the previous key, or one that does
  not unwrap, logs `PD_MASTER_KEY_MISMATCH` and stops start-up before anything is re-wrapped. The server never starts
  with a wrong key and then fails per request. Every profile except `dev` (including the new `prod`) also refuses to
  start without a key or with a malformed one: `NADEL_PD_MASTER_KEY is missing or is not 32 bytes of base64`, never the
  value, without the decoder's exception as cause.
- **Owner-side work runs inside Flyway.** The application role sees one cooperative per transaction (ADR 0002), so
  encrypting the existing data of every cooperative, the start-up key-check, rotation and the demo contacts run on
  Flyway's owner connection: Java migrations (`JavaMigration` beans in the owning module's `internal` package) and
  callbacks. They use `OwnerKeyring` (plain JDBC on the given connection, same formats as the runtime cipher), which,
  like the callback, depends only on the master keys, so registering them with Flyway does not pull JPA into Flyway's
  initialisation. No owner `DataSource` bean exists in the application. Flyway must stay enabled. Schema changes follow
  expand → Java encryption → contract: V21 keys; V22 member columns (expand), Java 22.1 encrypts members, V23 drops the
  plaintext (contract, guarded by a `DO` block that raises if plaintext remains beside a null ciphertext); Java 24 the
  audit values; V25, 25.1 and V26 the import plans.
- **Encryption in `members` happens in its mapper layer**, not in a JPA `AttributeConverter`: a converter sees one
  column value and not the row id the associated data needs. `Member` maps only the `byte[]` columns; ids are
  generated by the application, so they are known before sealing. A `MemberCrypto` component opens and seals the
  contacts and rewrites the phone index on a change; `MemberDirectory.contacts` keeps its signature.
- **Local and test keys.** In the `dev` profile only (and not with `prod`), when the environment has no key, the backend
  reads `NADEL_PD_MASTER_KEY=` from `infra/.env` (or `../infra/.env` when started from `backend/`); when no file exists
  and the directory of the first one does, it writes a random key there with mode 600 and logs a warning with the path
  only. `make up`, `make backend` and `make e2e` create the file first. There is no in-memory-only key (a restart would
  make the local data unreadable) and no committed key, so the secret scanner has nothing to allow. Integration tests
  set a fresh random key per JVM.

## Consequences
- **A lost master key means lost contacts.** The key needs an offline copy kept apart from database backups, and a
  restore drill uses it (`docs/features/backlog/deployment.md`).
- **The release with V23 is not compatible with the previous release**: V23 drops the plaintext columns the previous
  release reads, so a rollback after V23 needs a database restore. Accepted because there is no production data yet;
  stated in the pull request.
- Every read of contacts decrypts in the application; the cost is microseconds per value, and keys are cached for 10
  minutes. Creating a cooperative's first key uses a second pooled connection once.
- Search by contact is exact only (full e-mail, full phone or its last four digits); the member list hint says so.
- The audit log stores sealed values (`{"enc": …}`) and opens them only for readers with `MEMBERS_VIEW`; others see the
  change as hidden. Re-sealing existing audit entries is an owner update of `audit_entry`, the one documented exception
  to its append-only rule.
- Logs never contain a value, a key or a wrapped key; a value that does not open is logged with its table, column and
  row id only.

## Alternatives considered
- **`pgcrypto` in the database**: the key would travel in every query and could appear in `pg_stat_statements` or the
  server log. Rejected by the requirement.
- **A JPA `AttributeConverter`**: no row id, so no binding of a value to its row. Rejected (above).
- **One key for all cooperatives**: no crypto-shredding per cooperative and a larger blast radius. Rejected.
- **Deterministic encryption for search** instead of blind indexes: leaks equality of the whole value and needs a
  second key handling path anyway. Rejected.
- **An owner `DataSource` in the application** for the owner-side work: a second privileged credential in the running
  application. Rejected; Flyway already holds the owner connection at start-up.
- **An in-memory dev key**: local data would become unreadable on every restart. Rejected.

## Non-goals
- Data key rotation (the version field leaves room), crypto-shredding endpoints (phase 7), a key management service.
- Encrypting names, plot data or money.
- Storing a tax number, passport data or a date of birth (still never stored).
