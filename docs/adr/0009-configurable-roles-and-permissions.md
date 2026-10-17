# 0009. Configurable roles as permission sets per cooperative

**Status:** accepted

## Context
Every cooperative has a chairman, a treasurer, members and often an electrician, but many also have people with partial admin duties (a secretary who maintains the member list, an auditor who only reads). The product must let a chairman create such roles without code changes.

## Decision
- The application defines a **fixed list of permissions** in code (for example `PLOTS_EDIT`, `MEMBERS_EDIT`, `TARIFFS_EDIT`, `METERS_ENTER`, `METERS_APPROVE`, `PAYMENTS_ALLOCATE`, `PERIOD_CLOSE`, `INVOICES_SEND`, `REPORTS_VIEW`, `ROLES_MANAGE`).
- A **role is a named set of permissions stored per cooperative** (tables `role`, `role_permission`, `person_role`, all tenant-scoped with RLS). Base roles `CHAIRMAN`, `TREASURER`, `ELECTRICIAN`, `MEMBER` are seeded for every cooperative and cannot be deleted; the chairman can add custom roles.
- **Keycloak keeps only identity**: who the user is, the `tenant_id` claim, and the coarse realm role (`PLATFORM_OPERATOR` versus everyone else). Fine-grained permissions are resolved by the backend from the database on each request and exposed as Spring authorities (`PERM_PAYMENTS_ALLOCATE`).
- Code checks permissions, never role names: `@PreAuthorize("hasAuthority('PERM_PAYMENTS_ALLOCATE')")`.
- Granting or changing roles requires `ROLES_MANAGE` and is audited.

## Consequences
- New admin roles need no code change and no Keycloak change.
- A new permission is a code change (enum + migration seeding it into base roles where appropriate).
- The existing realm roles `CHAIRMAN`, `TREASURER`, `ELECTRICIAN`, `MEMBER` remain in the dev realm for convenience until this ADR is implemented in phase 1; after that the database is the source of truth for them.
