# 0009. Configurable roles as permission sets per cooperative

**Status:** accepted; implemented in phase 1 by spec 002 (`context/spec/002-members-access/`), see the implementation notes below

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

## Implementation notes (2026-10)
Spec 002 (members and access control) implemented this decision. Where it differs from the text above:

- **Base roles are `CHAIRMAN`, `TREASURER` and `MEMBER`.** Electrician is no longer a base role: an electrician works through a custom role, which gets meter permissions when meters arrive in a later phase. The demo cooperative has a custom role "Electrician" with no permissions yet, held by `electrician@demo.local` together with Member.
- **The member–role table is `member_role`**, not `person_role` (the code name of a member is `Member`, see the glossary). Tables `member`, `role`, `role_permission` and `member_role` are created by `V2__members_and_roles.sql`, all with `tenant_id`, RLS `tenant_isolation` and composite foreign keys on `(tenant_id, id)`, so a role of one cooperative can never be linked to a member of another.
- **Permissions so far** (`identity.Permission`): `MEMBERS_VIEW`, `MEMBERS_EDIT`, `ROLES_MANAGE`, `AUDIT_VIEW`, `PLOTS_VIEW`, `PLOTS_EDIT`, `OWNERSHIP_EDIT`, `IMPORT_RUN`, `SETTINGS_MANAGE` (added by feature 007; `V7__cooperative_settings.sql` re-creates `seed_base_roles` with the extra Chairman row and re-runs it for every cooperative), `TARIFFS_VIEW`, `TARIFFS_MANAGE` (added by feature 009; `V10__charge_types_and_tariffs.sql` re-creates `seed_base_roles` again, with `TARIFFS_VIEW` and `TARIFFS_MANAGE` for the Chairman and `TARIFFS_VIEW` for the Treasurer, but gives **existing** cooperatives the new rows by targeted inserts only, because re-running `seed_base_roles` would restore a permission a chairman had removed from a Treasurer role; a write needs both `TARIFFS_VIEW` and `TARIFFS_MANAGE`). The examples above (`PAYMENTS_ALLOCATE`, `METERS_ENTER`, ...) are added with the features that need them.
- **Base role seeding:** the SQL function `seed_base_roles(tenant)` inserts the three base roles with deterministic ids and their permissions (Chairman: all eleven, Treasurer: `MEMBERS_VIEW`, `PLOTS_VIEW`, `AUDIT_VIEW`, `TARIFFS_VIEW`, Member: none) with `ON CONFLICT DO NOTHING`, so it never overwrites a cooperative's changes. `BaseRolesMatrixTests` compares it with the Java definition.
- **Chairman always has every permission**, resolved in code (`PermissionUnion`), so a permission added later needs no migration for the chairman. Base roles cannot be renamed or deleted and Chairman's permissions cannot be changed (409 `BASE_ROLE_IMMUTABLE`); Treasurer and Member permissions are editable with `ROLES_MANAGE`. A cooperative always keeps at least one active chairman (409 `LAST_CHAIRMAN`, guarded by a per-tenant advisory transaction lock).
- **Keycloak keeps identity, the `tenant_id` claim and `PLATFORM_OPERATOR`** and nothing else: the dev realm no longer has the realm roles `CHAIRMAN`, `TREASURER`, `ELECTRICIAN`, `MEMBER`. On every request `identity`'s `MemberAuthenticationConverter` resolves the token to the active member of the token's cooperative (by Keycloak subject, or on first sign-in by verified email of an unlinked member) and turns the union of that member's role permissions into `PERM_*` authorities. Nothing is cached, so a role change applies on the next request. A token with no active member gets 403 `MEMBER_NOT_LINKED`; the platform operator is resolved without database access (ADR 0008).
- Every change to members, roles and role assignments is written to the audit log through domain events (module `audit`, ADR 0007).
