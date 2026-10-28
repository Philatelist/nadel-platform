# Pipeline log — 015 payments and allocation

- Status: finished
- Phase: 6 (review run running)
- Branch: 015-payments-and-allocation (planned) · Base: main @ 75db154
- Requirement: document docs/features/015-payments-and-allocation.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/56 · Checks: green (run 40715246536 on 03b0e4d)
- Review run: running at http://localhost:5173 (web), http://localhost:8080 (API), http://localhost:8180 (Keycloak); make up PID 4980 (pids in the session scratchpad review-run.pids)

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
- Slice 5 committed 08e3d1d; review round 1: PASS (deadlock analysis: no cycle; minor notes in review-log).
- Slice 6 backend: account + member account (same endpoint) + received (506 tests green). Builder choices: prepayment placements as a separate account list grouped per run; lists returned separately, client merges by date; CHARGES_VIEW-only custom role sees no payment rows.
- Slice 6 verify: backend 504 tests 0 failures; pnpm typecheck/lint/test green.
- Slice 6 committed f6b5f41; review round 1: PASS (minor notes in review-log).
- Slice 7 backend: reports after payments, payments report, templates (467 tests green). Builder choices: payments report = month range + optional received-day range (days win); reversed payments listed but not in per-source totals; payments report counts as personal data; template payments row set has no payer; per-type column headers '<type>: <family>'.
- Slice 7 verify: backend 444 tests 0 failures; pnpm typecheck/lint/test green.
- Slice 7 committed 592d62a; review round 1: PASS (minor notes in review-log).
- Slice 8 demo data: 14 payments on 14 plots (full, partial, bank, prepayment plot 20, split 7+15 receipt 7, reversed plot 3); colours RED 11, YELLOW 8, GREEN 6, GREY 6; plot 1 unpaid red (42 tests green).
- Slice 8 e2e task: payments.spec.ts (4 flows, fresh plots, @smoke cash entry), fixes to plots-acceptance, ownership-acceptance, ownership, charges specs; capture-screens entries payments, payment-form, payment-card, payment-reverse. Static checks green (e2e tsc, eslint, prettier, node --check).
  - AUTO (notTestableLocally=auto): Playwright could not run — ports 8080/5173 are held by the owner's own `make up` stack (PIDs 2121/2245/2267/2383, started 09:42, not in any pipeline pids file), which the pipeline does not stop; Keycloak redirects only to :5173. Specs run in CI on the PR (smoke on every PR; the PR gets the `full-e2e` label so the split/reverse/member flows run too). The e2e acceptance criteria stay [?] until CI is green. Guide screenshots (`make guide-shots`) need the app on this branch → owner follow-up, as in 014.
