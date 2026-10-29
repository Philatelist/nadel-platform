# audit

The append-only audit log: who changed what, when, from which value to which. Table `audit_entry` (RLS
`tenant_isolation`; the application role `nadel_app` can only `SELECT` and `INSERT`, so entries can never be changed or
removed; the one exception is the Java migration 24, below). Read with `GET /audit` (below).

## Public API

| Type | Use |
|---|---|
| `AuditEvent` | A change to record: event id, tenant, time, actor, action, entity type/id/label and the list of changes. `AuditEvent.now(...)` fills a fresh id and the time |
| `AuditBatch` | Up to 500 `AuditEvent`s of one cooperative recorded together (`batchId`, `tenantId`, `entries`); `AuditBatch.of(tenantId, events)` splits a list into batches. Used by the Excel import |
| `Actor` | Member id (nullable), Keycloak subject and display name of whoever made the change |
| `ActorNames` | SPI implemented by `members`: current display names of acting members, so `audit` never depends on `members` |
| `FieldChange` | `field`, `oldValue`, `newValue` as strings and `sealed`. Multi-valued fields (phones, role names) are sorted and joined by the publisher. The 3-argument constructor means `sealed = false`. `sealed = true`: the values are personal data already sealed by the publisher, as base64 text, at `FieldChange.oldValueRef(eventId, field)` / `newValueRef(eventId, field)` (see "Personal values") |

A module records a change by publishing an `AuditEvent` with `ApplicationEventPublisher` **inside the transaction that
makes the change**. The Spring Modulith event publication registry (table `event_publication`, `completion-mode: delete`)
stores the publication in that same transaction, so a committed change always has an audit entry owed.

Changes that touch thousands of records at once (the Excel import: about 7,000 at the limits) publish **`AuditBatch`**
events instead of one `AuditEvent` each: one batch per 500 entries, published inside the same transaction as the data. Each
entry keeps its own event id as the id of its audit entry. Single-record changes (endpoints) keep publishing `AuditEvent`.

## How an entry is written

`AuditListener.on(AuditEvent)` is annotated `@Async @TransactionalEventListener` and is **not** `@Transactional`. After the
publishing transaction commits it runs on another thread, binds the event's cooperative with
`TenantContext.runAs(event.tenantId(), ...)` and only then opens a transaction with a `TransactionTemplate`, so
`TenantAwareTransactionManager` sets `app.tenant_id` for it. `AuditWriter` inserts the row with a parameterised native
query `insert ... on conflict (id) do nothing`; `audit_entry.id` is the event id, so a redelivered event cannot create a
second entry. `changes` is a JSON array `[{"field", "old", "new"}]` written with Jackson; a value of a sealed change is
stored as `{"enc": "<base64>"}` (old and new separately, null stays null).

### How a batch is written

`AuditListener.on(AuditBatch)` has the same shape (`@Async @TransactionalEventListener`, `TenantContext.runAs(batch.tenantId(),
...)`, then a `TransactionTemplate`). `AuditWriter.writeAll` runs one parameterised JDBC batch
(`PreparedStatement.addBatch` / `executeBatch` on the connection of the tenant-aware transaction through
`Session.doWork`), so the whole batch is **one transaction**: row level security applies, and a failure writes nothing
and leaves the publication incomplete for resubmission. The statement is the same `insert ... on conflict (id) do nothing`,
so a redelivered batch (or a batch redelivered after another one half-failed) creates no duplicate. Why batching: one
transaction and one insert per entry made a 7,000-entry import take minutes on a slow runner and starved later events; a
batch of 500 takes tens of milliseconds. The record refuses an empty batch, more than 500 entries and an entry of another
cooperative; `AuditBatchWriteTests` covers the writer, the listener tenant binding, idempotency and redelivery.

### Decision: option A, verified

