# Pipeline log — 015 payments and allocation

- Status: running
- Phase: 3 (implement, Slice 1)
- Branch: 015-payments-and-allocation (planned) · Base: main @ 75db154
- Requirement: document docs/features/015-payments-and-allocation.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet)

## Project facts

- Base branch: `main` (origin/HEAD not a symbolic ref; `main` == `origin/main` @ 75db154, no local-only commits).
- Pipeline tooling present on `main` (skill, slice-review-loop, slice-reviewer agent, AWOS wrappers and core files).
- Working tree: clean.
- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`.
- One test: `cd backend && ./mvnw test -Dtest='<Class>'`; module: `-Dtest='app.nadel.<module>.**'`; web `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check` (contract, format, lint, backend verify with Testcontainers + JaCoCo floor, client typecheck/tests), then `make e2e` (needs JDK 25).
- Specialist agents: spring-backend, postgres-database, react-frontend, devops-infra, expo-mobile; testing agent: testing-expert; reviewer: slice-reviewer.
- UI: yes (web). Browser tool: claude-in-chrome (deferred tools available).
- Safe local run mode: `make up` (local docker-compose Postgres + Keycloak, backend :8080, web :5173), demo users from CLAUDE.md.
- Git rules: Conventional Commits; no AI attribution (no Co-Authored-By, no "Generated with"); never commit on main; agents push only the spec branch in Phase 5, no force; never merge/approve.
- Protected files (propose only): CLAUDE.md, .claude/, .mcp.json; also .github/ per pipeline rules.
- Publish pre-flight: `gh` logged in (github.com, Philatelist); origin = github.com/Philatelist/nadel-platform; CI workflow `.github/workflows/ci.yml` (jobs backend, clients, security, sast, e2e).
- Next spec number: 015. Latest migration: V26.

## Settings

- autonomy minimal: questions=blocking, reviewerEscalation=auto, notGreen=ask, gitUnsafe=ask, notTestableLocally=auto
- publish=pr+checks, reviewRun=on, notify=true

## Log

- Phase 0 done. Phase 1 started: analysis agents A (git history) and B (code search) launched.
- Note: a stale worktree from an earlier 012 run exists (`.../60c46cb4-.../scratchpad/run-012`, detached 9c58e55). Not created by this run; left untouched.
- Phase 1b done: Agent A (git) — no prior work on any branch/stash/worktree; `origin/docs/feature-015-payments` equals main (the merged requirement). Agent B (code) — nothing implemented; builds on 011/012/013/014.

## Requirements brief

**Requirement:** docs/features/015-payments-and-allocation.md. The treasurer records cash (internal receipt with a
sequential number) and hand-entered bank payments for a plot, or one payment split over several plots of one payer; the
server allocates them to the plot's open charges (oldest first, then charge type order), keeps the remainder as
prepayment and auto-allocates it to later charges (setting, on by default). Reversals, reallocation, automatic releases
on charge reversals and negative adjustments. Debt, plot status and reports switch to "charges − allocations". Users:
treasurer and chairman (entry, reversal), member (own payments on the account).

**Prior work:** none (Agent A). Verdict: **new**, extends 011 (ledger, plot account, status), 012 (adjustments,
recalculation booking paths), 013 (reports, template catalog), 014 (privacy cipher, encrypted audit values).

**Scope:** requirement §§2–10; out of scope §11. Mobile: no change (shell only until phase 5).

**Findings that shape the design:**
1. `ReportsAcceptanceTests.section22_noReportHasAPaidColumnOnScreenOrInTheFile` asserts no "paid" column → rewritten.
2. "Until payments exist" wording in DebtCalculator, PlotDebts, LedgerReadModel, PlotStatusPolicy, reports README, the
   `OLDEST_UNPAID_IS_OLDEST_CHARGE` header note (bundles, i18n, openapi) → updated.
3. money-rules §7 says auto-allocation "when a period is closed"; 015 (decided with the owner) says on every booking →
   §7 rewritten. Glossary names `PAYMENTS_ALLOCATE` → replaced by 015's three permissions.
4. Every charge-releasing path (manual reversal, run reversals, recalculation reversals and negative adjustments) must
   call one `AllocationReleaser` in its own transaction.
5. Next migration V27 (V24 is unused; Java migrations 22.1, 25.1). Permission seeding as V19/V20 (redefine
   `seed_base_roles` + idempotent insert for existing cooperatives).
6. Demo ledger block in `R__demo_data.sql` (`${demoledger}`, `app.ledger_maintenance`); `payer_note_enc` cannot be
   produced in SQL → demo payments carry no payer note in SQL (or Java seeding as in 014).

**Verification:** backend unit + Testcontainers (`./mvnw test -Dtest=...`), `make check`, Playwright specs
(`pnpm --filter web exec playwright test payments`) + smoke; UI criteria via claude-in-chrome on `make up`.

**Risks:** concurrency (receipt counter, plot `FOR UPDATE` lock order vs. the charging lock and month `FOR SHARE`);
async auto-allocation idempotency; many booking paths to wire to the releaser; report/test churn; demo data keeping
red plots; guide PDFs.

## Decisions (no blocking questions; autonomy minimal → recommended answers taken)

- AUTO: (questions=blocking, none blocking) Module: payments and allocations live in `ledger` (requirement default).
- AUTO: Event: keep `ChargesBooked` for charge bookings (status refresh + auto-allocation listener) and add a second
  event `PaymentsChanged(tenantId, plotIds)` for payments, reversals, allocations and releases (status refresh only);
  smaller change than renaming every publisher.
- AUTO: Auto-allocation listener runs after commit in its own transaction under `TenantContext.runAs`, locks the plot
  `FOR UPDATE`, allocates only the plot's current prepayment; releases inside a charge-booking transaction are followed
  by the `ChargesBooked` of that transaction, so the same listener re-places released money.
- AUTO: Lock order: charging lock (when the path holds it) → plot `FOR UPDATE` → month `FOR SHARE`; payment entry takes
  the plot locks in plot-id order.
- AUTO: A payment reversal releases the payment's allocations; the money is gone, so no prepayment results from it.
- AUTO: Split: proposal over the payer's current plots (common land and archived excluded) by debt, cents down, leftover
  cents by largest remainder then plot order; zero total debt → all to the first plot as prepayment.
- AUTO: `BANK` payments have no receipt number; `CASH` always one (one per group).
- AUTO: A member sees the payments of their own plots with receipt number, amount, date, source and allocations, but
  not the payer note nor the author name (personal data of others / staff names, as for charges).
- AUTO: money-rules §7 rewritten to 015's rule; glossary permission example updated.
- AUTO: Payments on a plot without an owner (GREY) are allowed; common land and archived plots are refused.
- AUTO: Demo payer notes: none in SQL (the cipher needs Java); the demo split/reversed payments carry no note.
- Baseline: `make check` on main 75db154 green (2969 backend tests, 0 failures); worktree removed.
- Phase 2.3: functional-spec.md written (Creation Mode, no markers).
- Phase 2.4: technical-considerations.md written from spring-backend, postgres-database and react-frontend proposals.
  Tech assumptions (all AUTO, conservative choice, listed in the doc's "Assumptions"):
  - AUTO: per-plot lock = transaction advisory lock `PlotLedgerLock` (requirement says "plot FOR UPDATE"; a row lock on
    the plots module's row would cross modules and deadlock with the FOR SHARE held by booking paths).
  - AUTO: planner tiebreak "charge id" → booking order (created_at, id) since ids are random UUIDs.
  - AUTO: payment reversal month = 011 charge-reversal rule (original's month while open, else earliest open); the
    functional spec 2.5 says so.
  - AUTO: receipt number stored on every part of a cash group; gist exclusion constraint.
  - AUTO: deferred DB backstop triggers for payment/charge caps and releases, plus the service checks.
  - AUTO: allocation value date for "as of" reports = GREATEST(received_on, accrued_on); totals.paid.<TYPE> on accrual
    basis; payer notes in the payments report via `PaymentNotes` (MEMBERS_VIEW).
  - AUTO: `Money.allocate` for the split rounding (ADR 0013 note); preview returns all open charges; no sweep when the
    setting is turned on; mobile unchanged.
  - Skill-update check of the awos-recruitment MCP not run (it requires asking the user; pipeline asks nothing).
- Phase 2.5: tasks.md written — 9 slices (8 implementation + Feature Testing & Regression on testing-expert).
  - AUTO: staffing gap — docs and guides task (Slice 8) has no specialist → `general-purpose` (no Open Questions section kept); noted for the final report.
  - AUTO: `<!-- not-user-reviewed -->` marker removed (Phase 1 decisions stand in for the plan review); plan listed in the final report.
- Phase 2 done.
- Slice 1 committed 23557e8 (verify: 168 tests 0 failures + typecheck); review round 1 started.
- Slice 1 review round 1: PASS (minor notes in review-log).
- Slice 2 verify: backend 476 tests 0 failures; pnpm typecheck/lint/test green.
- Slice 2 committed faf6564; review round 1: PASS (minor notes in review-log).
- Slice 3 backend: split implemented (38 narrow tests green). Builder choices (assumptions): a 0.00 part is shown but not saved;
  a part without an amount takes the proposal; payer with no eligible plot → 400 `split.payerMemberId` NOT_FOUND; group id =
  first part's id; split weights = open amounts (prepayment not subtracted); only part plots are locked.
- Slice 3 verify: backend 43 tests 0 failures; pnpm typecheck/lint/test green.
- Slice 3 committed ec792e9; review round 1: PASS (minor notes in review-log).
- Slice 4 backend: reverse/reallocate/prepayment in new PaymentCorrections (76 narrow tests green). Builder choice: 409 NOTHING_TO_ALLOCATE when no prepayment or no open charge.
- Slice 4 verify: backend 29 tests 0 failures; pnpm typecheck/lint/test green.
- Slice 4 committed 2bf814c; review round 1: PASS (minor notes in review-log).
- Slice 5 backend: releases wired into Reversals, run reversals, RecalculationService; PrepaymentAllocator; setting (785 tests in ledger/settings green). Note: in recalculation the plot ledger locks come after the plan's month FOR SHARE locks (builder's argument: shared month locks only) — flagged for review.
- Slice 5 verify: backend 785 tests 0 failures; pnpm typecheck/lint/test green. Settings switch shown only with SETTINGS_MANAGE (whole settings block is gated).
