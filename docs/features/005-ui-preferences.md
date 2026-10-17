# 005. Theme switch, cooperative name in the header, translated role descriptions

Small UI feature after the first review of 003 and 004 by the owner. Input for `/feature-pipeline`. Requires 003 (UI
kit, light and dark tokens) and 004 (`nameCustomized` on roles).

## 1. Goal

Three things the owner asked for after seeing the app:

1. A theme switch, so a user can choose light or dark instead of always following the system.
2. The cooperative's name as the main title of the header, as in the design reference.
3. Base role descriptions in the user's language (today they show the English seed text: "Full access", "Own data only").

## Context for the pipeline

Read together with `CLAUDE.md`, `docs/features/003-ui-design-system.md` (tokens, app shell, dark theme),
`docs/features/004-role-names.md` (how base role names are shown), `docs/design/screens/` and ADR 0009.

Feature sequence: `003 UI design system` → `004 Role names` → `005 UI preferences` → `006 Plots registry` →
`007 Ownership` → `008 Excel import` → `009 Deployment`.

## 2. Theme switch

- Three choices: "Системна" (default, follows the operating system as today), "Світла", "Темна". English: "System",
  "Light", "Dark".
- Placed in the header next to the UK/EN language switch, built the same way (a group of real buttons with
  `aria-pressed`, keyboard reachable, visible focus). Under 768 px it sits inside the collapsed menu.
- The choice is a per-browser preference stored in `localStorage` (no server change). Reading or writing storage can fail
  (private mode); the app then falls back to "System" without errors.
- The theme is applied before the first paint (a small inline script in `index.html` sets an attribute on `<html>`), so a
  dark-theme user never sees a white flash. Tokens switch on that attribute; "System" keeps the `prefers-color-scheme`
  rule.
- The sign-in redirect and full-page messages use the same theme.

## 3. Cooperative name in the header

- The header title is the cooperative's name from `GET /me` (`tenant.name`), in the serif title style of 003, linking to
  the home page. The product name moves to a small secondary label (for example "Наділ" in muted text after the
  cooperative name, or in the footer) — keep it, but not as the main title.
- The browser tab title is "<page> — <cooperative name>".
- A user without a cooperative (the platform operator) sees the product name as the title.
- Long names truncate with an ellipsis and keep the full name in a `title` attribute; at phone width the name stays
  visible next to the menu button.

## 4. Translated descriptions of base roles

- Same rule as names in 004: a base role shows its description from the i18n catalog in the user's language unless the
  chairman has changed it; once changed, the stored text is shown in every language. "Restore default" in the role
  form restores both the name and the description, or each separately (implementer's choice, keep it simple and
  consistent with 004).
- API: `RoleDetails` and `RoleRef` (where a description is returned) get a required boolean `descriptionCustomized`,
  always `true` for custom roles. `RoleInput` gets an optional boolean `useDefaultDescription` (400 for custom roles).
- Migration: `role.description_customized boolean not null default false`; existing custom roles set to `true`.
- Catalog texts (uk / en): Chairman "Повний доступ" / "Full access"; Treasurer "Перегляд членів, ділянок і журналу
  змін" / "Read access to members, plots and audit"; Member "Лише власні дані" / "Own data only".
- Audit: changing or restoring a description is audited like a name change (004).

## 5. Tests

- Web unit tests: theme choice stored and restored; storage failure falls back to System; header shows the cooperative
  name, falls back to the product name without a tenant; role description shown from the catalog or the stored text.
- Backend: `descriptionCustomized` and `useDefaultDescription` rules, migration of existing rows, audit entries.
- E2E: switch to dark and back, reload keeps the choice; header shows "Demo Garden Cooperative" for the demo chairman;
  Roles page shows Ukrainian descriptions of base roles.
- Contrast test from 003 still green in both themes.

## 6. Out of scope

Theme preference stored on the server or synced across devices; mobile app theme (follows the system until phase 5);
renaming the product.

## 7. Acceptance criteria

- [ ] Theme switch with System / Light / Dark in the header (in the menu on phones), remembered per browser, no flash on
      load.
- [ ] The header title is the cooperative's name; the product name is secondary; operator fallback works.
- [ ] Base role descriptions are translated unless customized; restore works; changes are audited.
- [ ] `make check` green; coverage floor raised to the new measured level.
