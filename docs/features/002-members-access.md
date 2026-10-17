# 002. Members and access control

Roadmap phase 1, feature 1 of 4. Input for `/feature-pipeline`.

## 1. Goal

Give a cooperative its people and decide who may do what. After this feature a chairman can add members, define admin
roles, assign them, and every admin action is recorded. A signed-in user is linked to their member record and gets
permissions from the database. Plots and ownership come in features 003 and 004.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/glossary.md`, `docs/domain/money-rules.md` and ADRs 0002, 0003, 0004, 0007, 0008, 0009.

Feature sequence in roadmap phase 1 (each one builds on the previous and leaves `main` working):
`002 Members and access control` → `003 Plots registry` → `004 Ownership` → `005 Excel import`.
The map is not part of phase 1 (roadmap phase 6): plots are records without polygons.

General rules for every feature in this sequence:
- All new tables are tenant-scoped: `tenant_id uuid not null`, RLS with a `tenant_isolation` policy, index on
  `tenant_id`, and an isolation test. Schema changes only through a new Flyway migration (never edit existing ones).
- Contract-first: change `api/openapi.yaml`, run `make gen`, implement the generated interfaces.
- Authorization through permissions (`PERM_*`), never role names (ADR 0009). The server is the authority; the UI only
  hides actions.
- Errors use Problem Details (RFC 9457) with a machine-readable `code` and field errors for validation.
- Web UI: Ukrainian by default, English available; all text through `@nadel/shared` i18n; empty, loading and error
  states on every screen.
- Admin changes are audited through domain events and the Modulith outbox (ADR 0007).
- Tests: unit tests for domain rules, Testcontainers integration tests, isolation test per new table, allowed and denied
  permission tests per endpoint, `ModularityTests` green, raise `coverage.minimum` to the new measured level.
- No mobile changes in phase 1.
- Do not store tax numbers (РНОКПП), passport data or dates of birth.

## 2. Permissions and roles (implements ADR 0009)

### 2.1 Permissions (fixed list in code)

| Permission | Allows | Used from |
|---|---|---|
| `MEMBERS_VIEW` | See all members and their contact details | 002 |
| `MEMBERS_EDIT` | Create, edit, archive members | 002 |
| `ROLES_MANAGE` | Create, edit, delete custom roles; assign roles to members | 002 |
| `AUDIT_VIEW` | Read the audit log | 002 |
| `PLOTS_VIEW` | See all plots with details | 003 |
| `PLOTS_EDIT` | Create, edit, archive plots | 003 |
| `OWNERSHIP_EDIT` | Add, change and correct ownership | 004 |
| `IMPORT_RUN` | Run the Excel import | 005 |

Create all eight now so roles can be configured once. Design the enum and seeding so that a later permission
(`TARIFFS_EDIT`, `PAYMENTS_ALLOCATE`, ...) is an enum value plus a migration that grants it to base roles where needed.

### 2.2 Base roles (seeded for every cooperative; cannot be deleted or renamed)

| Role | Permissions |
|---|---|
| `CHAIRMAN` | all permissions, always (cannot be reduced) |
| `TREASURER` | `MEMBERS_VIEW`, `PLOTS_VIEW`, `AUDIT_VIEW` |
| `MEMBER` | none; sees only own data |

The chairman may change the permissions of all roles. At least one active member must hold
`CHAIRMAN`: the system refuses to remove that role from, or archive, the last chairman.

### 2.3 Custom roles

A member with `ROLES_MANAGE` creates roles with a name, a description and any subset of permissions, for example
"Secretary" = `MEMBERS_VIEW`, `MEMBERS_EDIT`, `PLOTS_VIEW`, `PLOTS_EDIT`, `OWNERSHIP_EDIT`. A member may hold several
roles; their permissions are the union. Deleting a role still assigned to someone is refused. Role names are unique per
cooperative.

### 2.4 Linking sign-in to members (ADR 0003)

Keycloak stays the identity provider. A member has an optional email. On an API call from a signed-in user with a
`tenant_id` claim, the backend finds the active member of that cooperative with the same email (case-insensitive),
stores the Keycloak `sub` on the member on first call, and resolves permissions from the database for that member as
`PERM_*` authorities. A signed-in user with no matching member gets 403 with code `MEMBER_NOT_LINKED`.
`PLATFORM_OPERATOR` stays a Keycloak realm role and has no cooperative data access (ADR 0008).
Creating Keycloak accounts and sending invitations are out of scope.

Update the dev Keycloak realm and demo data so each demo user maps to a demo member with the matching base role.

### 2.5 What a member sees

A member with only the `MEMBER` role sees their own member card (read-only) and nothing about other members: other
members' endpoints return 404 for them. Enforced in the API, not only in the UI.

## 3. Data

### 3.1 Member

| Field | Rules |
|---|---|
| `last_name`, `first_name` | required |
| `middle_name` | optional (по батькові) |
| `phones` | zero or more, normalized to E.164 (`+380XXXXXXXXX`); `0XXXXXXXXX` is accepted and normalized |
| `email` | optional; unique per cooperative among active members; used for sign-in linking |
| `postal_address` | optional free text |
| `delivery_channel` | preferred invoice channel: `EMAIL`, `TELEGRAM`, `VIBER`, `PAPER`; must have the matching contact (email, Telegram username, Viber phone); default `PAPER` |
| `telegram_username`, `viber_phone` | optional, used later by notifications |
| `keycloak_subject` | set by the backend on first sign-in |
| `notes` | optional, visible only with `MEMBERS_VIEW` |
| `archived_at` | soft archive; archived members are hidden by default and cannot sign in; history is kept |

### 3.2 Roles

Tables for roles, role permissions and member roles (names are the implementer's choice). Base roles are created for
existing tenants by migration and for new tenants by the tenant creation code.

### 3.3 Audit log (new `audit` module)

Every create, update and archive of members, roles and role assignments writes an entry: actor (member id and Keycloak
`sub`), timestamp, action, entity type and id, changed fields with old and new values. Modules publish domain events;
`audit` listens through the outbox (`@ApplicationModuleListener`, `spring-modulith-starter-jpa`). The audit table is
append-only for `nadel_app` (no UPDATE/DELETE grants). Personal data may appear in audit values (protected by RLS and
`AUDIT_VIEW`) but never in application logs. Features 003–005 reuse this mechanism.

## 4. API

- `GET /me` extended: `memberId`, `permissions[]`, `roles[]`.
- `GET /members` (search by name, phone, email; include archived flag; paging), `GET /members/{id}`, `POST /members`,
  `PUT /members/{id}`, `POST /members/{id}/archive`, `POST /members/{id}/restore`.
- `GET /permissions`, `GET /roles`, `POST /roles`, `PUT /roles/{id}`, `DELETE /roles/{id}`,
  `PUT /members/{id}/roles`.
- `GET /audit` (filters: entity type, entity id, actor, date range; paging).

## 5. Web UI

1. **Members list**: name, phones, email, delivery channel, roles; search, archived filter, paging.
2. **Member card**: view and edit (with `MEMBERS_EDIT`), archive/restore, roles assignment (with `ROLES_MANAGE`).
3. **Roles screen**: base and custom roles, permission checkboxes, create/edit/delete custom roles.
4. **Audit log screen** (with `AUDIT_VIEW`).
5. **My account** for every user: own member card, own roles and permissions.
6. Navigation shows items by permission. The demo map page is removed from navigation until phase 6.

## 6. Tests (in addition to the general rules)

- Unit: phone normalization, permission union, last-chairman rule, delivery channel requires matching contact.
- Integration: sign-in linking by email (match, no match → 403, archived member → 403); member visibility for `MEMBER`;
  audit entries written through the outbox and survive a listener failure (redelivery).
- Set up Playwright for the web app with one E2E test: sign in as treasurer, open the members list, open a member card;
  sign in as member and verify the members list is not reachable. Run it in CI against the docker-compose stack.
- Demo data: about 35 members, the base roles assigned to the demo users, a custom role "Secretary".

## 7. Out of scope

Plots and ownership (003, 004), Excel import (005), member self-service editing, Keycloak account creation and
invitations, people in several cooperatives, notifications delivery.

## 8. Decisions already made (do not ask)

- Roles and permissions live in the database per ADR 0009; Keycloak keeps identity, `tenant_id` and `PLATFORM_OPERATOR`.
- Sign-in links to a member by email within the cooperative.
- Soft archive for members; audit records are never deleted.
- All eight phase 1 permissions are created now.
