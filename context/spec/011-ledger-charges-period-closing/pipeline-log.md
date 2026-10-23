# Pipeline log — 011 Ledger, charges and period closing

- Status: running
- Phase: 1 — requirements analysis
- Branch: 011-ledger-charges-period-closing (planned) · Base: main (= origin/main) @ 6325ed7
- Requirement: docs/features/011-ledger-charges-period-closing.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on — not started yet

## Project facts

Same as 004–010 (see `context/spec/010-meters-and-readings/pipeline-log.md`). Re-checked 2026-10-22: origin/main @ 6325ed7 (011 doc #40, e2e smoke set #39, guides #38, meters owner review #37, actions bump #14); pipeline tooling present on main; clean tree. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer, devops-infra. Build/test: backend `cd backend && ./mvnw verify`, one module `./mvnw test -Dtest='app.nadel.<module>.**'`, one class `./mvnw test -Dtest=<Class>`; clients `pnpm -r typecheck && pnpm -r lint && pnpm -r test`; full suite `make check`; e2e `make e2e` (own specs + `--grep @smoke` locally). Safe local run: `make up` (local docker Postgres + Keycloak, no external services). Browser tool: claude-in-chrome. Latest migration V12 → next V13. Git rules: Conventional Commits; no AI attribution (CLAUDE.md overrides harness attribution); feature branch only; push only the spec branch.
- A review run from 010 is still listening on :8080 (java pid 35178) and :5173 (node pid 35159); the pids differ from the 010 pids file, so they are not processes this run started. Handled before Phase 6.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** the ledger starts charging. Append-only `charge` (+ `charge_part`) guarded by a DB trigger; kinds REGULAR/REVERSAL/OPENING; sources TARIFF/CONSUMPTION/LOSSES/COMMON_CONSUMER/OPENING/MANUAL_REVERSAL; billing months OPEN/CLOSED closed in order with a non-blocking check list, late charges into the earliest open month; month run of recurring tariffs (preview, idempotent rerun, conflicting tariffs to the queue); a CONSUMPTION charge per approved PLOT-meter reading (days rule, tariff split, rounded once, replacements, water only when connected and active); losses FIXED_PERCENT per reading or PROPORTIONAL runs per tree node (negative/unknown handling, overlap refusal, run reversal); common consumer runs into a seeded COMMON_CONSUMERS charge type (CONNECTED_PLOTS / EQUAL_PER_PLOT / SERVICE_USERS / NOT_CHARGED); "Не нараховано" queue with retry; water connections with history (card, bulk, import column); opening balances (by hand + import sheet uk/en); plot status recalculated from debt and age with thresholds in settings; TariffVersionUsage and ReadingUsage implemented; 4 permissions; audit; Ukrainian demo data; money-rules, glossary, guides. Users: chairman (all), treasurer (view), member (own account).

**Prior work:** Agent A — nothing off main (origin/docs/feature-011-ledger = the doc already squashed as da0ae3d; old feature branches are pre-squash copies). Agent B — no ledger code; stubs to replace: `tariffs/internal/NoChargesYetUsage` (TariffVersionUsage, refused at TariffCommands:200) and `meters/internal/NoChargesYetUsage` (ReadingUsage is date-span based: `isUsed(tenant, meter, from, to)`); `plot_status` cache table exists (V1) with no writer API; no thresholds; `ChargeCalculator` lacks a per-version breakdown and a connected-days mask; tariff scope resolution and range consumption / tree slices are internal (new public facades needed); no reading-approved domain event; `Money` lacks negate/minus; no JobRunr; patterns for permissions (seed_base_roles CREATE OR REPLACE + targeted inserts), charge type seeding (`seed_charge_types`, deterministic ids), import sheets (010 MetersSheet + facade), audit via outbox, demo blocks in R__demo_data.sql with DemoTariffData/DemoMeterData helpers. Guides chapters 17–19 are "planned · 011". Verdict: **new; builds on 006–010**.

**Scope:** sections 2–9 of the feature doc. Out: section 10 (payments, 012 adjustments, reports, invoices, scheduled runs).

**Verification:** a unit test per §3 row on pure calculators; Testcontainers integration (trigger refusal, month run/rerun/close order, reading → charge via event, losses/common runs + overlap + reversal, queue retry, water connections, opening balances + import sheet uk/en, usage ports 409, status recalculation, permissions allowed/denied per endpoint, member visibility, isolation per new table, audit); web unit tests; e2e per §11; `make check`, `make e2e` (own specs + smoke), screenshots.

**Risks:** size (largest feature so far: ~10 tables, 4 calculators, 6+ screens); event-driven charging idempotency; ReadingUsage semantics for node meters; plot status writer across modules without cycles; demo data that must stay consistent with 010 readings; guide updates in two languages.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with worked examples).
- AUTO: new module `app.nadel.ledger` (depends on tariffs, meters, plots, settings, money, identity, audit, problem, tenancy); imports depends on ledger for the opening-balance sheet; plots exposes a public status writer (no plots → ledger dependency) (rule: CLAUDE.md modules, no cycles).
- AUTO: meters publishes a public domain event when a PLOT/any reading becomes APPROVED (entry, approval, import, replacement final value), carrying tenantId; ledger listens via the Modulith outbox, idempotent by reading id (rule: CLAUDE.md cross-module side effects through events).
- AUTO: runs (month, losses, common consumers) execute synchronously in the request transaction; no JobRunr (rule: scheduled runs out of scope; no dependency without a reason).
- AUTO: plot status: thresholds `red_amount` (default 1000.00) and `red_days` (default 90) in cooperative settings; YELLOW = any debt that is not red, no yellow thresholds (rule: money-rules §9 formula and product-definition "Yellow is any debt that is not red"; conservative).
- AUTO: ReadingUsage answers "used" when a non-reversed charge's calculation used a reading of that meter within the asked span: CONSUMPTION charges by service period for plot meters, LOSSES / COMMON_CONSUMER runs by range for node and common consumer meters (rule: port contract is date-span based; 2.1).
- AUTO: append-only by both REVOKE UPDATE/DELETE for nadel_app and a trigger on `charge` and `charge_part` (rule: ADR 0006 + spec 2.1).

