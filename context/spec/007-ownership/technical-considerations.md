# Technical Specification: Ownership

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Draft
- **Author(s):** Alex Usov (pipeline; inputs from spring-backend, postgres-database and react-frontend agents)

---

## 1. High-Level Technical Approach

- **Ownership lives in the `plots` module** (`app.nadel.plots.internal`), because the status cache, visibility, the plots
  list (owners column, owner search) and the delete/archive guards all live there. `plots` gains two new dependencies:
  `members` (through a new public `MemberDirectory` API, never the member tables) and the new `settings` module.
- **Members' archive refusal** goes through an SPI declared in the `members` public package (`MemberArchiveGuard`),
  implemented in `plots`. A veto has to be synchronous, so an event cannot replace it, and the SPI keeps `members` free
  of a `plots` dependency, so there is no cycle.
- **A new `app.nadel.settings` module** holds cooperative settings: the table `cooperative_settings`,
  `GET/PUT /settings` and the public `CooperativeSettings` API. It cannot live in `tenancy`, because `tenancy` → `audit`
  would close a cycle.
- **`GET /me` gets owned plots** through a new SPI in `identity` (`OwnedPlotsSource`), implemented in `plots`. This is
  the same direction as `AccessProfileSource`.
- Exact shares are a `Share` value object (reduced numerator/denominator, cross-multiplication). One pure
  `OwnershipRules` timeline validator serves both the change of owner and the correction.
- `plot_status` is written in the same transaction as the ownership change (same module, direct call): GREEN when the
  PLOT has current owners, GREY otherwise.
- A new permission `SETTINGS_MANAGE`; a V7 migration; contract-first changes in `api/openapi.yaml` + `make gen`.
- Web: an owners panel with change and correction dialogs, a member picker, the owners column, member-card and
  My-account plots panels, a settings page with a new `RadioGroup`, a neutral status mark, and an exact fraction helper
  in `packages/shared` (input UX only; the server is authoritative).
- A short ADR (0011) records the new module and the synchronous-veto SPIs.

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database (`V7__cooperative_settings.sql` for settings and the permission, `V8__ownership.sql` for ownership)

| Object | Key points |
|---|---|
| `plot` | `ADD CONSTRAINT plot_tenant_id_uq UNIQUE (tenant_id, id)`, the target of composite FKs |
| `ownership` | `id uuid pk`, `tenant_id`, `plot_id`, `member_id`, `share_numerator smallint`, `share_denominator smallint`, `valid_from date not null`, `valid_to date null`, `basis text` (≤ 500), `created_at`, `updated_at` |
| constraints | composite FKs `(tenant_id, plot_id)` → `plot (tenant_id, id)` and `(tenant_id, member_id)` → `member (tenant_id, id)`, both `ON DELETE RESTRICT`; CHECKs `1 ≤ num ≤ den ≤ 1000`, `valid_to is null or valid_to ≥ valid_from`, basis length; reduced form is enforced in the app |
| overlap | `CREATE EXTENSION IF NOT EXISTS btree_gist` + `EXCLUDE USING gist (tenant_id WITH =, plot_id WITH =, member_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)`; SQLSTATE 23P01 maps to 409 `OWNERSHIP_OVERLAP`. Fallback if the extension cannot be created: partial unique `(plot_id, member_id) where valid_to is null` + app checks (record in the migration comment) |
| indexes | `(tenant_id)`; `(tenant_id, plot_id) where valid_to is null`; `(tenant_id, plot_id, valid_from)`; `(tenant_id, member_id)` |
| RLS | enable + `tenant_isolation` policy (same pattern as V6) |
| grants | `REVOKE DELETE, TRUNCATE ON ownership FROM nadel_app` (records are never deleted; V3 pattern) |
| `cooperative_settings` | `tenant_id uuid pk references tenant`, `member_plot_status_visibility text not null default 'OWN_ONLY' check in ('OWN_ONLY','ALL_PLOTS')`, `updated_at`; RLS + `tenant_isolation`; the PK covers the tenant index (comment says so). A missing row means OWN_ONLY, so no backfill |
| permissions | `CREATE OR REPLACE FUNCTION seed_base_roles` (V2 body + `('CHAIRMAN','SETTINGS_MANAGE')`), keep `GRANT EXECUTE`, then `SELECT seed_base_roles(id) FROM tenant` |

