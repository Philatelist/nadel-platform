# Pipeline log — 010 Meters and readings

- Status: finished
- Phase: 5 — published
- Branch: 010-meters-and-readings · Base: main (= origin/main) @ 063af9f
- Requirement: docs/features/010-meters-and-readings.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: the pull request of this branch · Checks: see the PR
- Review run: on — started after green checks (local only, no tracked files)

## Project facts

Same as 004–009 (see `context/spec/009-charge-types-and-tariffs/pipeline-log.md`). Re-checked: origin/main @ 063af9f (009 #31, one-language fix #32, version correction #33, 010 doc #34); pipeline tooling present on origin/main; clean tree; no review run running. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. Safe local run: `make up`. Full suite: `make check`; e2e: `make e2e` (a realm or seed change may need `docker compose -f infra/docker-compose.yml down -v`). Latest migration on origin/main: V11 → next V12. One-language rules (CLAUDE.md): every system text from the catalogs; demo data in Ukrainian; data-user-content markers; language-purity e2e spec; local ESLint rule.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true
- Before the last push of Phase 5: Status `finished`, Phase `5 — published`, committed with the branch; Phase 6 changes no tracked files.
- CI fix rule (SKILL.md 6b6e9f0): after a CI fix, run the failed job's full local suite before pushing.

## Phase 1 — requirements brief

**Requirement:** a register of meters (PLOT, SUBSTATION, QUARTER, COMMON_CONSUMER; ELECTRICITY kWh / WATER m³; serial, name, installation date and initial reading, removal with final reading, note), an electricity meter tree with dated parent history, replacement as one operation (incl. counter wrap-around), readings on any date (`taken_on` prefilled with today with a quiet hint; APPROVED / PENDING / SUPERSEDED; ordering validation; unusual-consumption warning; correction and deletion until a charge uses them via a `ReadingUsage` port), server-side interval and range consumption with linear interpolation marked as calculated, four permissions, a member's own meters and pending submissions with approval, an Excel `Лічильники`/`Meters` sheet, audit, Ukrainian demo data. Users: chairman (all), electrician custom role (METERS_VIEW, READINGS_ENTER), treasurer (METERS_VIEW), member (own meters, submission). Nothing is charged (011).

**Prior work:** Agent A — nothing off main (only the requirement doc 063af9f and older meter-rule docs #19/#20). Agent B — nothing built; 009 placeholders "за показаннями" / "застосовується до ділянок з лічильником" to switch (ExpectedAmountQueries, TariffAssembler plotCount, catalogs); patterns: TariffVersionUsage port, permissions re-seed (V10 full seed_base_roles + targeted inserts), PlotCatalog (no street), OwnedPlotsSource, veto SPI (ADR 0011, OwnershipArchiveGuard), ChargeCalculator style, imports sheets + vocabulary, DemoTariffData clear/restore; web: /plots has no permission (electrician would see Plots), Settings page has no sections, PlotPage/AccountPage panels, DataTable without phone cards. Verdict: **new; builds on 006, 007, 008, 009; prepares 011**.

**Scope:** sections 2–10 of the feature doc. Out: section 11.

**Verification:** unit tests for every row of §3 and the validation rules; Testcontainers (meters CRUD/replace/remove/delete, tree and parent history, readings entry/approve/reject/supersede/correct/delete, round list, range consumption, member visibility, permissions allowed/denied per endpoint, audit, isolation per table, import sheet uk/en, plot delete/archive/kind guards, tariffs meter presence); web unit tests; e2e (electrician round with default and changed date, chairman approves a member reading, member sees only own meter, language purity covers the new screens); `make check`, `make e2e`, screenshots.

**Risks:** size (four screens, tree, import sheet); interpolation and replacement maths; plot delete/archive guards touching 006/007 behaviour; permission count ripple (11 → 15); menu/landing rules for the electrician; demo data clear/restore in tests; phone-width round screen.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with worked examples).
- AUTO: new module `app.nadel.meters` (depends on plots, identity, audit, problem); `MeterValue` (scale ≤ 3, never rounded, strings in JSON) lives in the meters module root, not in `money` (it is a quantity, not money) (rule: CLAUDE.md modules; money README scope).
- AUTO: tariffs learns meter presence through a public `meters` read API (tariffs → meters dependency); meters never depends on tariffs (rule: no cycles; a tariff "applies to plots with a meter" is a tariffs concern).
- AUTO: plots gets a veto SPI for delete, archive and PLOT→COMMON when a meter exists (ADR 0011 pattern), implemented by meters (rule: 2.1 "common land never has a PLOT meter"; a meter with readings is never deleted).
- AUTO: `PlotCatalog` gains street and natural street-and-number order for the round screen (rule: §8.2 order).
- AUTO: the round list includes every active meter: plot meters in street and number order, then non-plot meters by name; filters resource, street, "без показу з" (default 60 days off unless set) (rule: §4 "every active meter").
- AUTO: the meter's initial reading is the baseline for the first interval and is not a reading row; a meter can be deleted while it has no reading rows (rule: §2.1, §2.5).
- AUTO: unusual-consumption warning = new interval's daily average > 3 × the daily average over the approved intervals of the last 180 days before the new reading; no warning without at least one earlier interval (rule: §2.4; conservative).
- AUTO: a member's PENDING reading never supersedes; on approval it supersedes an APPROVED reading of the same date (rule: §2.4 one approved reading per date).
- AUTO: a menu item "Ділянки" is hidden for a user who has neither PLOTS_VIEW nor owned plots but has READINGS_ENTER or METERS_VIEW; such a user lands on Покази (or Лічильники) (rule: §8.2 "only this screen and the register").
- AUTO: the tree screen is `/settings/meter-tree` under Налаштування, gated by METERS_MANAGE; the Settings menu item shows with SETTINGS_MANAGE or METERS_MANAGE and the settings page lists the sections the user may use (rule: §8.4; settings page has no sections yet).
- AUTO: import sheet `Лічильники`/`Meters`: an active meter of the same resource and serial on the plot → unchanged; a different active meter → error (replacement is done in the UI); only plot meters (rule: §7; 008 idempotent re-import).
- AUTO: the Keycloak realm is unchanged; the electrician keeps demo member Ткаченко Сергій Іванович and the custom role Електрик gets METERS_VIEW and READINGS_ENTER in demo data with a new Ukrainian description (rule: §6, §10).

## Phase 2 — branch and specs

- Spec folder and branch `010-meters-and-readings` from main @ 063af9f (= origin/main). Baseline `make check` started in a detached worktree.
- functional-spec.md written (Creation Mode, no markers; 10 requirement groups).
- AUTO: meter-based tariffs show "немає лічильника" for a plot without an active meter of that resource and do not count it in the tariff's plots (rule: §4 "say whether the plot has an active meter").
- AUTO: messages for refusals chosen in the spec (e.g. "На ділянці вже є активний лічильник цього ресурсу", "Спершу зніміть лічильник ділянки") (rule: Problem codes need catalog texts).
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend).
- AUTO: `MeterValue` + exact `Quantity` + `ConsumptionCalculator` in the meters root; display values of interpolated readings rounded once HALF_UP to 3 decimals; ADR 0014 (rule: money-rules rounding is for money only; readings are not money).
- AUTO: `ReadingUsage` is range-based (`meterId, from, to`) (rule: 011 charges by date range; interpolated values change when a reading is inserted).
- AUTO: reading correction = a new row superseding the old (history kept); delete = hard delete, audited (rule: money-rules 6.2 history; spec 2.4).
- AUTO: batch entry is per row, each in its own transaction (rule: the round saves row by row).
- AUTO: `GET /readings?status=PENDING` added for the approvals screen (rule: §8.3 needs a list; missing in the feature doc API).
- AUTO: a non-plot meter's replacement takes over its children; meter_parent as explicit ranges with an exclusion constraint (V8 pattern) (rule: the tree must not break on replacement).
- AUTO: demo dates relative to CURRENT_DATE (rule: the 60-day "без показу" case must hold whenever the demo is loaded).
- tasks.md written: 8 slices (model + permissions + calculation; register; readings and round; replacement and member flow; tree; tariffs + import; demo/docs/e2e/coverage; Feature Testing & Regression by testing-expert). No staffing gaps. No `not-user-reviewed` marker.
- Baseline: make check green (1,380 backend, coverage 0.9795 vs floor 0.97; client 56/79/7/345). Recorded in review-log.md; baseline worktree removed.

