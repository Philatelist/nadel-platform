# Technical Specification: Role Names

- **Functional Specification:** [functional-spec.md](functional-spec.md) (source: `docs/features/004-role-names.md`)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

Extends the `members` module (002). Contract first, one Flyway migration, no new table.

1. `role.name_customized` marks a base role whose name was changed (always true for custom roles).
2. `PUT /roles/{id}` accepts renames of base roles; `useDefaultName: true` restores the seed name. `BASE_ROLE_IMMUTABLE`
   remains only for deleting base roles and changing Chairman permissions.
3. `RoleRef` and `RoleDetails` carry `nameCustomized`; clients show the catalog name for base roles with
   `nameCustomized = false`, the stored name otherwise.
4. Audit values for a non-customised base-role name are written as the token `base:<BASE_CODE>`; the web renders the
   token as the catalog name in the viewer's language (Ukrainian text may live only in the i18n catalogs).
5. Web: editable name for every role, base-role hint, restore button, on-screen-name clash warning, audit token
   rendering, uk `TREASURER` = "Казначей".

---

## 2. Proposed Solution & Implementation Plan

### 2.1 Data — `V4__role_name_customized.sql`

| Table | Change |
|---|---|
| `role` | `name_customized boolean not null default false`; backfill `true` where `base_code is null` |

- No CHECK constraint (expand/contract: the previous release inserts custom roles without the column).
  `Role.isNameCustomized()` returns `baseCode == null || nameCustomized`; `Role.custom(...)` sets it.
- `seed_base_roles()` unchanged (inserts with the default, `ON CONFLICT DO NOTHING`). RLS, policy and index unchanged.
- Demo data: custom roles in `R__demo_data.sql` set `name_customized = true` (consistency on a fresh DB).

### 2.2 API — `api/openapi.yaml` (then `make gen`)

| Item | Change |
|---|---|
| `RoleInput` | optional `useDefaultName: boolean` (default false); description: base roles only, restores the standard name and ignores `name`; on a custom role → 400 `VALIDATION_FAILED`, field `useDefaultName`, code `INVALID` |
| `RoleRef` | required `nameCustomized: boolean` |
| `RoleDetails` | required `nameCustomized: boolean` |
| `PUT /roles/{id}` | any role can be renamed (unique per cooperative ignoring case, 409 `ROLE_NAME_TAKEN`, also on restore when another role holds the seed name); 409 `BASE_ROLE_IMMUTABLE` only for Chairman permission changes |
| `DELETE /roles/{id}` / problem text | `BASE_ROLE_IMMUTABLE` for deleting base roles |

### 2.3 Backend (`app.nadel.members.internal`, `app.nadel.identity`)

- `BaseRoles.Definition` gains the seed `name` ("Chairman", "Treasurer", "Member"); `BaseRoles.defaultName(code)`.
  `BaseRolesMatrixTests` asserts Java names equal the SQL seed names.
- `RoleCommands.update` order: find → `useDefaultName` on custom role → 400 → target name (`useDefaultName` → seed name,
  `customized=false`; else `cleanName` and `customized = changed || already customised`) → Chairman permission lock
  → `ensureNameFree(tenant, name, selfId)` for every role when the name changes → diff → persist (flag-only changes
  persisted without audit) → audit when the snapshot diff is not empty.
- Equality is exact on the trimmed name: unchanged name = no rename (no audit, flag unchanged); a case-only change is a
  rename.
- `RoleSnapshot.auditName(name, baseCode, customized)` → `base:<CODE>` for non-customised base roles, else the name.
  Used for the `name` field old/new values and the `ROLE_UPDATED` entity label; `MemberRoleCommands` uses it for the
  `roles` field values too.
- Plumbing `nameCustomized`: `Role`, `identity/RoleRef` record, `RoleGrant`, `RoleGrantRow`, `MemberRoleRow`,
  `MemberRepository` JPQL constructors, `RoleGrantReader`, `MemberDtoMapper`, `RoleDtoMapper`, `MeController`.
- Accepted risk: a custom role literally named `base:MEMBER` renders as the token's catalog name; documented.

### 2.4 Web

| File | Change |
|---|---|
| `web/src/roleName.ts` | pure `roleDisplayName(role, t)` (catalog name when `baseCode && !nameCustomized`), `useRoleName`; `auditRoleValue(value, t)` mapping `base:<CODE>` to the catalog name (also each part of a `", "`-joined `roles` value) |
| `web/src/roleNameClash.ts` | pure `findNameClash(name, others, display, locale)`: trimmed, locale-lowercased comparison with other roles' displayed names |
| `web/src/pages/RolesPage.tsx` | name editable for all roles; hint "Base role: {name}" for base roles; "Restore standard name" secondary button when `baseCode && nameCustomized` (sends `useDefaultName: true`); Chairman Save enabled (permissions locked); clash warning as `Notice` (warning, not blocking); **if the name field still shows the untouched catalog name of a non-customised base role, submit the stored `role.name`** |
| `web/src/pages/AuditPage.tsx` | render `name`/`roles` values and ROLE entity labels through `auditRoleValue` |
| `packages/shared/src/i18n` | uk `roles.TREASURER` = "Казначей"; new `rolesPage.baseHint`, `rolesPage.restoreName`, `rolesPage.nameClash`; `problem.BASE_ROLE_IMMUTABLE` text (en+uk) updated |
| fixtures | `nameCustomized` in `Layout.test.tsx` and other role fixtures |

Mobile: no role display today — nothing to change.

### 2.5 Docs

- `docs/features/002-members-access.md` §2.2 rule note ("cannot be renamed" superseded by 004), `members`/`identity`
  module READMEs (flag, audit token), `docs/domain/glossary.md` if it defines base role names.

---

## 3. Impact and Risk Analysis

- **Dependencies:** generated API client (CI checks it is regenerated); every `RoleRef` constructor in main and tests.
- **Risks & mitigations:**
  - Shared test database: `RolesTests` cleanup must reset base names and `name_customized`.
  - Accidental rename on permission-only saves from the web: submit the stored name when the catalog name is untouched;
    covered by a backend test (stored name keeps non-customised) and a web unit test.
  - E2E relies on English base names: every rename in e2e is restored in `finally`.
  - Audit token leaking into UI: unit tests for `auditRoleValue`; e2e asserts the audit entry text.

---

## 4. Testing Strategy

- Backend (Testcontainers): `RolesTests` (rename, restore, uniqueness incl. Cyrillic case, trim no-op, case-only, 400
  `useDefaultName` on custom, 403 without `ROLES_MANAGE`, audit values/token, list flags), `SeedBaseRolesTests`,
  `BaseRolesMatrixTests`, `RoleLogicTests` (auditName), `MembersAccessAcceptanceTests`
  `renamingEveryBaseRoleDoesNotChangeWhoCanDoWhat` (reruns existing permission checks after renaming all three).
- Web (vitest): `roleName.test.ts`, `roleNameClash.test.ts`, RolesPage restore/stored-name behaviour where feasible.
- E2E: base-role name enabled; rename → restore flow; audit shows "Treasurer → Bookkeeper".
- `make check`; raise `coverage.minimum` to the measured line coverage rounded down to two decimals.

## Assumptions (recorded, not confirmed)

- Audit token `base:<CODE>` rendered client-side; also used for member-role audit values.
- Restore resets the stored name to the seed name and can hit `ROLE_NAME_TAKEN`.
- `useDefaultName` on a custom role → 400 field `useDefaultName` / `INVALID`.
- No CHECK constraint on the flag; getter rule instead.
- Mobile unchanged.