**Demo data** (`R__demo_data.sql`): fixed ownership ids `dddddddd-0000-0000-0000-0000000000NN`, plots looked up by
`(tenant_id, number)`, `ON CONFLICT (id) DO UPDATE`.
- Plot 3: 1/2 + 1/2 (two members other than the demo member).
- Plot 7: 1/2 demo member (0003, `member@demo.local`) + 1/2 Halyna Moroz (0007).
- Plot 11: 2/3 + 1/3.
- Plot 9: archived member Honcharenko (0020) from 2015-06-01 to 2025-05-31, then one owner from 2025-06-01.
- The demo member also owns plot 15 alone.
- Plots 1–24 and 12а: one distinct owner each; plots 25–29 and road R-1 have no owner.
- Then refresh `plot_status` (GREEN where a current owner exists, else GREY).
- The other cooperative gets one owned plot, for isolation tests.
- Members that existing tests archive must own nothing; the builder checks `MembersWriteTests` and the LastChairman
  tests and picks owners accordingly.

### 2.2 Backend

| Area | Classes / changes |
|---|---|
| plots.internal | `Ownership` (entity), `OwnershipRepository`, `Share` (parse "n/d", gcd, `plus`, compare, d ≤ 1000, n in 1..d), `OwnershipRules` (pure: segment sweep over `valid_from` / `valid_to + 1`, each segment sums to 0 or exactly 1, no same-member overlap, no future dates, change-date rule D > max(start of current, end of past)), `OwnershipCommands` (change, correct), `OwnershipQueries`, `OwnershipController` (implements generated `OwnershipsApi`), `OwnershipArchiveGuard` (implements `MemberArchiveGuard`), `OwnedPlotsAdapter` (implements `OwnedPlotsSource`), `ViewerScope` |
| plots.internal changes | `PlotRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`); `PlotCommands.archive` refuses with current owners (`PLOT_HAS_OWNERS`), `delete` with any ownership row (`PLOT_HAS_OWNERSHIP_HISTORY`), `update` refuses kind → COMMON with ownership history; `PlotStatusPolicy.afterOwnershipChange`; `PlotStatusEntry` gets a status setter/`updated_at`; `PlotDtoMapper` uses `ViewerScope` |
| members | public `MemberDirectory` (`find(ids)`, `idsMatchingName(q)` capped, `lockActive(ids)` FOR SHARE) + `MemberRef`; public SPI `MemberArchiveGuard`; `MemberCommands.archive` first locks the member (`findByIdForUpdate`), then calls `lastChairman` and every guard |
| settings (new module) | public `CooperativeSettings`, `PlotStatusVisibility`; internal entity, repository, `SettingsController` (`GET` any cooperative member, operator refused; `PUT` `PERM_SETTINGS_MANAGE`), audit `SETTINGS_UPDATED`; README, `package-info` |
| identity | `Permission.SETTINGS_MANAGE`; SPI `OwnedPlotsSource` + `OwnedPlot(plotId, number, street, share)`; `MeController` fills `ownedPlots` (empty for the operator) |
| members.internal | `PermissionCatalog` case for `SETTINGS_MANAGE` |
| time | a `Clock` bean with zone Europe/Kyiv; "today" for date rules comes from it |
| problem | new codes: `SHARES_NOT_ONE`, `DUPLICATE_OWNER`, `OWNERSHIP_DATE_INVALID`, `OWNERSHIP_OVERLAP`, `MEMBER_ARCHIVED`, `PLOT_COMMON_LAND`, `MEMBER_OWNS_PLOT`, `PLOT_HAS_OWNERSHIP_HISTORY`, `PLOT_HAS_OWNERS`; field codes `SHARE_INVALID`, `MEMBER_NOT_FOUND`; `PLOT_ARCHIVED` reused |
| audit | `AuditAction` + `OWNERSHIP_CHANGED`, `OWNERSHIP_CORRECTED`, `SETTINGS_UPDATED`; `AuditEntityType` + `OWNERSHIP`, `SETTINGS`. Ownership entries use entityId = plot id and label = plot number; fields `effectiveFrom`, `owners` (text "Last First 1/2 (from–to)", sorted), `basis`, `reason`. Settings: `memberPlotStatusVisibility` old/new. Names in the audit log follow the existing MEMBER entries; no patronymic, phone or e-mail |

**Concurrency:** change, correction and plot archive, delete and update take the plot row lock first. New owners are
locked `FOR SHARE` through `lockActive`. Member archive locks the member row. The lock order is plot, then members, so
there is no deadlock.