The design allowed two forms: (A) `@Async @TransactionalEventListener` without `@Transactional`, or (B)
`@ApplicationModuleListener` with an inner `REQUIRES_NEW` transaction template. A transactional listener (B) begins its
transaction before the body runs, when the thread still has no tenant, so it would need the inner transaction anyway.
Option A was verified with `AuditOutboxTests` against the real registry before choosing it:

- a publication row is stored on publish, and is deleted once the listener returns (completion mode `delete`);
- when the listener throws, the publication stays incomplete (`completion_date is null`) and no audit row exists;
- `IncompleteEventPublications.resubmitIncompletePublications` then redelivers it and exactly one entry appears;
- the same event id delivered twice yields one entry.

So A is used; there is no need for the heavier B. Outstanding publications are also resubmitted at startup
(`spring.modulith.events.republish-outstanding-events-on-restart: true`).

## Reading: `GET /audit`

`AuditController` (`@PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")`, so the platform operator and members without the
permission get 403) delegates to `AuditQueries`, one read-only transaction. The cooperative comes from the caller's
profile (the token) and is an explicit `tenant_id` condition on top of RLS.

- One parameterised JPQL query on the read-only entity `AuditRecord`; every filter (`entityType`, `entityId`,
  `actorMemberId`, `from`, `to`) is optional (`cast(:p as ...) is null or ...`) and combines with AND.
- `from` and `to` are calendar dates in `Europe/Kyiv`, both inclusive: the range is
  `occurred_at >= from 00:00 Kyiv` and `occurred_at < (to + 1 day) 00:00 Kyiv`. `from` after `to` gives 400
  `VALIDATION_FAILED` with `errors[{field: "from", code: "INVALID"}]`.
- Order is `occurred_at desc, id desc` (the id makes paging stable); size defaults to 50, at most 100.
- `actorName` is the actor's current name, looked up through the `ActorNames` SPI in one query for the whole page;
  absent when the entry has no actor or the member no longer exists (or belongs to another cooperative).
- `changes` is mapped from the stored `[{field, old, new}]` to `[{field, oldValue, newValue, hidden}]`; a null value is
  absent. A change with a sealed value is opened or hidden (below).

## Personal values (feature 014, ADR 0016)

Personal data in a change never reaches `event_publication` or `audit_entry` in the clear:

- **The publisher seals.** A module that records personal data generates the event id first and seals each value with
  `privacy.PersonalDataCipher` at `FieldRef("audit_entry", "changes.<field>.old" | "changes.<field>.new", eventId)`,
  the field name in snake case (`FieldChange.oldValueRef` / `newValueRef`: `postalAddress` is
  `changes.postal_address.old`). The event carries the base64 text with `sealed = true`, so the outbox holds only
  ciphertext. `members` does this (`MemberAuditPublisher`, used by both the endpoints and the Excel import), for
  `phones`, `email`, `postalAddress`, `telegramUsername`, `viberPhone` and `notes` of `MEMBER` entries, and `ledger`
  for the `payerNote` of `PAYMENT_RECORDED` (`LedgerAuditPublisher.publishPayment`, feature 015).
- **Stored format.** `AuditWriter` writes each sealed value as `{"enc": "<base64>"}`. The entry id is the event id, so
  the place the value was sealed for is the place it is stored at; copied to another entry, field, side (old/new) or
  cooperative it does not open.
- **Read rule.** `AuditController` passes `revealPersonal` = the caller holds `MEMBERS_VIEW` (authority
  `PERM_MEMBERS_VIEW`). `AuditQueries.changesOf` is generic: for any change with an `{"enc"}` value, a reader with
  `MEMBERS_VIEW` gets both values opened with the entry's cooperative and the same `FieldRef`; anyone else gets
  `hidden: true` and neither value (the web shows «приховано» / "hidden"). Changes without a sealed value are shown as
  stored. A sealed value that does not open hides the change too and logs
  `PERSONAL_DATA_UNREADABLE: a sealed value of audit entry <id> could not be opened` (the entry id only, at WARN); the
  page still loads.
