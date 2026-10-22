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
