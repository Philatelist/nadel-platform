# Pipeline log — 018 cash book, expenses and reconciliation

- Status: running
- Phase: 3 — Slice 1 backend task
- Branch: 018-cash-book-expenses-reconciliation (planned) · Base: main @ c7419a6
- Requirement: docs/features/018-cash-book-expenses-reconciliation.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started)

## Project facts

- Base branch: main (origin/HEAD not set; local main == origin/main @ c7419a6, no local-only commits). Working tree clean.
- Pipeline tooling present on main (skill, review loop, slice-reviewer, AWOS wrappers + core files).
- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`.
- One test: `cd backend && ./mvnw test -Dtest='<Class or package.**>'`; web `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check` (contract, format, lint, backend verify with Testcontainers + JaCoCo floor, client tests); then `make e2e`.
- Specialists: spring-backend, postgres-database, react-frontend, expo-mobile, devops-infra; testing agent: testing-expert; reviewer: slice-reviewer.
- UI: yes (web); browser tool: claude-in-chrome available.
- Safe local run mode: `make up` (local docker Postgres+Keycloak+Mailpit+fake Telegram, backend dev profile, web :5173); no external services by default.
- Git rules: Conventional Commits for squash titles, slice commits `018: Slice S — title` (as in 017), feature branch only, no AI attribution in commits/PRs (CLAUDE.md — overrides harness attribution reminder), never push main. Commits with `git -c gc.auto=0 commit` (017 lesson).
- Publishing: gh logged in (Philatelist), origin github.com/Philatelist/nadel-platform; CI workflow ci.yml (Backend, Clients, Security, SAST, End-to-end smoke).
- Settings: autonomy minimal, publish pr+checks, reviewRun on, notify true.
- Next spec number: 018. Latest migration V31 → next V32.
- Ports 8080/5173 free; the 017 review run is no longer alive.

## Phase 1

- Started 2026-11-03: Agent A (git history) and Agent B (code/spec search) in parallel.
- Agent A (git history): no 018 work on any branch, stash, worktree or unreachable commit; only the requirement doc (5287657 on main). 015 FS lists the cash book as out of scope (feature 018). Verdict: new.
- Agent B (code/spec): nothing of 018 implemented. Builds on ledger (PaymentService.book :240, PaymentCorrections.reverse :123; PaymentRecorded/PaymentReversed events ids only, consumed only async; BillingCalendar/BillingMonths internal), reports (ReportDefinition, ReportCatalog, ReportsController REPORTS_VIEW on all endpoints, TemplateRowSet), documents (DocumentsFacade, PDF layer internal; CertificateLayout/ReceiptLayout analogues; debt certificate = debtsAsOf), privacy (PersonalDataCipher, `<col>_enc` bytea, audit sealing with FieldChange.newValueRef; reveal only with MEMBERS_VIEW), settings (Requisites), identity Permission (28 → 31; counted in LedgerMigrationTests, RolesTests, SeedBaseRolesTests, DemoAccessChecks; seed_base_roles latest in V29), audit (Action enums + openapi AuditAction/AuditEntityType + i18n + auditValue + guard tests). Missing: public booking-month facade, per-plot ledger lines for the act, payment-date-based allocations by type, reversal date/month in payment rows, generic PDF API, image libs (no HEIC in JDK/TwelveMonkeys), multipart limits 10MB/11MB too small for 5×5MB. Demo months are relative (closed month + open month), demo ledger block wipes payment/allocation. Latest migration V31 → V32; next ADR 0019; coverage floor 0.977. Board guide planned row «Банк і касова книга» has a duplicate chapter number 26 (slip). Owner's 018 decisions in DECISIONS.local.md (28.10.2026) match the requirement.

## Requirements brief

**Requirement:** docs/features/018-cash-book-expenses-reconciliation.md. The cooperative's own money: cash (held by the treasurer) and the bank account, with opening balances, an append-only movement journal (member payments automatically, handovers, expenses with categories and receipt attachments, other income, booked differences, reversals), cash counts with a PDF act, a monthly manual bank reconciliation («Звірено»), a reconciliation act PDF per plot for the board and the member, and four reports (cash book, bank movements, income and expense for the general meeting, expenses). Users: treasurer (daily entry, counts, reconciliation), chairman (opening balances, differences, reversals, categories), board/audit committee (reports), members (their reconciliation act).

**Prior work:** none on any branch. Verdict: **new**; extends 015 (payments), 013 (reports), 016 (documents engine), 014 (privacy), 011 (billing months).

**Scope:** sections 2–10 and 12 of the requirement. Out of scope: section 11 (statement import, several accounts or cash holders, budgets, accounting export, payroll, supplier payments) and the mobile app.

**Acceptance criteria:** section 12 of the requirement, with example 12 restated (below).

**Approach:** new Spring Modulith module `treasury` (accounts, movements, expenses + attachments, categories, handovers, other income, counts, reconciliations); V32 migration (8–9 RLS tables, append-only triggers/grants on `account_movement`, 3 permissions, seed_base_roles, categories seed, backfill of existing payment movements); new ledger public pieces (booking month facade, in-transaction payment event, per-plot act lines, period payments by type); documents layouts for the cash count act and the reconciliation act; 4 report definitions; web screens «Каса і банк», «Витрати», «Перерахунок каси», «Звірка з банком», settings «Статті витрат», plot card and account «Акт звірки»; ADR 0019; money-rules section 14 «Каса і банк», glossary, READMEs, guides uk/en, demo data.

**Verification:** backend module tests (Testcontainers) incl. the 12 worked examples as unit tests, concurrency, append-only, isolation, permission matrix; clients typecheck/lint/test; 4 new Playwright specs (one `@smoke`); full `make check` + `make e2e` in Phase 4; UI checks in Chrome via `make up`.

**Risks:** large feature (backend + ledger/documents/reports changes + web + guides + demo); the first synchronous in-transaction cross-module listener (ADR); image handling without a library; coverage floor 0.977; demo months are relative.

## Phase 1 decisions

- Blocking questions: none (no prior work, base OK, tree clean, requirement concrete). No AskUserQuestion asked.
- AUTO (questions: blocking): **Example 12 restated exactly.** Income from members = the payments received in the period (by `received_on`) split by the charge types of their allocations as of the period end, their unallocated remainder as «Аванси», and reversals made in the period of payments received before it as a negative line; plus other income by category. Expenses by category. Booked count and bank differences are their own line. Result = income − expenses + differences = Δ(cash + bank) over the period. The member lines by type equal the payments report's allocations for the same payments. (The requirement's "minus the change of unallocated prepayment" holds only for an allocations-only income and ignores booked differences.)
- AUTO: payment movements are created in the payment's transaction by a **synchronous in-transaction listener** in `treasury` on a new ledger event carrying the movement facts (payment id, source, amount, date, booking month, reversal-of); ledger never depends on treasury; ADR 0019 records it as the one exception to "side effects through the outbox" (the movement is part of the same money fact). Not an SPI (ADR 0011: writing SPIs must be events).
- AUTO: a payment reversal's movement is dated on the reversal day (example 10), booked in the earliest open month from that day; the ledger itself keeps its own date rule.
- AUTO: the reconciliation act follows the ledger's dates (charges by `accrued_on`, payments and their reversals as `debtsAsOf` counts them), so both ends equal the plot balances report; the treasurer's name signs «Від кооперативу» (empty line if there is no treasurer).
- AUTO: opening balance is the balance at the start of its date, entered once by the chairman and never changed (mistakes are fixed by a count or a bank difference); movements dated before it are kept but not counted, and a movement other than a member payment dated before the opening date or before it exists is refused (409 `OPENING_BALANCE_MISSING`).
- AUTO: CASH_INSUFFICIENT rule = the running cash balance on the movement date and every later day stays ≥ 0.00 after the change; it applies to cash expenses, handovers, reversals of cash income and booked shortages; only member-payment reversals are exempt (warning). Cash account row locked FOR UPDATE, taken after the billing month lock.
- AUTO: HEIC → JPEG conversion happens in the browser before upload (no native HEIC library on the server; iOS Safari also hands over JPEG for inputs that do not accept HEIC). The server accepts only JPEG, PNG and PDF by magic bytes and strips EXIF/XMP/GPS from JPEG and PNG metadata chunks. Library choice in tech (noted in the PR).
- AUTO: multipart limits raised so 5 files of 5 MB fit; the 5 MB per-file cap is checked by the application (413 `FILE_TOO_LARGE` / 400 codes as existing).
- AUTO: other income uses its own category list (same table, kind INCOME), seeded «Відсотки банку», «Гранти та пожертви», «Продаж матеріалів», «Інше»; editable by the chairman like expense categories.
- AUTO: the four treasury reports require `CASHBOOK_VIEW` (not `REPORTS_VIEW`); the reports endpoints accept either permission and the catalog filters per report, so an audit committee role with only `CASHBOOK_VIEW` works.
- AUTO: sealed counterparty/description values in the audit follow the generic 014 reveal rule (`MEMBERS_VIEW`); screens and reports show them with `CASHBOOK_VIEW`.
- AUTO: bank reconciliation months are calendar months by movement date; any movement dated in a reconciled month (payments included) reopens it with the reason; BANK_MONTH_REOPENED is written by the system.
- AUTO: cash count stores the book balance at the moment it is saved (movements dated up to the count date that exist then); later back-dated movements do not change a saved count.
- AUTO: demo data uses the relative months of the demo ledger (the closed month = «September», the open month = «October»); encrypted demo fields are written by a Java demo runner.
- AUTO: mobile app unchanged; web only.

## Phase 2

- Spec folder `context/spec/018-cash-book-expenses-reconciliation`, branch `018-cash-book-expenses-reconciliation` cut from main @ c7419a6.
- Baseline `make check` on c7419a6 running in a detached worktree (scratchpad/baseline-018).
- functional-spec.md written (Creation Mode, no markers; Assumptions & Decisions section, 14 items).
- AUTO (spec): counterparty and supplier document optional on an expense; category required; categories unique per list.
- Baseline (make check on c7419a6): fully green (backend 3952, web 908); recorded in review-log.md; worktree removed.
- technical-considerations.md written from spring-backend, postgres-database and react-frontend design input (Explore step reused the Phase 1 Agent B report). Skill update check: all up to date.
- AUTO (tech): PDFs rendered in documents (CashCountActLayout, ReconciliationActLayout); the two PDF endpoints under tag documents (one generated interface per tag; avoids treasury → documents cycle).
- AUTO (tech): new ledger event PaymentMoneyMoved + plain synchronous @EventListener in treasury (MANDATORY tx); new ledger API BookingMonths, plotStatement, firstDebtDate, paymentSplits (recorded-by cut-off), paymentRefs.
- AUTO (tech): backfill in V32 SQL; reversal rows dated on their creation day (Kyiv), month greatest(row month, day month).
- AUTO (tech): seeded categories by system_code, names from i18n catalogs until renamed (CLAUDE.md i18n rule).
- AUTO (tech): categories managed with CASHBOOK_APPROVE (FS 2.13), not SETTINGS_MANAGE as the web agent proposed.
- AUTO (tech): HEIC via pinned, lazy-loaded heic-to (web agent) with canvas fallback (backend agent proposed canvas only); JPEG/PNG metadata stripped by JDK segment/chunk parsing, no re-encode, no backend library.
- AUTO (tech): no DB running-balance trigger; CashGuard under the cash account FOR UPDATE lock.
- AUTO (tech): multipart max-request-size 27MB; 5 MB per attachment in the service.
- AUTO (tech): reports endpoints accept REPORTS_VIEW or CASHBOOK_VIEW; treasury reports require CASHBOOK_VIEW; template filling keeps REPORTS_VIEW.
- AUTO (tech): difference reversals get audit actions COUNT_DIFFERENCE_REVERSED / BANK_DIFFERENCE_REVERSED (FS 2.14 amended); bank difference dated the month's last day (or today); act closing sentence by net balance.
- tasks.md written: 8 implementation slices + Feature Testing & Regression (testing-expert). Staffing gap: guides task → general-purpose (accepted per pipeline rule; for the final report). `<!-- not-user-reviewed -->` marker removed (Phase 1 stands in for the plan review).
- The four Playwright specs (FS 2.17 / requirement §12: expense+handover @smoke, cash count, bank reconciliation, member act) are written in Slice 9 by testing-expert.

## Phase 3

- S1 migration task done (postgres-database): V32__treasury.sql (9 tables, guards, column grants, seed_treasury, seed_base_roles redefined, backfill between BACKFILL markers), TreasuryTestSupport, TreasuryTenantIsolationTests, TreasuryGuardsTests, TreasuryBackfillTests, SeedBaseRolesTests updated. RED on guards and backfill. Orchestrator re-run: 35 tests green. booking_month = date (1st of month); movement amounts signed; reversal = same kind, negated, reverses_movement_id + reason. Permission enum left to the backend task (exhaustive switch in PermissionCatalog). Demo wipe issue noted for Slice 8.
- S1 backend task done (spring-backend): ledger BookingMonths, PaymentMoneyMoved (published in book and reverse), PaymentRef/paymentRefs; treasury module (MoneyAccounts, MovementWriter, AccountBalances, PaymentMovementListener, TreasuryAuditPublisher, TreasuryController); 31 permissions; contract + make gen; README/ADR 0009/glossary. Verify 153 green (orchestrator re-run 153 green); clients green (web 908). RED on 2. Deviations accepted: reversal reason read via paymentRefs (event stays text-free); bank reference returned as documentNumber; plotId only (no plot number); createdByName via audit.ActorNames (existing board pattern); extra fields asOf, accountId/kind/from/to/negative, reversesMovementId; default period current month, page size 100 (max 200); opening validation codes amount INVALID / DATE_IN_FUTURE; DB trigger second write → 500 only if service check bypassed. Unplanned but necessary: test clean-ups across ~20 test supports (seeded money_account/expense_category FK on tenant delete, movements before payments, TRUNCATE probe, Treasurer permission lists in 3 acceptance tests). Shared code touched → whole backend suite in Phase 4.
- Known until Slice 8: reversing a demo SQL payment in the dev DB returns 500 (no original movement); e2e specs reverse only their own payments. Added to the Slice 8 demo task.
- S1 web task done (react-frontend): CashbookPage, MoneyAccountCard, MovementsTable, nav, route, auditValue MONEY_ACCOUNT, i18n cashbook.*, api-client index export (hand-maintained file, as in 017). Clients green (orchestrator re-run: web 916). S1 Verify (orchestrator): backend 153 green, clients green.
- Slice 1 commit 64b6fcf (backend 153 + clients green); review round 1 started (2ad657e..64b6fcf).
- Slice 1 review round 1: PASS (2 minor notes in review-log.md).
- Owner (2026-11-03): asked why Slice 1 took long; answered (largest slice: all-feature schema, unplanned clean-up of ~20 test supports after V32 seeding, sequential tasks). Owner chose to continue with the parallel approach: per slice the web task starts as soon as the backend contract and generated client exist; one orchestrator check per slice instead of per task.
- S2: web task started in parallel with the backend task (contract and client for /expenses and /expense-categories already generated); backend agent told to stay out of web/** and keep i18n edits minimal.
- S2 backend task done (spring-backend): CashGuard, Categories, Expenses (+queries), AttachmentUploads, FileSniffer, Jpeg/PngMetadataStripper, TreasuryCipher, two attachment entities, problem extension fields balance/date, max-request-size 27MB and max-swallow-size 30MB; contract + gen. Reported 108 tests green, RED on 3. Deviations accepted: ExpensePartConverter (orval sends the JSON part without content type); stale reorder → 400 ids INVALID; archived:false restores (audited ARCHIVED true→false); CATEGORY_NAME_TAKEN by lower(name) per kind incl. archived; files[i] 0-based; list shows originals only; audit EXPENSE label = date; category audit label `base:<CODE>` for seeded (web must render); CashBookExamplesTests rows 1–5; extra AttachmentUploadsTests, in-memory TestImages.
- S2 web task done (react-frontend): expenses list/form/card, AttachmentPicker (camera), AttachmentList, ReasonDialog, CategoryName, ExpenseCategoriesPanel (rename, reorder, archive, restore, add), auditValue base:<CODE>, i18n expenses.*, expenseCategories.*, problem codes. Clients green (web 931).
- AUTO (licence, conservative choice): heic-to 1.6.7 is LGPL-3.0, not MIT as TC assumed; heic2any bundles libheif (LGPL) too. No LGPL dependency added: heic-to removed; HEIC handled only by the browser's own decoder (createImageBitmap → canvas → JPEG; Safari/iOS), otherwise the type error. TC 2.11 to be amended. Owner decision for the PR: accept an LGPL library if HEIC from Chrome/Android desktop files matters.
- Known minor: server field errors AMOUNT_NOT_POSITIVE / DATE_IN_FUTURE show the existing validation catalog wording, not the FS 2.3 wording (client-side checks use FS wording); the HEIC failure path has no automated test.
- S2 Verify (orchestrator): backend 108 green; clients green (web 931); pnpm audit OK; package.json/lockfile unchanged vs main. TC 2.11 and tasks amended for the HEIC decision.
- Slice 2 commit bcae29e (backend 108 + clients green); review round 1 started (64b6fcf..bcae29e).
- Slice 2 review round 1: PASS (4 minor notes in review-log.md).
- S3 web task done (react-frontend): HandoverDialog, OtherIncomeDialog, journal «Сторнувати» for HANDOVER/OTHER_INCOME (APPROVE), expense rows link to the card; i18n. Reported web 937 green, RED on the gate. Both handover lines offer reversal (same source id).
- S3 backend task done (spring-backend): Handovers, OtherIncomes (+queries), EntryChecks (shared rules, Expenses refactored onto it, ExpensesTests unchanged green), journal categoryId and handover documentNumber; audit HANDOVER_*/OTHER_INCOME_*; contract + gen. RED on 2. Deviations accepted: unknown/wrong-kind category → 400 categoryId INVALID (as expenses); handover needs both opening balances; handover reversal never refused.
- S3 Verify (orchestrator): backend 126 green; clients green (web 937).
- Slice 3 commit cd1e699 (backend 126 + clients green); review round 1 started (bcae29e..cd1e699).
- Slice 3 review round 1: PASS (3 minor notes in review-log.md).
- S4 web task done (react-frontend): CashCountNewPage/CashCountPage (debounced server preview, save, act PDF, «Оформити різницю» for APPROVE), kyivDateTime helpers, cash card last count + unresolved difference, COUNT_DIFFERENCE reversal in the journal; i18n incl. count audit labels (needed for typecheck). Reported web 941 green, RED on the gate. Count list skipped.
- S4 backend task done (spring-backend): CashCounts (preview, save with recomputed book balance, list, card, book-difference), CashCountQueries/Figures, JournalLines (shared mapping), movement reverse endpoint (COUNT_DIFFERENCE only for now; others 409 MOVEMENT_NOT_REVERSIBLE), CashCountActs facade + documents CashCountActLayout/CashCountActDocuments + GET /cash-counts/{id}/act.pdf; lastCount/unresolvedDifference on the cash account; audit CASH_COUNTED, COUNT_DIFFERENCE_BOOKED/REVERSED. Reported 491 green, RED on 2, ModularityTests green (documents → treasury, no cycle).
- New migration V33__cash_count_booking_reason.sql (nullable cash_count.booking_reason with checks + column grant): V32 only allows a reason on reversal movements, so the chairman's booking reason needed a column. Accepted (TC 2.4 rule: later slices add V33+ when needed).
- Decisions accepted: count explanation stays plain (V32 column; not personal data, like reversal reasons); a reversed booking leaves the count BOOKED (write-once) and the card/act show the reversal; movementsSince defined as lines since the previous count (documented); the cash card shows the newest count (unresolvedDifference only while it is UNRESOLVED); any count's difference can be booked; countedAt may be ≤5 min ahead of the server clock; act signers = first active TREASURER and CHAIRMAN holders.
- MOVEMENT_NOT_REVERSIBLE had no catalog text → sent to the web agent as a minimal follow-up.
- S4 Verify (orchestrator): backend 491 green; clients green (web 941).
