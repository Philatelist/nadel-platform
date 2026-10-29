# 0007. Domain events through the Spring Modulith outbox; JobRunr for scheduled work

**Status:** accepted

## Context
CLAUDE.md requires cross-module side effects (notifications, recalculations, status cache refresh) to go through events and an outbox. We also need scheduled and long-running work: closing a period for every cooperative, generating and delivering invoices, retrying delivery to external channels, and later polling bank APIs. An early brief assigned both jobs to JobRunr.

## Decision
- **Spring Modulith Event Publication Registry is the outbox.** Modules publish domain events with `ApplicationEventPublisher`; listeners use `@ApplicationModuleListener`. With `spring-modulith-starter-jpa` each event publication is stored in the same transaction as the data change that raised it and redelivered until its listener completes. Incomplete publications are resubmitted on restart.
- **JobRunr (PostgreSQL storage) runs scheduled and retryable jobs**: monthly period close per cooperative, batch invoice generation and delivery, delivery retries with backoff, retroactive recalculation of large ranges, and later bank statement polling.
- A job sets the cooperative explicitly with `TenantContext.runAs(tenantId, ...)`, since there is no user token.
- Listeners and jobs are idempotent: the same event or job may run more than once.

## Consequences
- "Data changed" and "something must happen because of it" are atomic; no lost notifications after a crash.
- JobRunr is not used to pass messages between modules, and Modulith events are not used for schedules.
- Two extra groups of tables appear (Modulith event publications, JobRunr). Both live in the shared schema; JobRunr and event tables hold no tenant data beyond IDs.
- Both dependencies are added when the first feature needs them (period close or notifications), not before.

## Implementation notes (2026-10)
The outbox arrived with spec 002 (members and access control), whose first listener writes the audit log (module `audit`). JobRunr is still not added. Feature 016 (invoices) builds a month's invoices synchronously in the request, in one `REPEATABLE READ` transaction, up to 2,000 plots (ADR 0017, "Synchronous month build and its limit"), so it does not need JobRunr either: JobRunr is deferred to feature 017 (delivery of invoices by e-mail, Telegram and Viber, with retries), whose jobs will call the documents through `documents.DocumentsFacade` under `TenantContext.runAs`.

- **`spring-modulith-starter-jpa` 2.1.1** with `spring.modulith.events.republish-outstanding-events-on-restart: true` and `spring.modulith.events.completion-mode: delete`. The table is created by Flyway (`V3__audit_and_event_publication.sql`, schema copied from the Spring Modulith 2.1.1 jar), not by Spring Modulith.
- **`event_publication` has no `tenant_id` and no RLS, deliberately.** It is an infrastructure table, not cooperative data: incomplete publications are resubmitted at application startup, where no cooperative is bound, so a `tenant_isolation` policy would hide them and events would be lost. This is the one exception to the rule that every table has `tenant_id` and RLS. Because serialized events can contain personal data (names, contacts in field changes), completed publications are deleted rather than kept, payloads stay small, and they are never logged. `EventPublicationGuardTests` asserts that the table has no RLS and that `nadel_app` can complete a publication with no tenant set.
- **Listener tenant handling.** A listener runs on another thread after the publishing transaction commits, with no security context and therefore no tenant. Every event that leads to tenant data carries its `tenantId` (and the actor). The listener binds it with `TenantContext.runAs(event.tenantId(), ...)` and only then opens its transaction with a `TransactionTemplate`, so `TenantAwareTransactionManager` sets `app.tenant_id`. A listener that is itself `@Transactional` (as `@ApplicationModuleListener` is) would begin its transaction before the body binds the tenant, so the audit listener uses `@Async @TransactionalEventListener` without `@Transactional`; `AuditOutboxTests` verifies that the registry still marks such publications complete, keeps them incomplete when the listener throws, and redelivers them. New listeners that touch tenant data follow the same pattern (or use `@ApplicationModuleListener` with an inner `REQUIRES_NEW` transaction opened after `runAs`).
- **Idempotency:** the audit row id is the event id and the insert is `on conflict (id) do nothing`, so a redelivered event writes one entry.
- **Batches for bulk changes.** A change that touches thousands of records (the Excel import, ADR 0012) publishes `audit.AuditBatch` events of at most 500 entries instead of one `AuditEvent` per record; the listener writes a batch with one JDBC batch insert in one transaction, still idempotent by entry id and bound to the batch's tenant with `TenantContext.runAs`. One outbox publication and one transaction per record does not scale to thousands.
- `@EnableAsync` is on `NadelApplication`.