- **Java migration 24** (`SealAuditValuesMigration`, a `JavaMigration` bean that Spring Boot hands to Flyway) seals what
  the previous release stored in the clear, on Flyway's owner connection and in its transaction, through
  `privacy.OwnerKeyring` (no JPA):
  - every `audit_entry` of entity type `MEMBER`: each string value of the six fields becomes `{"enc": …}` at the
    entry's `FieldRef`. This **UPDATE as the schema owner is the one documented exception** to the append-only rule:
    it rewrites the form of the values, never what they say, and `nadel_app` still has no `UPDATE` or `DELETE` on the
    table;
  - every unfinished `event_publication` (`completion_date is null`; completed rows are deleted, `completion-mode:
    delete`) whose `event_type` is `app.nadel.audit.AuditEvent` or `app.nadel.audit.AuditBatch`: the serialized
    changes of `MEMBER` events get the six fields' values sealed as base64 with `"sealed": true`, exactly as the
    publisher now sends them.

  Idempotent (a value that is already `{"enc"}`, or a change already `sealed`, is skipped), fixed checksum, counts-only
  log: `Audit values sealed: N of M member entries, K of L pending audit publications`. A publication stored before
  the flag existed and holding no personal field reads with `sealed = false` (`FieldChange.fromJson`).

## Values: codes and parameters, never display text

An audit entry stores **codes and parameters**; the client builds the text in the user's language (one UI language, see
the root `CLAUDE.md`). A server never writes an English phrase into `entity_label` or a change value. The formats:

