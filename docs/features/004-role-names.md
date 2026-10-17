# 004. Role names

Small follow-up to 002. Input for `/feature-pipeline`. Requires 002 (roles, audit) and 003 (UI kit).

## 1. Goal

The chairman can rename every role of the cooperative, the base roles included, in the same place where permissions
are edited. Cooperatives use different words for the same job (for example "Скарбник" or "Казначей"), and today only
custom roles can be renamed; everything else about a role can already be changed.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/glossary.md`, ADRs 0004 and 0009, and `docs/features/002-members-access.md` sections 2.2–2.3 (this feature
changes the "cannot be renamed" rule of 2.2).

Feature sequence: `002 Members and access control` → `003 UI design system` → `004 Role names` → `005 Plots registry` →
`006 Ownership` → `007 Excel import`.

General rules from 002 apply: contract-first, Flyway migration only, permissions not role names, Problem Details with
a `code`, Ukrainian by default with English available, admin changes audited through the outbox, tests per rule.

## 2. Rules

- A member with `ROLES_MANAGE` can rename any role, base or custom. Name: trimmed, 1–60 characters, unique per
  cooperative ignoring case (existing `ROLE_NAME_TAKEN`).
- Everything else about base roles stays as in 002: they cannot be deleted, the Chairman role always has all
  permissions, and the system logic keeps using `baseCode`, never the name.
- A base role that was never renamed shows its default name from the i18n catalog in the user's language (as today).
  Once renamed, it shows the stored name in every language.
- "Restore default name" on a renamed base role returns it to the catalog name.
- Uniqueness is checked against the stored names. The default display names of base roles are not stored (the seed
  keeps English names), so the web form also warns, before saving, when the new name equals the name another role is
  shown under in the current language.
- Every rename and every restore is audited (`ROLE_UPDATED`, field change `name`, old and new display name).
- The Ukrainian default name of `TREASURER` changes from "Скарбник" to "Казначей". English stays "Treasurer".

## 3. Worked examples

| Situation | Result |
|---|---|
| Chairman renames the Treasurer role to "Бухгалтер" | Shown as "Бухгалтер" in Ukrainian and English; audit entry name: "Казначей" → "Бухгалтер" |
| Chairman renames the Member role to "Садівник" while a custom role "садівник" exists | 409 `ROLE_NAME_TAKEN`, field error on `name` |
| Chairman renames a custom role "Електрик" to "Електрик" with a trailing space | Saved as "Електрик" (trimmed), no audit entry for the name (unchanged) |
| Restore default name of the renamed Treasurer role | Shown as "Казначей" in Ukrainian, "Treasurer" in English |
| Treasurer without `ROLES_MANAGE` tries to rename | 403 |

## 4. API (`api/openapi.yaml`)

- `PUT /roles/{id}` accepts a new name for base roles; the `BASE_ROLE_IMMUTABLE` rule remains only for deleting base
  roles and for changing Chairman permissions. Update the operation description.
- `RoleInput` gets an optional boolean `useDefaultName`; for a base role `true` restores the catalog name and the
  submitted `name` is ignored; for a custom role it is a 400 validation error.
- `RoleRef` and `RoleDetails` get a required boolean `nameCustomized` (always `true` for custom roles).
- Run `make gen`; clients use `nameCustomized` to choose between the stored name and the catalog name.

## 5. Data

New migration: `role.name_customized boolean not null default false`; existing custom roles are set to `true`. No other
schema change; RLS is unchanged (existing table).

## 6. UI

- Roles page (styled by 003): the name field is editable for every role. For a base role, a hint under the field shows
  "Базова роль: <catalog name>" and, when renamed, a secondary button "Повернути стандартну назву".
- `useRoleName` (web) and any mobile equivalent show `role.name` when `nameCustomized` is true, otherwise the catalog
  name by `baseCode`.
- Member pages, the account page and the audit log show the new names everywhere a role is listed.

## 7. Permissions

Only `ROLES_MANAGE` (Chairman by default) renames roles.

## 8. Acceptance criteria

- [ ] Every rule in section 2 and every row of section 3 is covered by a test (backend integration tests; web unit
      tests for `useRoleName` and the duplicate-name warning).
- [ ] Renaming a base role does not change who can do what: an existing permission test passes unchanged after a
      rename of every base role.
- [ ] The audit log shows renames and restores with old and new names.
- [ ] Uk catalog: `TREASURER` is "Казначей"; E2E specs that select roles by name are updated.
- [ ] `make check` green; coverage floor raised to the new measured level.
