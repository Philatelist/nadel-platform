# audit

The append-only audit log: who changed what, when, from which value to which. Table `audit_entry` (RLS
`tenant_isolation`; the application role `nadel_app` can only `SELECT` and `INSERT`, so entries can never be changed or
removed). Read with `GET /audit` (below).

## Public API

| Type | Use |
|---|---|
| `AuditEvent` | A change to record: event id, tenant, time, actor, action, entity type/id/label and the list of changes. `AuditEvent.now(...)` fills a fresh id and the time |
| `Actor` | Member id (nullable), Keycloak subject and display name of whoever made the change |
| `ActorNames` | SPI implemented by `members`: current display names of acting members, so `audit` never depends on `members` |
| `FieldChange` | `field`, `oldValue`, `newValue` as strings. Multi-valued fields (phones, role names) are sorted and joined by the publisher |

A module records a change by publishing an `AuditEvent` with `ApplicationEventPublisher` **inside the transaction that
makes the change**. The Spring Modulith event publication registry (table `event_publication`, `completion-mode: delete`)
stores the publication in that same transaction, so a committed change always has an audit entry owed.

## How an entry is written

`AuditListener.on(AuditEvent)` is annotated `@Async @TransactionalEventListener` and is **not** `@Transactional`. After the
publishing transaction commits it runs on another thread, binds the event's cooperative with
`TenantContext.runAs(event.tenantId(), ...)` and only then opens a transaction with a `TransactionTemplate`, so
`TenantAwareTransactionManager` sets `app.tenant_id` for it. `AuditWriter` inserts the row with a parameterised native
query `insert ... on conflict (id) do nothing`; `audit_entry.id` is the event id, so a redelivered event cannot create a
second entry. `changes` is a JSON array `[{"field", "old", "new"}]` written with Jackson.

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

## Things to remember

- Event payloads contain names and contact details. They are never logged: the listener logs the event id only. Completed
  publications are deleted so personal data does not linger in `event_publication`, which has no `tenant_id` and no RLS on
  purpose (startup resubmission has no tenant); `EventPublicationGuardTests` guards that.
- Listeners and the writer are idempotent; keep it so when adding a listener.
- `Actor.displayName` travels with the event but is not stored: `audit_entry` keeps the member id and subject.

Depends on `tenancy` (public `TenantContext`), `identity` (`CurrentUser` in the controller) and `problem`. Used by
`members`; it never depends on the modules that publish (actor names come through the `ActorNames` SPI).