| Where | Value | Client |
|---|---|---|
| Role name, role description, `roles` of a member (`ROLE_*`, `MEMBER_ROLES_CHANGED`; `ROLE` label) | `base:<CODE>` (`CHAIRMAN`, `TREASURER`, `MEMBER`) while a base role keeps its standard name/description, otherwise the stored text. `roles` is such names joined by ", " | catalog name |
| `CHARGE_TYPE` label, `name` old/new, `chargeType` of `TARIFF_CREATED`, `CHARGE_REVERSED` and `OPENING_BALANCE_ENTERED`, and `chargeTypes` of `RECALCULATION_BOOKED` | `base:<CODE>` (`MEMBERSHIP`, `ELECTRICITY`, `LAND_TAX`, `WATER`, `COMMON_CONSUMERS`) while a seeded type was never renamed (`nameCustomized` false), otherwise the stored name | catalog default name |
| `TARIFF` label | the tariff name (user content only; the charge type is not part of the label) | as is |
| `METER` and `METER_READING` label | the name of a named meter, the serial number of a plot meter (user content); a `METER_READING` entry's entity id is the meter | as is |
| Meter readings (`initialReading`, `finalReading`, `value` of a reading) | plain decimal with three decimals (`8205.000`) | format meter value |
| `plot` of a meter | the plot number (user content) | as is |
| `replacementMeter` of `METER_REPLACED` | the serial number of the new meter (user content) | as is |
| `CHARGE` label (`CHARGE_REVERSED`, `OPENING_BALANCE_ENTERED`, `NOT_CHARGED_RETRIED`) | the plot number (user content); the entity id is the plot, whose account changed | as is |
| Money amounts (`amount` of a reversal or an opening balance, `total` of a month run or a recalculation, `redAmount` of the settings) | plain decimal with two decimals (`-25.00`, `1500.00`) | format money |
| `RECALCULATION` label (`RECALCULATION_BOOKED`, feature 012) | its months `from/to` (`YYYY-MM/YYYY-MM`); the entity id is the `recalculation` row. Fields: `cause` (the cause type, `MANUAL`, `TARIFF_VERSION_ADD`/`_UPDATE`/`_DELETE`, `READING_ENTER`/`_CORRECT`/`_DELETE`), `from` and `to` (`YYYY-MM`), `chargeTypes` (charge type name tokens joined by ", ", only when chosen), `plots` (plot numbers in natural order, only when chosen), `reason` (user content), `count` (lines), `total` (the sum of the differences) | format month; catalog for the cause and the types |
| `REPORT` label (`REPORT_GENERATED`, feature 013) | the report code (`charges-by-month`); the entity id is the same for every run of a report (`UUID.nameUUIDFromBytes("report:<code>")`). Fields, new values only: `report` (the code), `output` (`PREVIEW` or `XLSX`), `filters` (JSON array of `{"param", "value"}` as the query string carries them: ids, codes, `YYYY-MM` months, ISO dates and the street as typed), `rows` (the rows of every sheet, an integer), `personalData` (`true`/`false`). Never the report's contents | catalog name of the report; format the filters |
| `REPORT_TEMPLATE` label (`REPORT_TEMPLATE_UPLOADED`, `REPORT_TEMPLATE_ARCHIVED`, feature 013) | the template's name as entered (user content); the entity id is the template. Fields, new values only: `version` (the version number, an integer: the new one of an upload, the latest one of the archive) and, for an upload, `fileName` (the stored base name, user content). Never the file | as is; format number |
| `INVOICE` label (`INVOICE_REISSUED`, feature 016: an invoice issued on request) | the plot number (user content); the entity id is the plot, whose invoice it is. Fields, new values only: `number` (`YYYY-MM/plot`), `month` (`YYYY-MM`), `version` (an integer) and `due` (two decimals). Never the owners' names (they stay sealed in the invoice) | as is; format month and money |
| `CERTIFICATE` label (`CERTIFICATE_ISSUED`, `CERTIFICATE_ANNULLED`, feature 016: a debt certificate) | the certificate number (`Д-2026-1`); the entity id is the certificate. Fields, new values only: `CERTIFICATE_ISSUED` `plot` (the plot number, user content), `number`, `asOf` (ISO date) and `totalDebt` (two decimals); `CERTIFICATE_ANNULLED` `number` and `reason` (user content). Never the names on the certificate (they stay sealed in it) | as is; format date and money |
| `recalculationId` of a tariff version or reading entry (`TARIFF_VERSION_ADDED`/`UPDATED`/`DELETED`, `READING_ENTERED`/`REPLACED`/`CORRECTED`/`DELETED` booked together with a recalculation) | the id of the recalculation, new value only; the change's entry comes first in the same `AuditBatch`, then `RECALCULATION_BOOKED`. The charges a recalculation books are the ledger and are not repeated in the log | link to the recalculation |
| `billingMonth` of a ledger entry, `month` of a not-charged retry, `from`/`to` of a recalculation | `YYYY-MM` | format month |
| `BILLING_MONTH` label (`BILLING_MONTH_CLOSED`, field `status` `OPEN` -> `CLOSED`; `BILLING_MONTH_RUN`, fields `count` and `total`) | the month `YYYY-MM`; the entity id is the `billing_month` row | format month |
| `BILLING_MONTH` label of `INVOICES_ISSUED` (feature 016: one build of a month's invoices) | the month `YYYY-MM`; the entity id is the same for every build of the month (`UUID.nameUUIDFromBytes("invoices:YYYY-MM")`; the documents module does not read the `billing_month` row). Fields, new values only: `month` (`YYYY-MM`), `built`, `newVersions`, `unchanged`, `skipped` (integers) | format month; format number |
| `SETTINGS` label | the code `settings` | catalog name |
| `DELIVERY_SETTINGS` label (`DELIVERY_SETTINGS_CHANGED`, feature 017) | the code `delivery-settings`; the entity id is the cooperative. Fields with old and new value: `host`, `port`, `security` (`STARTTLS`/`SSL`), `user`, `fromAddress`, `fromName`, `emailRatePerSecond`, `notifyPayments`. A changed secret is the field `password` or `botToken` with `sealed = true` and **neither value**: the history shows only that it changed (never the secret, not even sealed) | catalog name; «змінено» for a secret |
| `BILLING_MONTH` label of `INVOICES_SENT` (feature 017: a send of a month's invoices, or of one invoice, to the members) | the month `YYYY-MM`; the entity id of the month's `INVOICES_ISSUED` (`UUID.nameUUIDFromBytes("invoices:YYYY-MM")`). Fields, new values only: `month` (`YYYY-MM`), `telegram`, `email`, `paper` (the deliveries created per channel, integers) and, for one invoice, `number` (`YYYY-MM/plot`). Only when the send created something; never an address, a chat or a text. Single sends, retries and «Роздруковано» are not audited (they are on the delivery screen) | format month; format number |
| `MEMBER` entries `TELEGRAM_LINKED` / `TELEGRAM_UNLINKED` (feature 017: a member's Telegram linked or unlinked through the cooperative's bot) | the label "Last First" of the member, the entity id the member; the author is the member (a bot action carries the member id and an empty subject) or whoever unlinked in the account. `TELEGRAM_LINKED` has no fields; `TELEGRAM_UNLINKED` has `endReason`, new value only: `UNLINKED` (the member unlinked it in the bot or the account), `BLOCKED` (the member blocked the bot), `MOVED` (the chat was linked to another member). Never a chat id or any Telegram account detail | catalog name; catalog for the reason |
| `lossesPercent`, `redDays` of the settings, `count` of a month run | plain decimal (`5.00`), integer (`90`) | format number |
| `owners` of `OWNERSHIP_CHANGED` / `OWNERSHIP_CORRECTED` | JSON array of `{"member": "Last First", "share": "1/2"}`; a correction adds `"from"` and, unless the record is open, `"to"` (ISO dates) | parse, format dates and names |
| `effectiveFrom`, `validFrom`, `dueDate`, `installedOn`, `removedOn`, `takenOn`, `serviceFrom`, `serviceTo`, `date` | ISO date `2026-01-01` | format date |
| Enums (`kind`, `basis`/`frequency` of a tariff version, `scope`, `deliveryChannel`, `memberPlotStatusVisibility`, `lossesMode`, `commonConsumerDefaultSplit`, `status` of a billing month, `kind`/`resource` of a meter, `kind`/`reason`/`outcome` of a not-charged retry) | the enum code | catalog |
| `permissions` | permission codes sorted and joined by ", " | the difference: `+ name` per added, `− name` per removed permission |
| Flags (`active`, `archived`, `hasBuilding`) | `true` / `false` | yes/no |
| Numbers (`areaM2` `600.00`, `rate`, `position`, import counts) | plain decimal text | format number |
| Member and plot labels, names, notes, reasons (also `reason` of `READING_REJECTED`, `CHARGE_REVERSED`, `OPENING_BALANCE_ENTERED` and `RECALCULATION_BOOKED`), import file name | user content | as is |

A publisher lists only the fields whose value changed; a field with the same old and new value is not written. The one exception is `takenOn` of `READING_REPLACED`, which names the date of the replaced reading.

Entries written before this format keep their old text (the log is append-only and is not migrated): a value that
does not match a format above is shown as stored.

## Things to remember

- Event payloads contain names (contact details only sealed, see above). They are never logged: the listener logs the event id (or the batch id) only. Completed
  publications are deleted so personal data does not linger in `event_publication`, which has no `tenant_id` and no RLS on
  purpose (startup resubmission has no tenant); `EventPublicationGuardTests` guards that.
- Listeners and the writer are idempotent; keep it so when adding a listener.
- `Actor.displayName` travels with the event but is not stored: `audit_entry` keeps the member id and subject.

Depends on `tenancy` (public `TenantContext`), `identity` (`CurrentUser` and `Permission` in the controller), `privacy`
(`PersonalDataCipher`, `FieldRef`, `OwnerKeyring`) and `problem`. Used by `members`; it never depends on the modules that
publish (actor names come through the `ActorNames` SPI, the personal field names of migration 24 are listed in it).
