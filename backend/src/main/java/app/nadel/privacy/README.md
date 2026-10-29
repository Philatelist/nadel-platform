# privacy

Encryption of personal data at rest (feature 014, ADR 0016). Members' contacts and notes are stored as AES-256-GCM
sealed values (`bytea`), found by HMAC-SHA-256 blind indexes, and every key below the master key is per cooperative.
The JDK's `javax.crypto` only: no crypto library and no `pgcrypto`, so keys never travel to the database in a query.
Table `tenant_data_key` (V21). No endpoints. Depends on `tenancy` (`TenantContext`) and `problem`
(`PersonalDataUnreadableException` is an `ApiProblemException`).

## Public API

| Type | Use |
|---|---|
| `PersonalDataCipher` | Runtime use inside a cooperative's transaction: `encrypt(tenantId, FieldRef, plaintext)`, `decrypt(tenantId, FieldRef, sealed)`, `blindIndex(tenantId, IndexKind, normalised)`. Refuses (`IllegalStateException`) a `tenantId` that is not `TenantContext.currentTenantId()` |
| `FieldRef(table, column, rowId)` | The place of a value, the associated data of its encryption. Names match `[a-z_.]+` (never `|`); a JSON path such as `changes.email.old` is a column name |
| `IndexKind` | `EMAIL`, `PHONE`, `PHONE_LAST4`, `DOCUMENT_CONTENT` (feature 016: the content hash of an invoice snapshot's canonical form, names in plaintext, so a change is detected without a hash of plaintext names), `TELEGRAM_CHAT` (feature 017: a Telegram chat id as the decimal text Telegram sends, so the bot finds the link of an incoming chat); part of the HMAC input, so kinds never collide |
| `OwnerKeyring` | Owner-side work on Flyway's connection (Java migrations, callbacks): `open(connection).forTenant(tenantId)` gives `encrypt`/`decrypt`/`blindIndex` with the same formats; creates a missing key on that connection, inside the caller's transaction. Depends only on the master keys, never on JPA |
| `PersonalDataUnreadableException` | A value or key that does not open: 500 `PERSONAL_DATA_UNREADABLE`, message without values. Callers never turn it into an empty field, which a later save would write back |

The caller normalises before indexing: e-mail `strip().toLowerCase(Locale.ROOT)`, a phone in the international form of
`PhoneNumber`, last 4 = the last four digits of that form. A multi-valued field (phones) is one JSON array sealed once.

Field references in use: `members` seals each contact as `FieldRef("member", "<column>_enc", memberId)`, the column
being the `bytea` column the value is stored in (`phones_enc`, `email_enc`, `postal_address_enc`,
`telegram_username_enc`, `viber_phone_enc`, `notes_enc`), identically in its Java migration 22.1, its demo contacts
callback and at runtime (see the members README). The audit values of those contacts are sealed as
`FieldRef("audit_entry", "changes.<field>.old" | "changes.<field>.new", entryId)` with the field in snake case
(`changes.postal_address.old`), by `members` when it publishes and by the audit Java migration 24 (see the audit
README). The plan of a pending Excel import preview is sealed as one envelope `{"plan", "digest"}` at
`FieldRef("import_run", "plan_enc", importId)`, by `imports` at runtime and by its Java migration 25.1 (see the imports
README).
The `notifications` module (feature 017) seals the secrets of a cooperative's own delivery channels, which are not
personal data but are kept with the same key (ADR 0018): the mailbox password at
`FieldRef("delivery_settings", "smtp_password_enc", tenantId)` and the bot token at
`FieldRef("delivery_settings", "bot_token_enc", tenantId)` (the row id of `delivery_settings` is the cooperative), and
the chat id of a member's Telegram link at `FieldRef("telegram_link", "chat_id_enc", linkId)` with the blind index
`TELEGRAM_CHAT` in `telegram_link.chat_id_bidx` (see the notifications README).

## Formats

| Item | Format |
|---|---|
| Sealed value | `[0x01 format][u16 big-endian key version][12-byte random nonce][ciphertext ‖ 16-byte tag]` |
| Associated data | the 3 header bytes ‖ UTF-8 `tenantId|table|column|rowId` (UUIDs in lower case) |
| Blind index | HMAC-SHA-256(index key, `kind.name()` ‖ `0x00` ‖ UTF-8 normalised value), 32 bytes |
| Wrapped key | the sealed layout with key version 0; associated data `tenant_data_key|tenantId|version|data` (or `|index`) |
| Master key id | the first 8 bytes of SHA-256 of the master key (`tenant_data_key.master_key_id`) |

## Keys

- **Master key** `NADEL_PD_MASTER_KEY`: 32 random bytes in base64 (`openssl rand -base64 32`), from the environment
  only (`nadel.privacy.master-key`). Never in the database, the logs, the repository or a backup. `MasterKeys`
  validates it by hand: the error is `NADEL_PD_MASTER_KEY is missing or is not 32 bytes of base64` (or the same for
  `NADEL_PD_MASTER_KEY_PREVIOUS`), never the value, and the decoder's exception is never attached.
- **Per cooperative** (`tenant_data_key`, PK `(tenant_id, version)`, RLS `tenant_isolation`; `nadel_app` may only
  `SELECT` and `INSERT`): a data key (AES-256) and an index key (HMAC), each wrapped by the master key. Version 1 for
  every cooperative: data keys are not rotated in this feature, the version in each sealed value leaves room for it.
- **Lazy creation** (`TenantKeyStore`): a cache miss reads the row in the caller's transaction (or a read-only one when
  there is none). A cooperative without a row gets one in a `REQUIRES_NEW` transaction (the caller's may be read-only,
  such as a search or the sign-in) with `insert ... on conflict do nothing`, then reads back whichever row won. The
  cooperative of that transaction comes from `TenantContext`, so `TenantAwareTransactionManager` sets `app.tenant_id`
  and RLS applies. It costs a second pooled connection once per cooperative.