## Phase 3 — implement

- Resumed (`/feature-pipeline resume`, 2026-10-21 20:34): Slice 1 backend task found written but uncommitted; web task not started. Re-running the slice's backend tests; web task delegated to react-frontend.
- Found in the working tree, changed after the builder finished and not part of this run: `.claude/agents/spring-backend.md` (model sonnet → opus) and `backend/pom.xml` (surefire `forkedProcessTimeoutInSeconds` 900 → 4000). Treated as the owner's local edits: left as they are and never staged.
- Slice 1: backend slice tests green (meters + roles + modularity; 0 failures); client test/typecheck/lint/format green (shared 97, web 345); `pnpm gen:api` no diff.
- Slice 1 committed `d201cfc` — tests green; review round 1 running.
- Owner asked to move their local edits to the branch: `.claude/agents/spring-backend.md` (model opus) and `backend/pom.xml` (surefire timeout 4000 s) committed as `738723b`.
- Slice 1 review round 1: MAJOR_ISSUES (M1 locale-blind parse, M2 trailing zeros); fixes by react-frontend committed `73e4b06` (shared 110 tests green); round 2 running.
- Slice 1 review round 2: PASS.
- Slice 2 backend (spring-backend): reported 312 tests green; contract tag `meters`; guard SPI returns refusals (ADR 0011 note); `READING_DATE_IN_FUTURE/OUTSIDE_METER/OUT_OF_ORDER` already used for installation/removal; carried forward: Slice 4 adds the pending check on remove, Slice 5 must refuse delete/remove of a tree parent (`METER_HAS_CHILDREN`) instead of an FK 500. Web task delegated; backend re-verification running.
- Slice 2 web (react-frontend): web 367 tests; also re-exported the meters module in the hand-written `packages/api-client/src/index.ts`. Verified: backend Slice 2 tests green (MetersTests 33, PlotChangeGuardTests 9, MeterAuditTests 6, PlotCatalogTests 12, PlotsWriteTests 42, ImportPreviewPlotsTests 27, ValidationProblemsTests 6, ModularityTests 2); client test/typecheck/lint/format green; `pnpm gen:api` no diff.
- Slice 2 committed `eca98e7` — tests green; review round 1 running.
- Slice 2 review round 1: MAJOR_ISSUES (M1 import catalog text). The fix touches packages/shared/src/i18n, which Slice 3 (in progress) also edits → applied as a separate commit right after the Slice 3 commit (review loop overlap rule).
- Slice 3 backend (spring-backend): reported 154 tests green, no V13; deviations to note: `supersededReadingIds` array (a correction can supersede two rows), `GET /meters/{id}/readings` under tag readings, idempotent repeat saves, empty list → `REQUIRED`. Web task delegated; backend re-verification running.
- Slice 3 web (react-frontend): web 386 tests. Verified: backend Slice 3 tests green (ReadingsTests 22, RoundTests 6, ReadingValidatorTests 14, ReadingAuditTests 5, MetersTests 33, MeterAuditTests 6, PlotChangeGuardTests 9, ValidationProblemsTests 7, MembersAccessAcceptanceTests 13, ModularityTests 2); client test/typecheck/lint/format green; `pnpm gen:api` no diff.
- Slice 3 committed `c95e714` — tests green; review round 1 running. Slice 2 M1 fix delegated now that the tree is clean.
- Slice 2 M1 fix committed `4fd731d` (web 387, shared 110); Slice 2 review round 2 running. Slice 4 backend delegated.
- Slice 2 review round 2: PASS.
- Slice 3 review round 1: PASS.
- Slice 4 backend (spring-backend): reported 223 tests green, no V13. AUTO-documented: remove and replace refused while any PENDING reading exists; replacement writes METER_REPLACED (old) + METER_REGISTERED (new); new endpoint `GET /meters/{id}/consumption` (not in the tech spec) for end-to-end range checks across the chain. Web task delegated; backend re-verification running.
- Slice 4 web (react-frontend): web 406 tests. Verified: backend Slice 4 tests green (MeterReplacementTests 13, MemberReadingsTests 17, MetersTests 34, ReadingsTests 23, RoundTests 6, MeterAuditTests 8, ReadingAuditTests 8, ModularityTests 2); client test/typecheck/lint/format green; `pnpm gen:api` no diff.
- Slice 4 committed `18f2ee9` — tests green; review round 1 running. Slice 5 backend delegated.
- Slice 4 review round 1: PASS.
- Slice 5 backend (spring-backend): reported 208 tests green, no V13; closes the Slice 2 gap (delete of a meter that ever had children → 409 `METER_HAS_CHILDREN`, remove refused while a child period lasts past the removal date). Tree nodes are replacement chains; a meter moved within the range appears under each parent with its slice. Web task delegated; backend re-verification running.
- Slice 5 web (react-frontend): web 424 tests; on my request the move dialog preselects the current parent and disables a no-op move (it started at "Поза деревом"). Verified: backend Slice 5 tests green (MeterTreeTests 20, TreeRulesTests 18, MeterReplacementTests 13, MetersTests 34, MeterAuditTests 8, SettingsTests 13, ModularityTests 2); client test/typecheck/lint/format green; `pnpm gen:api` no diff.
- Slice 5 committed `76ab842` — tests green; review round 1 running. Slice 6 backend delegated.
- Slice 5 review round 1: MAJOR_ISSUES (M1 removal-day off by one). Fix delegated to spring-backend in a separate worktree at `76ab842` (fix-010-s5-r1) so it does not share build output with the Slice 6 agent; the fix commit is cherry-picked onto the branch.
- Slice 5 M1 fix `8d611e2` cherry-picked onto the branch as `8c40cab` (MeterTreeTests 20, MetersTests 34, MeterReplacementTests 13, ModularityTests 2 green; RED shown); review round 2 running.
- Slice 5 review round 2: PASS.
- Slice 6 backend (spring-backend): reported 672 tests green over tariffs/imports/meters, no V13. `ImportCounts.meters` kept required (the server always sends it); the 3 web fixtures that break typecheck are fixed in the web task. Import commit refused with `IMPORT_STALE` if meters changed after the preview; `IMPORT_COMMITTED` gains `metersCreated`/`metersUnchanged`. Web task delegated; backend re-verification running.
- Slice 6 web (react-frontend): web 424 tests; I restored the functional-spec wording "за показаннями" / "by readings" for BY_READINGS (the agent wrote its own longer text). Verified: backend Slice 6 tests green (PlotTariffsTests 26, TariffsTests 25, TariffScopeTests 29, TariffsAcceptanceTests 24, ChargeCalculatorTests 30, ImportPreviewMetersTests 9, ImportCommitTests 25, ImportAcceptanceTests 11, TemplateWriterTests 17, PlanDigestTests 8, MeterCatalogTests 2, ModularityTests 2); client test/typecheck/lint/format green; `pnpm gen:api` no diff.
- Slice 6 committed `acae94e` — tests green; review round 1 running. Slice 7 DB/docs task delegated.
- Slice 6 review round 1: PASS.
- Slice 7 DB/docs (postgres-database): demo meter network (38 meters, 169 readings, plot 9 replacement, plot 20 stale 75 days, 2 pending from member@demo.local, Електрик gets METERS_VIEW + READINGS_ENTER), money-rules §6 + new §6.4, glossary, architecture, meters README. Verified: `./mvnw -B verify` 1,688 tests, 0 failures; line coverage 0.97947 → floor stays 0.97 (rounded down; unchanged). Proposed CLAUDE.md edit for the owner: electrician line → 'Member plus the demo custom role "Електрик" (METERS_VIEW and READINGS_ENTER)'. e2e task delegated.
- Slice 7 e2e (react-frontend): `make e2e` 99 passed / 0 failed (new meters.spec.ts, 5 tests; language-purity covers the new screens; design-system menu count; tariffs-acceptance now expects "немає лічильника" on a plot without a meter). Verified: `make check` green (backend 1,688 tests, 0 failures, coverage floor met; client 56/7/110/424).
- Slice 7 committed `304f683` — make check + make e2e green; review round 1 running. Slice 8 waits for it (gate).
- Slice 7 review round 1: PASS. Gate open (Slices 1–7 PASS); Slice 8 delegated to testing-expert.
- Slice 8 (testing-expert): MetersAcceptanceTests (33) + meters-acceptance.spec.ts (10); `*AcceptanceTests` 103/0 failures; `make e2e` 109 passed / 0 failed; RED shown (backend 4 tests, e2e 2 tests). Note: the agent purged its own `E2E…` fixture meters/readings/audit rows from the local dev DB with SQL (local only). Two GAPs logged in tasks.md:
  - GAP 1 — demo has two pending member readings (plots 7, 15); spec §2.10 says one. AUTO: align the demo block to the spec (keep plot 7) — the spec is the contract (rule: conservative, smallest change; spec wins over data).
  - GAP 2 — §2.6 #3 "Ділянку не знайдено" / "Plot not found": the API answers generic `NOT_FOUND`. AUTO: plot-scoped meter endpoints (`GET /plots/{id}/meters`, `POST /plots/{id}/readings`) answer 404 with a specific code `PLOT_NOT_FOUND` and catalog texts (rule: follows the existing `MEMBER_NOT_FOUND` / `OWNERSHIP_NOT_FOUND` pattern; server sends codes, client texts).
  - Fixes go to the builders sequentially (they share backend/target), then the testing task re-runs to tighten its assertions.
