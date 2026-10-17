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