- **Cache** (`TenantKeyCache`): unwrapped keys per cooperative in this instance's memory only, at most 10 minutes after
  loading (injected `Clock`). Never a shared cache.

## Start-up: key-check and rotation (`KeyCheckCallback`)

A Flyway `Callback` bean on `AFTER_MIGRATE`, on Flyway's owner connection and outside a transaction, so it runs on
every start before the application serves anything. **Flyway must be enabled** (the `prod` profile states it). For
each row of `tenant_data_key`:

- wrapped with the current master key: both keys are unwrapped (the key-check);
- wrapped with `NADEL_PD_MASTER_KEY_PREVIOUS`: re-wrapped with the current key, one transaction per cooperative;
- wrapped with any other key, or not unwrapping: an ERROR log line with `PD_MASTER_KEY_MISMATCH` and start-up fails,
  before anything is re-wrapped.

The INFO line has counts only: `Personal data keys: N checked, M re-wrapped, 0 left on the previous master key`. Once it
says 0 left, the owner removes `NADEL_PD_MASTER_KEY_PREVIOUS`. The callback (like `OwnerKeyring`) depends only on
`MasterKeys`, so registering it with Flyway pulls no JPA bean into Flyway's initialisation.

## Profiles and local runs

- `prod` and every profile except `dev`: the key comes from the environment only; start-up fails without it or with a
  malformed one.
- `dev` (not together with `prod`), when the environment has no key (`DevMasterKeySource`): read `NADEL_PD_MASTER_KEY=`
  from the first existing file of `nadel.privacy.dev-key-file` (`infra/.env,../infra/.env`, relative to the working
  directory: the repository root or `backend/`); when none exists and the directory of the first one does, write a
  random key there with mode 600 and a WARN line naming the path (never the key); otherwise fail with a hint to run
  `make up`. `make up`, `make backend` and `make e2e` create `infra/.env` before the backend starts (`infra/README.md`).
  There is no in-memory-only key: the local data would be unreadable after a restart.
- Tests: `IntegrationTest` sets a random key per JVM through `@DynamicPropertySource`, so a test never reads or writes
  `infra/.env`.

## Logging

No value, key or wrapped key is ever logged. A value that does not open logs
`PERSONAL_DATA_UNREADABLE: <table>.<column> of row <id> could not be decrypted` (an internal row id, never a value);
the problem handler logs the 500 with its code.