- GAP 1 fixed (postgres-database): demo has one pending reading (plot 7); DemoMeterDataTests 8/8. GAP 2 fixed (spring-backend): `PLOT_NOT_FOUND` on the plot-scoped meter endpoints + catalog texts; RED shown (5 tests); MetersTests/MemberReadingsTests/MetersAcceptanceTests/ModularityTests 86/0 failures; client checks green. Testing task re-run to tighten §2.10, remove the GAP markers and run make e2e.
- Slice 8 re-run (testing-expert): §2.10 tightened to exactly one pending demo reading; GAP markers removed; `*AcceptanceTests,DemoMeterDataTests,ModularityTests` 111/0 failures; `make e2e` 109/0 after removing the stale plot-15 row by hand. It found the repeatable-script DELETE built the id from the meter id without dashes (matched nothing on existing DBs); I fixed the one line and confirmed in the local DB that both expressions differ and the fixed one equals the INSERT's id. Verified: DemoMeterDataTests 8, MetersAcceptanceTests 33 green.
- Slice 8 committed `cb9877f` (acceptance tests + the two GAP fixes: one pending demo reading, `PLOT_NOT_FOUND`); review round 1 running.
- Slice 8 review round 1: PASS. Every slice review is PASS.

## Phase 4 — verify and finish

