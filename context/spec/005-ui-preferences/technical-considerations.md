# Technical Specification: UI Preferences

- **Functional Specification:** [functional-spec.md](functional-spec.md) (source: `docs/features/005-ui-preferences.md`)
- **Status:** Completed
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

- **Theme (web only):** a per-browser choice in `localStorage` (`nadel.theme`), applied as `data-theme` on `<html>` by a
  tiny inline script in `index.html` before first paint and by a React provider afterwards. Dark tokens apply under
  `:root[data-theme='dark']` and under `prefers-color-scheme: dark` unless `data-theme='light'`.
- **Header (web only):** cooperative name (`me.tenant.name`) as the serif title link; product name as a muted label;
  `useDocumentTitle` sets "<page> — <cooperative>".
- **Role descriptions (full stack):** mirrors 004 names — `role.description_customized`, `RoleInput.useDefaultDescription`,
  `RoleDetails.descriptionCustomized`, catalog texts in i18n, audit token `base:<CODE>` mapped by field on the web.

---

## 2. Proposed Solution & Implementation Plan

### 2.1 Theme

| Item | Change |
|---|---|
| `web/src/theme.ts` | `THEMES = ['system','light','dark']`, `ThemeChoice`, `STORAGE_KEY = 'nadel.theme'`, `readTheme()` (try/catch, invalid → `system`), `writeTheme()` (try/catch, swallow), `applyTheme()` (set/remove `data-theme`, update `theme-color` metas) |
| `web/src/themeContext.tsx` (provider) | `ThemeProvider`/`useTheme` like `I18nProvider`; wrapped in `main.tsx` |
| `web/index.html` | inline IIFE in `<head>` (English comment, try/catch): reads `nadel.theme`, sets `data-theme` for `light`/`dark`, sets the `theme-color` meta; `<title>` stays the product name |
| `web/src/index.css` | light tokens in `@theme`; dark tokens in `:root[data-theme='dark']` and `@media (prefers-color-scheme: dark) { :root:not([data-theme='light']) }` (identical bodies, `color-scheme: dark`); `:root[data-theme='light'] { color-scheme: light }`; `:root { color-scheme: light dark }` |
| `web/src/theme.test.ts` | both dark blocks equal the dark tokens and each other; script/meta colours equal `palette.*.background` |
| `web/src/components/ThemeSwitch.tsx` | clone of `LocaleSwitch` (`role="group"`, `aria-label` `t.nav.theme`, `aria-pressed` buttons, `min-h-11`, `rounded-control`) |
| `Layout.tsx` | with a menu: switch in the header from `md` up and inside `#app-menu` below `md`; without a menu: in the header always |
| i18n | `nav.theme`, `theme.{system,light,dark}` (uk "Системна", "Світла", "Темна") |

Risk: a future Content-Security-Policy must allow the inline script (hash). Documented in `web/README.md`.

### 2.2 Header and tab title

- `Layout.tsx`: `<Link to="/" title={name} className="… truncate font-serif …">{name}</Link>` with
  `name = me.tenant?.name ?? t.app.name`; when a tenant exists, `t.app.name` as a muted non-link label.
- `web/src/useDocumentTitle.ts`: `useDocumentTitle(page?)` → `[page, cooperative ?? t.app.name].filter(Boolean).join(' — ')`;
  reads the tenant through a non-throwing `useOptionalMe` (exported from `me.tsx`). Called by Members, Member, Member
  form, Roles, Audit, Account, Not found, No access; `SignIn` and `FullPageMessage` produce "<title> — Nadel"/"Nadel".

### 2.3 Role descriptions

**Data — `V5__role_description_customized.sql`**

| Table | Change |
|---|---|
| `role` | `description_customized boolean not null default false`; `true` where `base_code is null`; `true` for base roles whose stored description differs from the V2 seed text (keeps descriptions edited since 002 visible) |

No CHECK constraint (expand/contract); `Role.isDescriptionCustomized()` = `baseCode == null || flag`.

**API**

| Item | Change |
|---|---|
| `RoleInput.useDefaultDescription` | optional boolean; base roles only; restores the seed description and ignores `description`; custom role → 400 `VALIDATION_FAILED`, field `useDefaultDescription`, code `INVALID` |
| `RoleDetails.descriptionCustomized` | required boolean |
| `RoleRef` | unchanged (no description) |

**Backend:** `BaseRoles.Definition` gains the seed description + `defaultDescription(code)`; `Role.edit(name, nameCust,
description, descCust, now)`; `RoleCommands.update` — name and description restores independent and combinable;
`descCustomized = changed || already` (exact equality after `blankToNull`); flag-only changes persisted without audit;
`RoleSnapshot.auditDescription` → `base:<CODE>` for a non-customised base description; `RoleDtoMapper` sets the flag.

**Web:** `roleDescription(role, t)` in `roleName.ts`; `descriptionToSubmit` (stored description when the catalog text is
untouched); RolesPage list + form prefill + "Повернути стандартний опис" / "Restore standard description" button
(`baseCode && descriptionCustomized`, sends `useDefaultDescription: true` with the stored name and current permissions);
`auditRoleValue(value, t, field)` maps `base:<CODE>` for `description` to `t.roleDescriptions[code]`;
i18n `roleDescriptions.{CHAIRMAN,TREASURER,MEMBER}` with the texts from the feature doc, `rolesPage.restoreDescription`.

---

## 3. Impact and Risk Analysis

- Shared test DB: four cleanups must also reset `description_customized` (`RolesTests.restoreBaseRoles`,
  `RoleNamesAcceptanceTests`, `MembersAccessAcceptanceTests`, `SeedBaseRolesTests`).
- `RoleDetails` constructor and `BaseRoles.Definition` change → compile fixes in tests.
- E2E: `design-system.spec.ts` asserts the "Наділ" link; header tests need updating.
- Theme CSS duplication is unavoidable without a build step; the sync test guards it.

## 4. Testing Strategy

- Web unit: theme storage (restore, invalid, throwing storage → system), `applyTheme`, ThemeSwitch, Layout (title link,
  fallback, switch placement), `useDocumentTitle`, `roleDescription`, `descriptionToSubmit`, `auditRoleValue` by field,
  theme CSS sync.
- Backend: `RolesTests` description cases (change, restore, no-op restore, custom 400, permission-only save, blank, both
  restores, independence of name/description, 403), `RoleLogicTests.auditDescription…`, `BaseRolesMatrixTests`,
  `SeedBaseRolesTests`, migration rule check, acceptance flow.
- E2E: dark ↔ light, reload keeps the choice, no-flash check (`data-theme` present at `DOMContentLoaded`), phone
  placement, header title and tab title, Ukrainian role descriptions, description restore.
- Contrast test unchanged and green; `make check`; coverage floor raised to the measured value rounded down.

## Assumptions (recorded, not confirmed)

- V5 also flags base roles with already-edited descriptions as customised.
- Separate description restore; both restores may be combined in one request.
- Theme switch rendered twice for users with a menu (header ≥ 768 px, menu < 768 px); only one is visible.
- Pages without a title show only the cooperative name in the tab.