**Visibility (`ViewerScope(full, ownedPlotIds, setting)`):**
- `full` = PLOTS_VIEW.
- `canSeeDetails` = full or owned. It covers area, cadastral number, building, owners/history and debt on the map.
- `canSeeNotes` (notes, timestamps) = full.
- `canSeeStatus` = full or owned or `ALL_PLOTS`. A hidden status is null and absent from the JSON.
- Archived plots and cadastral search stay `full` only.
- Status filter for a restricted viewer under OWN_ONLY: matches only owned plots. An empty owned set short-circuits in
  Java (no empty `IN ()`).
- `q` for full viewers also matches current owners by name: ids come from `MemberDirectory.idsMatchingName`, then
  `exists` on `ownership`.
- The owners summary is loaded per page with one ownership query and one `MemberDirectory.find`, so there is no N+1.

### 2.3 API contract (`api/openapi.yaml`, tag `ownerships` and `settings`)

| Endpoint | Permission | Shape |
|---|---|---|
| `GET /plots/{id}/ownerships` | PLOTS_VIEW, or the viewer currently owns the plot; else 404 | `PlotOwnerships {plotId, current[], history[]}`; `Ownership {id, memberId, memberName, memberArchived, share "n/d", validFrom, validTo?, basis?}` |
| `POST /plots/{id}/ownership-changes` | PLOTS_VIEW + OWNERSHIP_EDIT | `{effectiveFrom, owners[{memberId, share}] (0..20), basis?}` → 201 `PlotOwnerships` |
| `POST /ownerships/{id}/corrections` | PLOTS_VIEW + OWNERSHIP_EDIT | `{memberId, share, validFrom, validTo?, basis?, reason, coOwnerShares[{ownershipId, share}]}` (full replacement of the target) → `PlotOwnerships` |
| `GET /members/{id}/ownerships` | PLOTS_VIEW or MEMBERS_VIEW, or own member id; else 404 | `MemberOwnerships {current[], past[]}`, items `{ownershipId, plotId, plotNumber, street?, share, validFrom, validTo?}` |
| `GET /settings`, `PUT /settings` | any cooperative member / SETTINGS_MANAGE | `CooperativeSettings {memberPlotStatusVisibility}` |
| `GET /plots`, `GET /plots/{id}`, `GET /plots/map` | unchanged | `status` becomes optional in `PlotSummary`, `PlotDetails` and `PlotProperties`; `PlotSummary.owners?[{memberId, name, share}]` for full viewers |
| `GET /me` | unchanged | `ownedPlots[{plotId, number, street?, share}]` (required, may be empty) |
| enums | — | `Permission` + `SETTINGS_MANAGE`; `AuditAction`, `AuditEntityType` as above |

The share pattern is `^[1-9][0-9]{0,3}/[1-9][0-9]{0,3}$`. Validation errors use the existing Problem Details with
field paths such as `owners[1].share`.

### 2.4 Web

| Area | Files |
|---|---|
| shared | `packages/shared/src/fraction.ts` (`parseFraction`, `addFractions`, `formatFraction`, `isWhole`; BigInt, gcd; tests); i18n groups `ownership`, `settings`, `account.myPlots`, `member.plots`, `plots.columns.owners`, `validation.*`, `problem.*` (new codes), `nav.settings`, `permission.SETTINGS_MANAGE`, `audit.*`; remove `plot.ownersPlaceholder` |
| ui | `components/ui/RadioGroup.tsx` (+ test); `components/Modal.tsx` extracted from `ConfirmDialog` (which then uses it); `StatusDot` with an optional status (neutral ring, no label); `plotStatusLabel` returns undefined without a status; neutral map colour token in `ui-tokens` if `MapPage` needs it |
| ownership | `components/ownership/OwnersPanel.tsx` (current + history, actions), `OwnershipLists.tsx` (read-only), `ChangeOwnerDialog.tsx`, `CorrectOwnershipDialog.tsx`, `MemberPicker.tsx` (debounced `useListMembers`, excludes archived and already chosen, "Add member" link with MEMBERS_EDIT in a new tab), `ShareTotal.tsx`, `MemberPlotsPanel.tsx`, `MyPlotsPanel.tsx`; hooks `useCanEditOwnership` (PLOTS_VIEW + OWNERSHIP_EDIT; the change action also needs MEMBERS_VIEW), `useOwnershipInvalidation` |
| pages | `PlotPage` shows `OwnersPanel` for full viewers and own plots; `PlotsPage` owners column (2 names + "+N"); `MemberPage` plots panel; `AccountPage` "My plots"; `SettingsPage` + route `/settings` under `RequirePermission SETTINGS_MANAGE` + nav item; `AuditPage` labels and OWNERSHIP links to the plot; `problem.ts` `fieldKey` handles `owners[i].*` |