- Started: /awos:verify (pipeline mode).
- Phase 4 UI check: `make up` (detached; log scratchpad/makeup010.log), screenshots from demo data with no writes (react-frontend, temporary Playwright spec removed): docs/screenshots/010-meters-and-readings-*.png (18 files). Findings:
  - DEFECT: `GET /audit?entityType=METER` → 500. Cause: `METER_PARENT_CHANGED` (written by the tree move, Slice 5) is missing from the contract's `AuditAction` enum, so `AuditAction.fromValue` throws in `AuditQueries`. Affects §2.9 (audit). Verify fix delegated to spring-backend.
  - Not a defect: "Без показу з" checkbox reported as not toggling by the screenshot script; `meters-acceptance.spec.ts` §2.7 #2 clicks it and asserts it is checked (green in make e2e), and the handler sets the URL param — the script's click missed.
  - Not required by the spec: no successor/predecessor link on the meter card (the plot card shows the replacement history, §2.3 #1); the demo's rejected reading is on plot 1 (not member@demo.local's) — §2.4 #10 is covered by tests; local audit list contains e2e leftovers (local DB only).
  - `make up` stopped before the fix (it runs from backend/target).
- Verify fix `997d9cb` (spring-backend): `METER_PARENT_CHANGED` added to the contract `AuditAction` enum; regression test MeterTreeTests.theAuditLogShowsAMoveWithItsParentAndDate (RED 500 → green); audit/meter targeted tests 88/0; client checks green. Fix review r1 running.
- Fix review r1 (997d9cb): PASS. All 41 criteria marked [x] with evidence in functional-spec.md; change-log entry added.
- Full suite on the branch (`make check`, after the verify fix): backend 1,722 tests, 0 failures, coverage met (floor 0.97); client ui-tokens 56, api-client 7, shared 110, web 424. Baseline: 0 failing → no regressions. `make e2e`: 109 passed / 0 failed (Slice 8 run, before the contract-only verify fix; CI runs it again).
- Clean-up: only the main worktree remains; `make up` stopped.

## Final report

- **Branch:** `010-meters-and-readings` from main @ 063af9f. Commits: d418b60 specs; d201cfc Slice 1 + 73e4b06 review fix; 738723b owner's chore (agent model, surefire timeout); eca98e7 Slice 2 + 4fd731d review fix; c95e714 Slice 3; 18f2ee9 Slice 4; 76ab842 Slice 5 + 8c40cab review fix; acae94e Slice 6; 304f683 Slice 7; cb9877f Slice 8 (incl. the two GAP fixes); 997d9cb verify fix (§2.9 audit); final docs commit.
- **Acceptance criteria:** 41/41 verified with evidence (acceptance tests, e2e and screenshots `docs/screenshots/010-meters-and-readings-*.png`); no `[?]`.
- **Reviews:** Slice 1 MAJOR (locale-blind parse, trailing zeros) → PASS r2; Slice 2 MAJOR (import catalog text) → PASS r2; Slice 3 PASS r1; Slice 4 PASS r1; Slice 5 MAJOR (removal-day off by one) → PASS r2; Slice 6 PASS r1; Slice 7 PASS r1; Slice 8 PASS r1; verify fix PASS r1. Minor notes in review-log.md.
- **Full suite vs baseline:** baseline 0 failing; branch 0 failing.
- **Assumptions after Phase 1:** listed in Phase 2 above and in the spec's "Assumptions & Decisions"; slice-level decisions logged per slice (supersededReadingIds array; `GET /meters/{id}/consumption`; remove/replace refused while a reading is pending; tree nodes are replacement chains; import sheet plot meters only, unchanged by serial).
- **Decided automatically:**
  - Phase 1: no blocking question; module layout, MeterValue, tariffs → meters dependency, plot veto SPI, round order, unusual rule, menu/landing, tree screen under Settings, import sheet rules, demo electrician (see "Phase 1 — decisions").
  - Phase 2: NO_METER texts, refusal messages, ADR 0014, range-based ReadingUsage, correction = new superseding row, per-row batch transactions, `GET /readings?status=PENDING`, parent ranges with an exclusion constraint, relative demo dates.
  - Slice 8 GAPs: demo aligned to one pending reading (spec wins over data); `PLOT_NOT_FOUND` code for another plot's meters (follows MEMBER_NOT_FOUND / OWNERSHIP_NOT_FOUND).
  - Reviewer escalations: none (no round 3 reached).
- **Parked items:** none.
- **For the owner:**
  - Proposed CLAUDE.md edit (protected file, not made): the Keycloak test users line `electrician@demo.local → Member plus the demo custom role "Electrician" (no permissions until meters arrive)` → `Member plus the demo custom role "Електрик" (METERS_VIEW and READINGS_ENTER)`.
  - Local dev DB: agents deleted their own e2e fixture rows and a stale demo pending row with SQL (local only); the repeatable demo script now converges an existing DB on its own.
  - Notes for 011 from reviews: `ReadingUsage` is called per reading (batch it); `hasMeterBetween` checks the whole month; demo quarter A rate ≈ the sum below it (losses near zero); a general test that every written audit action is in the contract enum is missing.
- **Suggested follow-ups:** `/awos:architecture` only if the owner wants the meters module described beyond the update in this branch (architecture.md was updated in the slices); no `/awos:hire` needed.