- Slice 8 verify: DemoLedgerDataTests+PlotStatusRefreshTests 27/0; e2e tsc, lint, format-check green (guide HTML formatted with prettier). AUTO: PDFs not rebuilt (would show placeholders for new screens) → owner follow-up with guide-shots.
- Slice 8 committed 9d2b59b; review round 1 MAJOR_ISSUES (M1 role-names 22 permissions) → fix 72a880e → round 2 PASS. All implementation slices PASS; Slice 9 started.
- Slice 9: PaymentsAcceptanceTests 27/0 (RED checked on 3 tests); registry context/qa/list-of-tests.md updated; UI-only criteria covered by Vitest + payments.spec.ts (CI).
- Slice 9 committed cca927a; review round 1: PASS. All slice reviews PASS. Phase 4 started (full make check running in background).
- Phase 4 full make check (attempt 1 for regressions): backend 3281 tests, 1 failure + 1 error, not in baseline: MeEndpointTests.treasurerHasReadPermissions (order-dependent treasurer permissions), SealImportPlansMigrationTests (context at V26 fails schema validation on allocation). Regression fix delegated to spring-backend.
- Regression fix 939958d (ChargeTypesTests restores the treasurer role from its current permissions; SealImportPlansMigrationTests ddl-auto none below latest): fix review round 1 PASS. Full make check re-running.
- Full `make check` (after regression fix 939958d): green — backend 3281 tests, 0 failures (baseline main 75db154: 2969/0), JaCoCo floor met; contract, format, lint, client typecheck and tests green (web 810, shared 130). Regressions: none left. Measured line coverage 0.9778 → `coverage.minimum` raised 0.974 → 0.977.
- /awos:verify: 47 of 50 acceptance criteria [x] with test evidence (PaymentsAcceptanceTests + slice tests); 3 [?]: large receipt screen, settings switch after reload / treasurer view, electrician menu and no-access page — server side verified, real-browser rendering not checked locally (ports 8080/5173 held by the owner's own `make up`; Keycloak redirects only to :5173). Covered by `web/e2e/payments.spec.ts` and Vitest; Playwright runs in CI.
- Status: finished
- Product docs: suggested (not run) `/awos:architecture Payments and allocations live in the ledger module (feature 015): payment, allocation and receipt_counter tables (V27), PlotLedgerLock, AllocationReleaser, PrepaymentAllocator, PaymentsChanged event, PaymentNotes port; remove "payments" from the planned modules`.
- Worktrees: only the main checkout plus the pre-existing 012 worktree (not created by this run, left untouched).

## Final report (Phase 4)

- Branch `015-payments-and-allocation`, commits: 6a3d4a3 (specs), 23557e8 (S1), faf6564 (S2), ec792e9 (S3), 2bf814c (S4), 08e3d1d (S5), f6b5f41 (S6), 592d62a (S7), 9d2b59b (S8), 72a880e (S8 review fix), cca927a (S9), 939958d (regression fix), plus the Phase 4 commit.
- Acceptance criteria: 47/50 verified; 3 [?] (browser rendering, see above).
- Reviews: slices 1–7 and 9 PASS in round 1; slice 8 MAJOR_ISSUES (role-names permission count) → fixed → PASS in round 2; regression fix PASS. Minor notes in review-log.md.
- Full suite vs baseline: 3281/0 vs 2969/0; two regressions found and fixed in tests (order-dependent treasurer role; migration test context at V26).
- Decided automatically: see every `AUTO:` entry above (Phase 1 recommended answers, tech assumptions 1–12, builder choices logged per slice, e2e not runnable locally, PDFs not rebuilt).
- Staffing: docs and guides task ran under `general-purpose` (no docs specialist).
- Owner follow-ups: run `make guide-shots` (app on this branch) then `make guide` to add the four new screenshots and rebuild the PDFs; check the 3 [?] criteria in the browser; review the AUTO decisions; stale tariffs-chapter sentence in the board guide ("charges will appear later") predates this feature.
- Phase 5: pushed 015-payments-and-allocation, opened PR #56 (label full-e2e). Waiting for checks.
- CI run 40706708919 on PR #56: Clients, SAST, Security green; Backend red (PaymentAuditTests.theNoteIsNeverStoredOrLoggedInReadableForm:119 — unscoped audit scan for the note text "Оксана", order-dependent); End-to-end (full) red: 4 failed, 1 flaky of 142 — charges.spec member label renamed in Slice 6, design-system menu count/tab order (new Payments item), language-purity chairman every-screen (a screen without h1). payments.spec.ts passed. CI fix attempt 1 delegated (spring-backend, react-frontend).
- CI fix attempt 1 (Backend b0c3d4f, End-to-end ae4cb1b): fix review PASS. Pushing.
- CI run 40711286319 on 2bebfbb: Backend, Clients, SAST, Security green; End-to-end: 144 passed, 1 failed (meters.spec:272 — debounced street filter race, same class as PR #54), 1 flaky (privacy.spec:37, passed on retry). Earlier e2e failures fixed (language-purity split worked). CI fix attempt 2 (End-to-end) delegated.
- CI fix attempt 2 (End-to-end 7c36272): fix review PASS. Pushing.
- CI run 40715246536 on 03b0e4d: Backend, Clients, End-to-end (full), SAST and Security green.

## Final report addendum (Phase 5 and 6)

- PR: https://github.com/Philatelist/nadel-platform/pull/56 — "feat(ledger): payments, cash desk and allocation" (label full-e2e).
- Checks: green on run 40715246536 (03b0e4d) after three runs.
- CI fixes:
  - b0c3d4f (Backend): PaymentAuditTests' global note scan collided with a member named «Оксана» from MemberSearchTests (names are readable by design) → unique payer name per run in the note; scans stay global.
  - ae4cb1b (End-to-end): charges.spec label renamed in Slice 6; design-system menu count and Tab order (new Payments item); language-purity chairman screens split into shorter sessions (browser resource exhaustion on the Vite dev server, net::ERR_INSUFFICIENT_RESOURCES).
  - 7c36272 (End-to-end): meters.spec and plots.spec wait for the debounced filter in the URL before clicking a link (same race as main PR #54).
  - No re-runs of infrastructure failures; privacy.spec:37 was flaky once (passed on retry), unchanged.
- Review run (Phase 6): not started — ports 8080/5173 held by the owner's own app.
- Left for the owner: review and merge the PR; `make guide-shots` + `make guide` on this branch (screenshots and PDFs); check the 3 [?] criteria in a browser; `/awos:architecture` follow-up; optional hardening of the recalculation lock order.
- Owner decision (2026-10-28): stop the owner's own `make up` and run again to verify. Stopped it (TERM to the recipe shell, then the two Java processes); ports freed.
- Phase 6: `make up` started detached from the spec branch (PID 4980; backend 5293, web 5155); Flyway migrated the dev DB to V27 and reapplied the demo data. Ready at :8080 and :5173.
- Browser checks of the 3 [?] criteria (claude-in-chrome, demo users): receipt screen «Receipt № 10» after a 100,00 cash payment on plot 9; auto-allocation switch stays off after reload (DB false; switched back on); electrician has no «Payments» item and gets the no-access page on /payments. Screenshots in docs/screenshots/015-payments-and-allocation-*.png. All 50 criteria [x]; status Completed.
- Note: the review run left a real 100,00 cash payment (receipt № 10) on plot 9 in the local dev DB.
- Owner request: make guide-shots (108 shots) and make guide (4 PDFs) on the review run; capture-screens table step now scrolls the table into view (payment-form showed only the top of the form). Committed 152f27a and pushed.

## Owner review of PR #56 (NOTES.local.md, 2026-10-28)

- Status: running again (owner review round). Items: 1 reallocation dialog validation and stuck preview (also payment form and split); 2 payments list filter layout (plot popover, billing month select), also reports and plot account filters; 3 owner decision: with auto-allocation on, an unallocated remainder is placed at once on open debts (changes spec 2.4, 8.2, 8.4, money-rules 7, guides); 4 payment card: «Зараз зараховано» then collapsible «Історія розподілу».
- Decision (conflict with CLAUDE.md "only the server calculates money"): item 1's "validate on the client" is met server-side — an invalid preview returns 200 with per-line problems and the server's allocated total; the table stays, the button is disabled, real errors show above the table. Owner can override by changing the CLAUDE.md rule.
- Owner review fixes committed: fa5356b (docs, item 3), 83b3bc4 (item 2), e3b668e (items 1, 3, 4). Fix review PASS. Flaky-order fix of LedgerAccountTests in progress; full make check running.
- Full make check after the owner review fixes: backend 3301 tests, 0 failures, JaCoCo floor met; web 825, shared 130; contract, format, lint green. LedgerAccountTests order fix (explicit created_at) added.
- CI run 40737374368 on cbc2a04: Backend, Clients, SAST, Security green; End-to-end 143 passed, 4 failed — meters-acceptance:290, recalculation:279, tariffs-acceptance:275 broken by the shared PlotPicker combobox change (item 2; specs clicked suggestion buttons / duplicate label), privacy.spec:37 audit log stuck on Loading (flaky earlier, failed twice now). CI fix attempt 1 delegated (react-frontend). The new reallocation e2e step passed.
- CI fix attempt 1 for the owner round (6db0891): fix review PASS. Review run restarted on 6db0891 (make up PID 58666). Pushing.
- CI run 40745784297 on ae8a02c: all five checks green (full e2e).
- Finish 4: make guide-shots (108) and make guide (4 PDFs) on the review run at 6db0891; 24 screenshots and the PDFs changed (payment card, list filters, form, account). Note: the local dev DB contains the review test payments № 10–13, visible in the payments list and card shots.
