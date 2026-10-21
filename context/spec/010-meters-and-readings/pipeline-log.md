# Pipeline log — 010 Meters and readings

- Status: running
- Phase: 3 — implement (Slice 5)
- Branch: 010-meters-and-readings · Base: main (= origin/main) @ 063af9f
- Requirement: docs/features/010-meters-and-readings.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet)

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