## Phase 2 — branch and specs

- Spec folder and branch `011-ledger-charges-period-closing` from main @ 6325ed7 (= origin/main). Baseline `make check` started in a detached worktree (scratchpad/baseline-011).
- functional-spec.md written (Creation Mode, no markers; 13 requirement groups).
- AUTO: a charge booked in a closed month cannot be reversed by hand (rule: 2.5 run reversal only in open months; closed months corrected by 012 adjustments).
- AUTO: opening balance amount > 0 and date not in the future, booked in its month or the earliest open month (rule: 2.2 no future months; prepayments are phase 3).
- AUTO: debt age dates — month run: first day of the month (one-off: due date); reading: reading date; runs: range end; opening: its date (rule: 2.1 accrual date; conservative).
- AUTO: a late charge with no open month after the closed one opens the next month (rule: 2.2 earliest open month must exist).
- AUTO: water import column: так/yes → the only source, a source name → that source, ні/no/empty → unchanged (rule: 2.8; 008 idempotent re-import).
- AUTO: refusal messages chosen in the spec (rule: Problem codes need catalog texts).
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend; exploration from Phase 1 Agent B).
- AUTO: water connections in `meters` (rule: 010 water missing-readings list lives there; no cycle; imports already depends on meters).
- AUTO: one migration per slice from V13 (rule: slices testable on their own; expand-only).
- AUTO: a reversed tariff or interval charge is not re-created by a rerun or the listener; correction is 012 (rule: 2.3 "creates only what is missing"; conservative).
- AUTO: runs use end-of-day values `valueAt(to) − valueAt(from − 1)` (rule: reading-interval days rule; back-to-back runs without gap or overlap; §3 "01.10–30.10 (30 days)").
- AUTO: status colour derived at read time from cached debt + oldest date + thresholds (rule: 2.10; no scheduler in scope).
- AUTO: ReadingUsage from `meter_value_use` rows + meters span fix `[prev+1, next−1]` (rule: port is span-based; otherwise every reading after a charged one is refused).
- AUTO: demo ledger as SQL block with an owner-only maintenance bypass of the trigger (`SET LOCAL app.ledger_maintenance`), guarded by DemoLedgerDataTests (rule: existing demo pattern; DemoMeterData.clear must still work).
- AUTO: water connections changed with METERS_MANAGE; losses/threshold/common consumer settings with SETTINGS_MANAGE (rule: existing permissions; spec §6 lists only the four new ones).
- AUTO: preview digest, stale booking → 409 PREVIEW_OUTDATED (rule: 008 PlanDigest pattern).
- AUTO: a plot with charges cannot be deleted (PLOT_HAS_CHARGES via PlotChangeGuard) (rule: append-only ledger references the plot).
- tasks.md written: 10 slices (ledger core; months + status; month run + queue; reading charges + fixed %; water connections; proportional losses; common consumers; import; demo/docs/guides/e2e/coverage; Feature Testing & Regression by testing-expert). No staffing gaps. `<!-- not-user-reviewed -->` marker removed (pipeline rule: Phase 1 answers stand in for the plan review; plan listed in the final report).
- Baseline: make check green (backend 1,733 tests, coverage 0.9794 vs floor 0.97; clients 56/7/122/438). Recorded in review-log.md; baseline worktree removed.