## 3. Impact and Risk Analysis

- **Module structure:** `plots` → `members`, `settings`. Verified cycle-free by `ModularityTests`. ADR 0011,
  architecture.md and the module READMEs are updated.
- **Contract break:** `status` becomes optional. Web types, `MapPage` and `mobile/App.tsx` must handle a missing status
  (mobile gets a compile-only fix, as in 006).
- **Permission count 8 → 9:** `RolesTests`, `SeedBaseRolesTests`, `DemoAccessChecks`, `MeEndpointTests`, web role
  fixtures, ADR 0009 and the architecture docs change.
- **Demo data:** existing tests assuming all-GREY plots, deletable demo plots or archivable demo members must be
  adjusted. `TenantIsolationTests`, `PlotStatusTests`, `PlotsReadTests`, `PlotMapTests`, `PlotsWriteTests`,
  `PlotsRegistryAcceptanceTests`, `MembersWriteTests` and e2e specs are affected.
- **Races:** plot and member locks plus the exclusion constraint (C1, C5 from the backend review).
- **Time zone:** the Europe/Kyiv clock, so "today" is right on a UTC server.
- **btree_gist:** created by V7 as `nadel_owner`. It is trusted since PG 13; if it fails in Testcontainers, use the
  documented fallback.
- **Audit enums** must be in the contract before events are published (`AuditQueries` parses them).
- **Coverage:** the local baseline measured 0.9481 (< 0.95 floor) while CI was green. New code needs full test coverage,
  and the floor is raised only to the measured CI-safe value.

## 4. Testing Strategy

- **Unit:**
  - `ShareTests`: gcd, 2/4 → 1/2, 1/3 × 3, 1/2 + 1/3 = 5/6, bounds.
  - `OwnershipRulesTests`:
    - sum to one;
    - overlap;
    - future date;
    - change on the 1st of a month, on 1 January and on 1 March in a leap year;
    - gaps;
    - correction with co-owner shares.
  - `PlotStatusPolicy` tests.
- **Integration (Testcontainers):**
  - `OwnershipChangeTests`, `OwnershipCorrectionTests` (audit old/new + reason), `OwnershipVisibilityTests` (both
    settings × own / not own × PLOTS_VIEW × former owner; list, card, map, filter).
  - `PlotGuardsTests` (archive / delete / kind change), `MemberArchiveGuardTests`, `MemberDirectory` name search.
  - `OwnershipTenantIsolationTests` and settings isolation tests, including the composite FK across tenants, DELETE
    denied, and the exclusion constraint.
  - `SettingsTests` (default for new and existing cooperatives, permissions, audit), `MeOwnedPlotsTests`,
    `OwnershipConcurrencyTests`, and allowed/denied tests per endpoint.
  - `ModularityTests`.
- **Web unit:** fraction, RadioGroup, StatusDot neutral, plotStatusLabel, OwnersPanel actions, ChangeOwnerDialog
  (5/6 disabled, 1/3 × 3 enabled, zero rows, duplicate, field errors on row 2), CorrectOwnershipDialog reason,
  MemberPicker, SettingsPage, `problem.ts` field keys, Layout nav.
- **E2E (Playwright):**
  - The chairman changes plot ownership to two co-owners at 1/2, and the audit shows one entry.
  - The dialog blocks 1/2 + 1/3.
  - A correction to 2/3 + 1/3.
  - The treasurer sees the owners column and the owner search.
  - The demo member sees My plots with a co-owner, a limited card for plot 9, and neutral marks under OWN_ONLY.
  - The chairman switches to ALL_PLOTS and restores it in `finally`.
  - The settings page is not available to the treasurer or a member.
  - The archive and delete guards.
  - Created data is cleaned up or the specs use dedicated plots so the suite stays order-independent.
- **Gate:** `make check` (coverage floor raised to the measured value, rounded down) and `make e2e`.

## Assumptions (pipeline)

- Ownership inside `plots`; `settings` as a new module; synchronous SPIs for the member archive veto and `/me`.
- The exclusion constraint with `btree_gist` is used as a database backstop.
- The search box (`q`) matches owners for full viewers; there is no separate `owner` parameter.
- A member sees the basis on their own plot's ownership records (it is part of their own history).
- `GET /members/{id}/ownerships` shows shares and periods to PLOTS_VIEW or MEMBERS_VIEW holders and to the member
  themself.
- "Today" is the Europe/Kyiv date.
