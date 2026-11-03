# Pipeline log — 018 cash book, expenses and reconciliation

- Status: running
- Phase: 2 — committing specs
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
