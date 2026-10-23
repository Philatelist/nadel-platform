# audit

The append-only audit log: who changed what, when, from which value to which. Table `audit_entry` (RLS
`tenant_isolation`; the application role `nadel_app` can only `SELECT` and `INSERT`, so entries can never be changed or
removed). Read with `GET /audit` (below).

## Public API

| Type | Use |
|---|---|
| `AuditEvent` | A change to record: event id, tenant, time, actor, action, entity type/id/label and the list of changes. `AuditEvent.now(...)` fills a fresh id and the time |
| `AuditBatch` | Up to 500 `AuditEvent`s of one cooperative recorded together (`batchId`, `tenantId`, `entries`); `AuditBatch.of(tenantId, events)` splits a list into batches. Used by the Excel import |
| `Actor` | Member id (nullable), Keycloak subject and display name of whoever made the change |
| `ActorNames` | SPI implemented by `members`: current display names of acting members, so `audit` never depends on `members` |
| `FieldChange` | `field`, `oldValue`, `newValue` as strings. Multi-valued fields (phones, role names) are sorted and joined by the publisher |

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
second entry. `changes` is a JSON array `[{"field", "old", "new"}]` written with Jackson.

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
- `changes` is mapped from the stored `[{field, old, new}]` to `[{field, oldValue, newValue}]`; a null value is absent.

## Values: codes and parameters, never display text

An audit entry stores **codes and parameters**; the client builds the text in the user's language (one UI language, see
the root `CLAUDE.md`). A server never writes an English phrase into `entity_label` or a change value. The formats:

| Where | Value | Client |
|---|---|---|
| Role name, role description, `roles` of a member (`ROLE_*`, `MEMBER_ROLES_CHANGED`; `ROLE` label) | `base:<CODE>` (`CHAIRMAN`, `TREASURER`, `MEMBER`) while a base role keeps its standard name/description, otherwise the stored text. `roles` is such names joined by ", " | catalog name |
| `CHARGE_TYPE` label, `name` old/new, and `chargeType` of `TARIFF_CREATED`, `CHARGE_REVERSED` and `OPENING_BALANCE_ENTERED` | `base:<CODE>` (`MEMBERSHIP`, `ELECTRICITY`, `LAND_TAX`, `WATER`, `COMMON_CONSUMERS`) while a seeded type was never renamed (`nameCustomized` false), otherwise the stored name | catalog default name |
| `TARIFF` label | the tariff name (user content only; the charge type is not part of the label) | as is |
| `METER` and `METER_READING` label | the name of a named meter, the serial number of a plot meter (user content); a `METER_READING` entry's entity id is the meter | as is |
| Meter readings (`initialReading`, `finalReading`, `value` of a reading) | plain decimal with three decimals (`8205.000`) | format meter value |
| `plot` of a meter | the plot number (user content) | as is |
| `replacementMeter` of `METER_REPLACED` | the serial number of the new meter (user content) | as is |
| `CHARGE` label (`CHARGE_REVERSED`, `OPENING_BALANCE_ENTERED`, `NOT_CHARGED_RETRIED`) | the plot number (user content); the entity id is the plot, whose account changed | as is |
| Money amounts (`amount` of a reversal or an opening balance, `total` of a month run, `redAmount` of the settings) | plain decimal with two decimals (`-25.00`, `1500.00`) | format money |
| `billingMonth` of a ledger entry, `month` of a not-charged retry | `YYYY-MM` | format month |
| `BILLING_MONTH` label (`BILLING_MONTH_CLOSED`, field `status` `OPEN` -> `CLOSED`; `BILLING_MONTH_RUN`, fields `count` and `total`) | the month `YYYY-MM`; the entity id is the `billing_month` row | format month |
| `SETTINGS` label | the code `settings` | catalog name |
| `lossesPercent`, `redDays` of the settings, `count` of a month run | plain decimal (`5.00`), integer (`90`) | format number |
| `owners` of `OWNERSHIP_CHANGED` / `OWNERSHIP_CORRECTED` | JSON array of `{"member": "Last First", "share": "1/2"}`; a correction adds `"from"` and, unless the record is open, `"to"` (ISO dates) | parse, format dates and names |
| `effectiveFrom`, `validFrom`, `dueDate`, `installedOn`, `removedOn`, `takenOn`, `serviceFrom`, `serviceTo`, `date` | ISO date `2026-01-01` | format date |
| Enums (`kind`, `basis`/`frequency` of a tariff version, `scope`, `deliveryChannel`, `memberPlotStatusVisibility`, `lossesMode`, `commonConsumerDefaultSplit`, `status` of a billing month, `kind`/`resource` of a meter, `kind`/`reason`/`outcome` of a not-charged retry) | the enum code | catalog |
| `permissions` | permission codes sorted and joined by ", " | the difference: `+ name` per added, `− name` per removed permission |
| Flags (`active`, `archived`, `hasBuilding`) | `true` / `false` | yes/no |
| Numbers (`areaM2` `600.00`, `rate`, `position`, import counts) | plain decimal text | format number |
| Member and plot labels, names, notes, reasons (also `reason` of `READING_REJECTED`, `CHARGE_REVERSED` and `OPENING_BALANCE_ENTERED`), import file name | user content | as is |

A publisher lists only the fields whose value changed; a field with the same old and new value is not written. The one exception is `takenOn` of `READING_REPLACED`, which names the date of the replaced reading.

Entries written before this format keep their old text (the log is append-only and is not migrated): a value that
does not match a format above is shown as stored.

## Things to remember

- Event payloads contain names and contact details. They are never logged: the listener logs the event id (or the batch id) only. Completed
  publications are deleted so personal data does not linger in `event_publication`, which has no `tenant_id` and no RLS on
  purpose (startup resubmission has no tenant); `EventPublicationGuardTests` guards that.
- Listeners and the writer are idempotent; keep it so when adding a listener.
- `Actor.displayName` travels with the event but is not stored: `audit_entry` keeps the member id and subject.

Depends on `tenancy` (public `TenantContext`), `identity` (`CurrentUser` in the controller) and `problem`. Used by
`members`; it never depends on the modules that publish (actor names come through the `ActorNames` SPI).