## Phase 3 — implement

- Slice 1 DB (postgres-database): V13 (billing_month, charge, charge_part, meter_value_use; trigger with owner-only maintenance bypass; REVOKE; permissions + COMMON_CONSUMERS seed); LedgerTenantIsolationTests 22 + LedgerAppendOnlyTests 8 green with RED validation. Test support creates a `nadel_owner` role in Testcontainers. Java permission tests left red for the backend task. Backend task delegated to spring-backend.
- Slice 1 backend (spring-backend): ledger module, reversals, opening balances, account API, ChargeTypeCatalog facade in tariffs; full backend verify 1,816 green per builder. V13 edited (not yet committed) so OPENING charges keep an optional reason. Verified by orchestrator: 208 tests (ledger, money, roles, seeds, modularity, charge types, me endpoint) green. Web task delegated to react-frontend.
- Slice 1 committed `32ed18c` — backend 208 slice tests green (orchestrator), clients green (shared 130, web 453), gen stable; review round 1 running.
- Slice 1 review round 1: MAJOR_ISSUES (M1 negative opening amount text, M2 V13 seed hides name clashes). Fixes run in the detached review worktree at 32ed18c (postgres-database for M2, react-frontend for M1) so they never mix with the Slice 2 work in progress; the fix commit is then cherry-picked onto the branch.
- Slice 1 review fixes committed `303f4ff` (cherry-picked from the review worktree); LedgerMigrationTests/Isolation/AppendOnly/ChargeTypes/SeedBaseRoles 70 green and RED checked by orchestrator; web 455 green per builder; round 2 running.
- Slice 1 review round 2: PASS (minors noted in review-log).
- Slice 2 backend (spring-backend): V14 (billing_month guard trigger; settings columns), billing tag endpoints, MonthClosing, MeterNetwork.staleMeters, PlotDebtCache, read-time PlotStatusPolicy, PlotStatusRefresher; full backend verify 1,853 green, coverage 0.9802 (builder).
- AUTO: months without a row are listed as OPEN without creating rows; closing creates the row (rule: a GET must not write; the treasurer may only view).
- AUTO: `PUT /settings` is a partial update (`CooperativeSettingsUpdate`), `{}` → 200 no change (rule: new sections save independently).
- AUTO: settings read without a bound cooperative return defaults (rule: RLS shows no row anyway; no tenant data exposed).
- Slice 2 verified by orchestrator: backend 849 (ledger, plots, settings, meters, modularity) green; clients shared 130, web 466, typecheck/lint/format green; gen stable.
- Slice 2 committed `057fbd4` — tests green; review round 1 running.
- Slice 2 review round 1: MAJOR_ISSUES (M1 calendar gap month, M2 audit status raw). Fixes in the review worktree (spring-backend M1, react-frontend M2).
- Slice 2 review fixes committed `4a0698d` (cherry-picked); M1 50 tests green + RED per builder (log checked); M2 clients green + RED; round 2 running.
- Slice 2 review round 2: PASS.
- Slice 3 backend (spring-backend): V15 not_charged_item, DaySet, ChargeCalculator.breakdown, TariffBook/TariffResolver, conflict warning, MonthRun, NotChargedQueue, LedgerTariffVersionUsage; slice 416 + full verify 1,929 green, coverage 0.9796 (builder); RED checks on source-key skip, conflict queueing, version usage, water mask. gen stable (orchestrator). Web task delegated to react-frontend.
- AUTO: chargeable plots = active, not common land (`PlotCatalog.findChargeable`) (rule: money-rules — common land is never charged).
- AUTO: the month run queues only conflicts where at least one tariff charges > 0.00 that month; "no tariff / outside scope / excluded / type inactive" are not queue items (rule: the queue lists what should have been charged but was not; nothing is owed in those cases).
- AUTO: a meter tariff whose version is per-kWh/per-m³ in the month gets no tariff-run charge (money-rules §4.1 "by readings" for the whole tariff; slice 4 charges readings).
- AUTO: a preview writes nothing; a booking marks `run_at` even with 0 charges (rule: a GET-like preview must not write; the check list needs "run done").
- AUTO: a retry of an item whose month was closed books into the earliest open month with its own service period (rule: same as Slice 2 late booking).
- User instruction (2026-10-23): builders never run the full backend suite inside a slice — only the slice Verify command and the tests of changed classes; the full suite runs once in Phase 4. Added to delegation-rules.md and passed in every builder prompt.
- Slice 3 web (react-frontend): RunPreviewDialog, NotChargedPage, TariffConflicts, audit labels; clients green (web 478) per builder. Found: AuditAction enum lacks BILLING_MONTH_RUN / NOT_CHARGED_RETRIED (audit listing would fail on fromValue) — fix delegated to spring-backend.
- Slice 3 audit fix (spring-backend): AuditAction enum gains BILLING_MONTH_RUN, NOT_CHARGED_RETRIED (audit listing returned 500 — RED shown); typed `LedgerAuditPublisher.Action` + `LedgerAuditActionsTests` guard; ledger+audit 205 green per builder.
- Slice 3 verified by orchestrator: backend 416 (ledger, tariffs, modularity) green before the audit fix; clients ui-tokens 56, api-client 7, shared 130, web 478, typecheck/lint/format green; gen stable; web RED checked (digest dropped → booking test fails; gating removed → Retry test fails).
- Slice 3 committed `9006194` — tests green; review round 1 running.
- Slice 3 review round 1: MAJOR_ISSUES (M1 a booked run retries only its own month's queue items; spec §2.4/§2.7 want every open item retried). Fix in the review worktree (spring-backend) with minors 2 (already charged plot not queued as conflict) and 4 (lock all charge types). Slice 4 builder told about the cross-month design to keep the later cherry-pick simple.
- AUTO: only tariffs that would charge > 0.00 for the plot in the month take part in a conflict (rule: two one-time fees of one type due in different months must not block each other forever — review minor 1; the tariffs page warning stays as is).
- Slice 3 review fixes committed `0f8d1fe` in the review worktree (not yet on the branch: overlaps MonthRun/NotChargedQueue with Slice 4 work in progress; cherry-pick right after the Slice 4 commit). ledger+tariffs 423 green, RED for M1, conflict rule, minor 2 (logs checked). New tenant queue advisory lock; retried charges count in totals/digest. Also aligned the conflict paragraph in docs/features/011. Round 2 running.
- Slice 3 review round 2: PASS (minors in review-log). Fix 0f8d1fe to be cherry-picked after the Slice 4 commit.
- Slice 4 backend (spring-backend): meters event PlotMeterPointsChanged, MeterNetwork.lockSharedPoints, span fix, ReadingChargeCalculator/Planning, IntervalCharging, ReadingCharges listener, LedgerReadingUsage; slice 476 + MetersAcceptanceTests 33 green, RED per class (builder); no migration. Web task delegated to react-frontend.
- AUTO: a charge "uses" its meter on the closed range of its two meter_value_use dates; isUsed checks overlap with the asked span, ignoring reversed charges (rule: a reading must not be inserted inside a charged interval).
- AUTO: interval retries happen only on a booked month run (not in preview/digest/totals) (rule: interval charges are booked by readings, the run only retries them).
- AUTO: the meter import publishes no reading event (it only creates meters with an initial value; no interval closes).
- AUTO: demo ELECTRICITY type switched off during meter tests (`DemoReadingCharges`) so meter tests don't book demo charges (rule: tests leave the demo cooperative unchanged).
- Slice 4 web (react-frontend): LossesSettingsSection, ChargeDetailDialog consumption/losses, invalidateAfterReading (now + 2.5 s), INTERVAL rows with meter link, audit from/to; RED per builder. Orchestrator: clients 56/7/130/487 green, typecheck/lint/format green, gen stable.
- Slice 4 verified by orchestrator: backend 915 (ledger, meters, acceptance, imports, settings, modularity) green, 1 skipped = pre-existing ImportFixtureGenerator (property-gated).
- Slice 4 committed `a75bbd1` — tests green.
- Slice 3 review fixes cherry-picked as `ff5780c` (conflicts in MonthRun, NotChargedQueue, NotChargedItemRepository, ledger README resolved by the orchestrator: the run does interval retries right after the month lock, then the queue lock, then all charge types and tariffs; IntervalCharging now locks all charge types like the run and drops its unused TariffBook). ledger+tariffs+meters+modularity 745 green after the merge; typecheck, format and gen stable. Review worktree s3 removed; Slice 4 review round 1 running.
- Slice 4 review round 1: PASS (minors in review-log; three small web ones go into the Slice 5 web task). Worktree removed.
- Slice 5 backend (spring-backend): V16 plot_water_connection, meters.WaterConnections + commands/queries/controller, ledger WaterDays (replaces the applicableDays seams), missing-readings and stale-meters filters; slice 513 green, RED in two runs (builder); gen stable. Web task delegated to react-frontend together with three Slice 4 review minors.
- AUTO: a water source is the cooperative supply (no meter) or an active COMMON_CONSUMER meter (rule: 010 has no separate water-source model).
- AUTO: new problem code WATER_SOURCE_IN_USE when deleting a meter plots are or were connected to (rule: a RESTRICT foreign key must not surface as 500).
- AUTO: a water interval with no connected day is Nothing before tariff resolution, so unconnected plots are never queued (rule: spec — unconnected plots are never queued).
- AUTO: connection dates are not limited to today or earlier (spec gives no such rule); archived plots and common land are refused.
- NOTE for Slice 9: until demo connections are seeded, the demo's 4 water meters drop out of missing-readings and stale-meter lists; the demo data must connect their plots. Not handled (READMEs): a replaced well meter keeps its connections on the old id; a connection entered after a charged reading does not change that charge (012).
- Slice 5 web (react-frontend): WaterConnectionPanel, WaterSourcesSection, water problem/audit texts, Slice 4 minors (delete refetch, losses changed check, losses text). Orchestrator: clients 56/7/130/507, typecheck/lint/format green, gen stable; RED checked on the ReadingHistory delete refetch. AUTO: water PLOT_ARCHIVED uses its own text (generic one speaks of deleting).
- Slice 5 verified by orchestrator: backend 1,034 (meters, ledger, plots, acceptance, modularity) green.
- Slice 5 committed — tests green; review round 1 running.
- Slice 5 review round 1: PASS (web minors 1-3 go into the Slice 6 web task; minor 4 to Slice 9). Worktree removed.
- Slice 6 backend (spring-backend): V17 losses_run/losses_run_node (run_reversal_guard trigger, partial exclusion), MeterNetwork.network, LossesCalculator, LossesPlanning, LossesRuns + controller, LOSSES_NODE queue retries, run reversal; slice 555 green, RED per class (builder); gen stable. Web task delegated to react-frontend with three Slice 5 review minors.
- AUTO: losses run booking month = month of `to` or the earliest open month after it (spec wording); RUN_IN_CLOSED_MONTH only refuses reversing a run whose month is closed.
- AUTO: a node already charged by a booked run for any day of the range is ALREADY_CHARGED in the preview; booking it is 409 LOSSES_RANGE_OVERLAP, the user books the other nodes via nodeIds; the digest leaves ALREADY_CHARGED nodes out.
- AUTO: each plot's losses kWh from all nodes priced once with its ELECTRICITY tariff → one LOSSES charge per plot per run; a plot that cannot be priced is listed with a reason, no charge, no queue item.
- AUTO: UNKNOWN and SKIPPED_ABOVE nodes open a LOSSES_NODE queue item; its retry runs that node alone over the item's range (audit entity type METER).
- AUTO: `to` after today → 400 DATE_IN_FUTURE (spec silent; nothing could be charged anyway).
- Slice 6 web (react-frontend): LossesPage on reusable RunRangePage/RunHistory/ReverseRunDialog/RunWarnings, audit LOSSES_RUN, LOSSES_NODE queue rows, Slice 5 minors (water buttons, uk texts, WATER_SOURCE_IN_USE text); RED per builder. Orchestrator: clients 56/7/130/542, typecheck/lint/format green, gen stable. AUTO: overlap UX — Book hidden, "preview without the charged nodes" re-previews with nodeIds, then books with that digest.
- Slice 6 verified by orchestrator: backend 606 (ledger, meters, audit, modularity) green.
