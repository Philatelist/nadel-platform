# Pipeline log — 009 Charge types and tariffs

- Status: running
- Phase: 3 — implement (Slice 1)
- Branch: 009-charge-types-and-tariffs · Base: main (= origin/main) @ eca55b9
- Requirement: docs/features/009-charge-types-and-tariffs.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet)

## Project facts

Same as 004–008 (see `context/spec/005-ui-preferences/pipeline-log.md` and `context/spec/008-excel-import/pipeline-log.md`). Re-checked: main = origin/main @ eca55b9 (008 merged as #27, docs #28–#30); pipeline tooling present on main; clean tree; the 008 review run was stopped (PIDs 48958 49164 49118). Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. UI verification with the project's Playwright. Safe local run: `make up`. Full suite: `make check`; e2e: `make e2e`.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true
- Phase 5 rule: before the last push set Status `finished`, Phase `5 — published`, committed with the branch. Phase 6 changes no tracked files; review-run notes go only to the final report.

## Phase 1 — requirements brief

**Requirement:** the chairman describes what the cooperative charges for: charge types (four seeded per cooperative — MEMBERSHIP, ELECTRICITY, LAND_TAX, WATER inactive — plus custom ones; rename, reorder, archive), tariffs per charge type with dated versions (basis PER_PLOT / PER_AREA_SOTKA / PER_KWH / PER_M3 / FIXED_ONE_TIME, frequency MONTHLY / QUARTERLY / YEARLY / ONE_OFF, rate with 4 decimals, note), scopes (all plots / selected plots, exclusions with reasons), and a server-calculated expected amount per plot and month. No charges are created (011). Users: chairman (TARIFFS_MANAGE), treasurer (TARIFFS_VIEW), member (own plots only).

**Prior work:** Agent A — nothing off main; only the requirement doc (eca55b9). Agent B — nothing built; latest migration V9 → V10; no Java `Money` value object exists despite money-rules §1 (must be created); no `Rate` schema; no public plots read facade (only `PlotImport`); `identity.OwnedPlotsSource` gives a member's current plots; base roles seeded by SQL `seed_base_roles` (re-seeded in V7, ON CONFLICT DO NOTHING) and called by demo data and tests; no tenant-creation code; no billing-frequency setting; plot card has no tabs (panels); AuditPage maps entity types. Verdict: **new; builds on 006, 007 and the 007 settings page; prepares 010–012**.

**Scope:** sections 2–8 of the feature doc. Out: section 9 (charges/ledger 011, recalculation 012, meters 010, allocation priority use, reports 013), mobile.

**Verification:** unit tests for every section 3 row and the month split; Testcontainers (CRUD, versions, scopes, preview endpoint, permission allowed/denied per endpoint, member own plots only, audit, isolation per table, seeding for existing and new tenants); web unit tests; Playwright (chairman creates a membership tariff and sees the expected amount on a plot card; a member sees only own plot's amounts); `make check`, `make e2e`, screenshots on `make up`.

**Risks:** new shared `Money` value object placement (module boundaries; later used by ledger); the month split + periods share rule must be the single implementation 011 reuses; permission count changes ripple through many existing tests; plots module needs a new public read API; the shared dev DB in e2e (unique names per run, cleanup).

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with worked examples).
- AUTO: new module `app.nadel.tariffs`; plots exposes a new public read facade (area, kind, active, number) and member visibility uses `identity.OwnedPlotsSource` (rule: CLAUDE.md modules talk through public APIs).
- AUTO: create the `Money` value object (HALF_UP, 2 places) and a `Rate` value object (scale ≤ 4, never rounded) in a shared, open place chosen in the tech doc; the calculation (periods share + month split by days + one rounding) lives once in the tariffs module (rule: money-rules §1; feature doc §2.4 "so 011 uses the same rule").
- AUTO: the cooperative bills monthly; no billing-frequency setting is added (rule: feature doc "when the cooperative bills monthly (default)"; conservative — no setting nobody asked for).
- AUTO: the seeded charge types keep a code and a nullable custom name; the UI shows the i18n default until renamed (rule: "editable names, like base roles in 004–005").
- AUTO: new cooperatives are covered by calling the charge-type seed from `seed_base_roles` (one seed entry point); V10 seeds every existing tenant (rule: no tenant-creation code exists; base-role pattern).
- AUTO: charge types are never deleted, only archived (any charge type, with or without tariffs) (rule: API in §4 has no delete; conservative).
- AUTO: tariff versions are immutable in 009 (no edit or delete endpoint); two versions of a tariff cannot share `valid_from` → 409 with a code (rule: API in §4; ledger corrections later rely on version history).
- AUTO: common land can never be in a scope (selected plots or exclusions) — rejected with 400/409 code; archived plots get no tariffs (rule: §2.3 "common land never").
- AUTO: rates are non-negative (pattern `^\d+(\.\d{1,4})?$`); zero allowed.
- AUTO: a version starting or a scope period inside the month contributes by days valid / days in month (same split rule); a month before the first version gives no amount; FIXED_ONE_TIME uses the version valid on its due date and is not split (rule: §2.4 split rule applied consistently).
- AUTO: PER_KWH/PER_M3 tariffs appear in the plot preview as "by readings" without an amount, for every plot in scope (meters arrive in 010) (rule: §2.3/§2.4).
- AUTO: an excluded plot appears in the plot preview with the reason and no amount (rule: §3 "plot 17: no amount, reason shown").
- AUTO: `GET /plots/{id}/tariffs` is allowed with TARIFFS_VIEW and PLOTS_VIEW, or for a member's own current plot; otherwise 404 like the plot card; `month` defaults to the current Kyiv month (rule: §6; 007 visibility pattern).
- AUTO: the plot card gets a "Тарифи" panel with a month picker instead of introducing tabs (rule: the card has no tab component; smallest change).
- AUTO: the account page shows the current month's expected amounts for the member's own plots (rule: §7.5).

## Phase 2 — branch and specs

- Spec folder and branch `009-charge-types-and-tariffs` from main @ eca55b9 (= origin/main). Baseline `make check` started in a detached worktree.
- functional-spec.md written (Creation Mode, no markers; 7 requirement groups).
- AUTO: inactive charge types keep their tariffs but give no expected amount; archived types' tariffs never apply (rule: §2.1 "active flag"; conservative).
- AUTO: charge type names unique (case-insensitive) among non-archived types (rule: names identify types in lists and the audit log).
- AUTO: exclusions exist only with ALL_PLOTS; switching to SELECTED_PLOTS removes them, audited (rule: §2.3 "plots removed from ALL_PLOTS").
- AUTO: ONE_OFF only with FIXED_ONE_TIME; a tariff is created with its first version; the month total is the sum of per-tariff amounts, each rounded once (rule: §2.2; §2.4 one rounding per amount).
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend; exploration from Phase 1 Agent B).
- AUTO: new module `app.nadel.money` (Money, Rate) with ADR 0013 (rule: ledger 011 reuses them; CLAUDE.md "an architectural one gets an ADR").
- AUTO: seeded charge type names follow the role pattern (stored English default + `name_customized`) (rule: consistency with V4 roles).
- AUTO: existing cooperatives get TARIFFS_* by targeted inserts, not by re-running seed_base_roles (rule: re-running would restore Treasurer permissions a chairman removed).
- AUTO: new cooperatives get charge types from `seed_base_roles` (no trigger on tenant) (rule: one seed entry point).
- AUTO: inactive/archived types' tariffs are omitted from expected amounts; a meter-based overlapping version makes the month "by readings"; common land and archived plots return no items (rule: conservative reading of §2.3/§2.4).
- AUTO: scope rows cascade on plot delete (rule: 006 deletes only plots without history; must not block it).
- AUTO: `GET /plots/{id}/tariffs` lives in the tariffs module (rule: no plots → tariffs dependency).
- AUTO: reorder via `PUT /charge-types/order` with the full id list (rule: atomic reorder; the feature doc's API had no reorder endpoint).
- tasks.md written: 6 slices (permissions + charge types; tariffs and versions; scopes; expected amount on plot card and account; e2e/docs/coverage; Feature Testing & Regression by testing-expert). No staffing gaps. No `not-user-reviewed` marker left.
- Baseline: make check green (1,096 backend, coverage 0.9780 vs floor 0.97; client 56/67/7/241). Recorded in review-log.md; baseline worktree removed.
- Slice 1 backend: verify 1171 tests green, coverage 0.9775; RED checks (default-name collision, tariff_version grants) failed then passed. AUTO: Ukrainian default charge type names held in `backend/src/main/resources/tariffs/charge-type-default-names.properties` for the name-collision check (rule: the client shows them for unrenamed types, the server must refuse a duplicate); CLAUDE.md wording change proposed to the owner, not made. AUTO: update/archive of an archived type → 409 CHARGE_TYPE_ARCHIVED; sending the displayed default name back is not a rename. `ChargeTypesMigrationTests` proves V9→V10 targeted inserts don't restore a removed Treasurer permission.
- Slice 1 committed (backend targeted green; verify 1171 / coverage 0.9775; ui-tokens 56, shared 67, api-client 7, web 260; typecheck/lint/format green). Web: hand-written api-client index exports the tariffs hooks; default-name parity test reads the backend properties file. Review r1 started.
- Slice 1 review r1 PASS (minor notes in review-log.md; Slice 4 must adjust the charge_type test cleanup).
- Slice 2 backend: verify 1231 tests green, coverage 0.9785; RED checks (duplicate-date mapping, rate rounding) failed then passed. AUTO: charge-type operations moved to their own tag `charge-types` (two controllers cannot implement one generated interface; operationIds unchanged); `dueDate` on a non-fixed basis → field code INVALID; tariff create field errors prefixed `firstVersion.`; the version note is in the audit entry (as plot notes) but never in the application log; `TariffSummary.version` is the full version shape; unknown charge type on create → 404.
- Slice 2 committed (backend targeted green; verify 1231 / coverage 0.9785; ui-tokens 56, shared 72, api-client 7, web 278; typecheck/lint/format green). AUTO (web): the contract's ChargeType has no archived flag, so the new-version form stays visible on an archived type's tariff and the server's CHARGE_TYPE_ARCHIVED is shown; new version prefilled from the newest version. Review r1 started.
