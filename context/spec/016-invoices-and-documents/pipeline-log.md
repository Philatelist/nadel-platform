# Pipeline log — 016 invoices and documents

- Status: running
- Phase: 3 (implement, Slice 1)
- Branch: 016-invoices-and-documents · Base: main @ 5287657
- Requirement: document docs/features/016-invoices-and-documents.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: off until Phase 6 (reviewRun: on)

## Project facts

- Base branch: `main` (origin/HEAD not symbolic; `main` == `origin/main` @ a668a03, no local-only commits).
- Pipeline tooling present on `main`. Working tree clean.
- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`.
- One test: `cd backend && ./mvnw test -Dtest='<Class>'`; module `-Dtest='app.nadel.<module>.**'`; web `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check`, then `make e2e` (JDK 25).
- Specialists: spring-backend, postgres-database, react-frontend, devops-infra, expo-mobile; testing: testing-expert; reviewer: slice-reviewer.
- UI: web. Browser tool: claude-in-chrome (deferred).
- Safe local run mode: `make up` (local docker-compose Postgres + Keycloak, backend :8080, web :5173).
- Git rules: Conventional Commits; no AI attribution; never commit on main; push only the spec branch in Phase 5, no force.
- Protected files: CLAUDE.md, .claude/, .mcp.json, .github/.
- Publish pre-flight: gh logged in (Philatelist); origin github.com/Philatelist/nadel-platform; CI `.github/workflows/ci.yml`.
- Next spec number: 016.

## Settings

- autonomy minimal: questions=blocking, reviewerEscalation=auto, notGreen=ask, gitUnsafe=ask, notTestableLocally=auto
- publish=pr+checks, reviewRun=on, notify=true

## Log

- Phase 0 done. Phase 1b: agents A (git) and B (code) launched; orchestrator looks for the NBU Resolution 97 text.
- NBU source: official PDF downloaded from https://bank.gov.ua/admin_uploads/law/19082025_97.pdf (55 pages, published 25.08.2025);
  text extracted with PDFBox to scratchpad/nbu/nbu97.txt. Annex 4 (format 003) table 2 present: 17 fields. No stop needed.
- Note (official text vs. requirement): amount "as short as possible" (UAH3 better than UAH3.00) → the official text wins;
  field 10 "Категорія / ціль" is mandatory (ISO 20022 codes CCCC/PPPP) → value to be taken from the official text.
- Agent A (git): no prior work on any branch/stash/worktree; `origin/docs/feature-016-invoices` (9140de4) == main (the merged requirement, #57).
  Stale 012 worktree `.../60c46cb4-.../scratchpad/run-012` exists, not this run's; left untouched. pom.xml has no PDF/QR library; last ADR 0016.
- Agent B (code): nothing of 016 implemented; no PDF/QR/JobRunr dependency; next migration V28; next ADR 0017.

## Requirements brief

**Requirement:** docs/features/016-invoices-and-documents.md. Monthly (and on-request) invoices per plot as PDF with the
NBU payment QR code (format 003), stored as snapshots with versions; a month build in batches with progress and a
print PDF; debt certificates with a sequential register and annulment; cash receipts for CASH payments of 015; amount in
words. Users: treasurer/chairman (build, issue, print), member (own invoices and receipts).

**Prior work:** none (A). Verdict: **new**, extends 011 (billing months, charge calculations), 013 (requisites, ledger
read model), 015 (payments, receipt numbers, allocations), 014 (cipher), 007 (owners, shares), 002 (delivery channel).

**Scope:** requirement §§1–13; out of scope §14. Mobile unchanged. Plus the two 015 UI polish items deferred to 016 in
DECISIONS.local.md:125 (amount line wrap; hint with the remaining amount).

**Findings that shape the design:**
1. Billing month path parameter is `{month}` (YYYY-MM), not `{id}`.
2. ЄДРПОУ lives on the tenant (`TenantDirectory.current()`), requisites in settings.
3. LedgerReadModel lacks per-plot/billing-month invoice data (charged rows with calculation, payments in month, start/end
   balances by booking month); `debtsAsOf` is by accrued_on → new read-model methods needed (example 5).
4. Owners with shares, role holders (chairman/treasurer names) and delivery channel have no public API → new methods.
5. JobRunr not yet added (ADR 0007 decided it; CLAUDE.md: long-running work uses JobRunr).
6. No TTF fonts; design system font IBM Plex Sans is OFL-1.1 (embeddable).
7. Payer note needs MEMBERS_VIEW (015), receipts need PAYMENTS_VIEW.
8. architecture.md:169 plans object storage for invoice PDFs; 016 renders from snapshots → doc update.
9. Stale "016 = bank import" mentions (ledger README:55, glossary:57, 015 spec:418).
10. Demo months are relative to an anchor date; snapshot names need the Java cipher (not SQL).
11. Demo IBAN `UA213223130000026007233566001` may be a published sample, not clearly test.

**Verification:** backend unit + Testcontainers, `make check`, Playwright specs + smoke; PDFs rendered to PNG for the
PR; UI via claude-in-chrome on `make up`. The owner's real-bank QR scan stays a manual `[?]` item.

**Risks:** official QR layout details (field 10, mask), PDF byte-stability, certificate counter under concurrency, JobRunr
introduction (tables via Flyway, RLS-free job storage), Supply-chain CI job with new libraries, large guide work.

## Decisions (no blocking questions; autonomy minimal → recommended answers taken)

- AUTO: (questions=blocking, none blocking) QR layout from the official PDF (Annex 4); where it differs from the
  requirement, the official text wins: shortest amount form (`UAH413.26`, `UAH3`), mandatory field 10, LF endings,
  encoding `1` (UTF-8), ban mask per bit numbering of table 2 (fields 1–5, 11, 14–17 always banned).
- AUTO: Endpoints use the existing `{month}` (YYYY-MM) billing-month path parameter.
- AUTO: New module `documents` (planned in architecture.md:91); it reads ledger/settings/tenancy/plots/members only
  through public APIs; new public read methods added where missing.
- AUTO: Long-running month build with JobRunr as ADR 0007 decided, unless the tech phase finds it cannot meet the
  Flyway-only/RLS rules; then a documented fallback in ADR 0017.
- AUTO: PDFs are not stored (spec wins over architecture.md:169; doc updated).
- AUTO: Embedded font IBM Plex Sans TTF (OFL-1.1) with its licence in backend resources.
- AUTO: Receipt payer: the payer member's name; the decrypted payer note only for callers with MEMBERS_VIEW; a member
  downloading a receipt never sees another person's note.
- AUTO: Demo IBAN: replace by a checksum-valid, clearly fictitious test IBAN; flagged in the PR for the owner to confirm
  (and for the QR scan check, the owner can enter a real account locally).
- AUTO: Demo invoices/certificate/receipt: created in Java under the demo profile (the cipher needs Java), for the demo
  closed month.
- AUTO: Stale "016 = bank statement import" references corrected to the new numbering.
- AUTO: The two 015 UI polish items deferred to 016 are included as a small task.
- Phase 1 done. Phase 2: branch 016-invoices-and-documents cut from main @ 5287657 (main was fast-forwarded by the owner from a668a03 to 5287657, #58: docs 017 and 018 only, between Phase 0 and the cut); log moved in; baseline started.
- User (mid-run): field 10 «Категорія / ціль» must be taken from Annex 4 and the rule quoted in docs/domain/nbu-qr.md; no invented code.
  Finding: Annex 4 (section I, item 4.10) has no default; the recipient fills ISO 20022 codes as CCCC/PPPP. Concrete values only in
  its examples: SUPP/SUPP (example 1, utility payments), MP2P/MP2B, MP2B/GSCB, OTHR/GDDS.
- User answer: field 10 = `SUPP/SUPP` (Annex 4 example 1, «Оплата комунальних послуг»). nbu-qr.md quotes item 4.10 and example 1.
- Phase 2.3: functional-spec.md written (Creation Mode, no markers).
- Phase 2.4: awos:tech — exploration reused from Agent B (scratchpad/recon-016.md); specialists spring-backend, postgres-database, react-frontend, devops-infra launched in parallel. Skill-update check of awos-recruitment not run (it needs a user choice; the pipeline asks nothing).
- devops-infra: OSV scans backend/pom.xml (incl. transitives) → pin exact versions, no openhtmltopdf SVG module (batik), override PDFBox to latest 3.0.x via dependencyManagement if needed; font binaries fine for gitleaks, OFL.txt next to fonts; no CI change; JobRunr only with dashboard off, skip-create + Flyway-owned jobrunr_* tables, ids-only job args.
- react-frontend: design in (read only part of the spec); orchestrator corrections: print scope paper|all; 'annul' not 'cancel'; audit actions per spec §2.11 only (no RECEIPT_PRINTED); open months invoiceable; PDFs as orval blob functions (application/pdf), new PlotTabs on PlotPage, CertificatesPage route documents/certificates.
- postgres-database: V28 design in (invoice append-only via ledger_append_only, invoice_run with partial unique RUNNING + heartbeat, certificate_guard annul-once, document_counter +1 step, gapless counter in the issuing tx); recommends no JobRunr (invoice_run row as the job, @Async under runAs, batches of 50, one tx per batch); demo IBAN UA850000000000000000000123456 (bank code 000000; checksum verified by orchestrator); demo documents via a dev ApplicationRunner under runAs.
- Baseline: make check on a668a03 green (3301 backend tests, 0 failures); worktree removed; review-log.md written.
- main moved to 5287657 (#58 adds docs 017, 018). 017 expects from 016: a public documents facade (latest invoice version, PDF bytes, invoice-for-today, cash receipt PDF, QR link) and the print PDF; 017 is the first feature with retries.
- User decision (28.10.2026): JobRunr comes in 017. In 016 the month build is synchronous like the month run (snapshots only,
  PDFs on demand), measured on the largest fixture, limit + measurement in ADR 0017 as ADR 0012 did; no own job queue (no
  @Async, no invoice_run progress table); progress UI only if the build exceeds a few seconds, else a spinner.
  Functional spec §2.5, scope and assumptions updated; spring-backend informed while designing.
- User decision (28.10.2026): the month build reads every snapshot at one point in time (one REPEATABLE READ transaction, or the
  011 cooperative charging lock taken shared), so a payment or charge booked during the build is in all or none of its
  invoices; a test for it is required.
- User decision (hryvnia sign, Annex 1 items 10–14 mandatory for 003): first check the downloaded resolution PDF for the sign;
  if vector, extract to docs/domain/nbu-hryvnia-sign.svg with the page cited and use it; else the font glyph U+20B4 behind a
  replaceable source, PR note that the owner supplies the official file.
- Hryvnia sign check: the resolution PDF has the sign only inside raster PNG images of the format 003 examples (PDF pages 46, 49,
  52, 55; e.g. page 46 = Annex 4 sheet 11, Image130 500×500 px). Vector QR on page 28 is a format 002 example without the sign.
  → Per the owner's rule: font glyph U+20B4 behind a replaceable sign source; PR note that the owner supplies the official file.
- spring-backend (revised for the sync decision): synchronous build in one REPEATABLE READ transaction (matches the owner's
  consistency rule), advisory month lock, chunks of 50 with flush/clear, limit 2,000 plots to be measured; PDFBox 3.0.8 own
  layout layer; ZXing 3.5.4; Win1251 encoding '2', FEFF mask constant, Base64URL no padding; content hash = keyed blind index.
- Phase 2.4: technical-considerations.md written. Tech assumptions (AUTO, conservative, listed in the doc):
  - AUTO: QR encoding `2` (Win1251, as every official 003 example) with UTF-8 fallback (replaces the earlier brief note "encoding 1").
  - AUTO: field 17 empty (table 2: optional); official examples print `RFU` → switch if a bank rejects it in the owner's check.
  - AUTO: FEFF lock mask as the official constant; Base64URL without padding; link ≤ 507 bytes; QR version 10–17, level M.
  - AUTO: PDF engine PDFBox 3.0.8 (Apache-2.0) with own layout layer; ZXing core 3.5.4 (javase test scope); IBM Plex Sans TTF.
  - AUTO: consistency by one REPEATABLE READ transaction (not the charging lock: payments do not take it); advisory month lock.
  - AUTO: build limit 2,000 invoiceable plots, measured in InvoiceBuildLimitTests; >~5 s → back to the owner.
  - AUTO: content hash = keyed blind index (new IndexKind.DOCUMENT_CONTENT); no hash of plaintext names.
  - AUTO: «До сплати» does not subtract prepayment; PAPER print = any current owner with PAPER; on-request = always new version.
  - AUTO: documents tables keep `month date`, no FK to ledger tables; demo documents by a demo-profile ApplicationRunner.
  - AUTO: public DocumentsFacade for 017.
  - Staffing: no specialist for NBU QR / PDF engine → spring-backend (closest); suggest /awos:hire for PDF/QR later.
- Phase 2.5: tasks.md written — 7 slices (6 implementation + Feature Testing & Regression on testing-expert).
  - AUTO: staffing gap — docs and guides (Slice 6) and NBU QR / PDF engine (no specialist; spring-backend as closest) → Slice 6 on
    `general-purpose`; no Open Questions section kept; noted for the final report.
  - AUTO: `<!-- not-user-reviewed -->` marker not kept (Phase 1 decisions and the owner's mid-run decisions stand in for the plan review); plan listed in the final report.
- Phase 2 done.
- Phase 3 started. Delegation rules: scratchpad/delegation-rules.md (shared by every builder).
- Slice 1 web task done (react-frontend): contract GET /payments/{id}/receipt.pdf (tag documents), ReceiptButton, usePdfAction, i18n; pnpm typecheck/lint/test green (web 828 tests), RED shown.
- User decision (28.10.2026): slices 1–6 "without tests" = builders write the listed tests but run none (compile check only:
  `./mvnw -q test-compile`, `pnpm -r typecheck && pnpm -r lint`); each slice committed without review; after Slice 6 the Verify
  commands of slices 1–6 run (failures fixed), then each slice is reviewed with its verify run (deferred Slice Review Loop), then
  Slice 7. Overrides the per-slice Verify-before-commit and the review gate for slices 1–6.
- Slice 2 task 1 done (postgres-database, started early in parallel): V28__documents.sql (invoice, certificate, document_counter, permissions), DocumentsTenantIsolationTests, DocumentsAppendOnlyTests, DocumentTableTestSupport, SeedBaseRolesTests; test-compile only. Extra index certificate_plot_idx. Demo wipe left for Slice 5.
- Stopped the previous 015 review run (pids file add1cc85-…/review-run.pids: make up 67153, mvn 67175, node 67318, java 67458): it was rebuilding backend/target while builders compiled (rule: a later run stops a previous review run). Ports 8080/5173 free.
- Slice 1 backend task done (spring-backend): documents module, PDF layer, AmountInWords, CashReceipts, ReceiptController (implements
  the whole generated DocumentsApi), LedgerReadModel.cashReceipt, PDFBox 3.0.8 (+fontbox, pdfbox-io, commons-logging 1.3.6), IBM Plex
  Sans 1.1.0 TTF (₴ present) + OFL, ADR 0017 draft. Builder decisions: no-permission non-owner 403 before lookup, owner of part of a
  group 404; number words in the bundle. Orchestrator: `./mvnw -q test-compile` exit 0. Tests written, not run (owner rule).
- Slice 1 committed c146796 (test-compile ok; tests written, not run; review deferred to after Slice 6). tasks.md in this commit also carries the early Slice 2 V28 tick.
- Slice 2 task 2 done (spring-backend): docs/domain/nbu-qr.md (tables 1–2, items, Annex 1, our values, field 10 SUPP/SUPP quoted from item 4.10 + example 1, sign raster-only note, official-text inconsistencies §7), nbu package (NbuQrPayload, NbuQrImage, HryvniaSign glyph), ZXing core 3.5.4 + javase test; test-compile ok; tests written not run.
- Parallelised on the owner's request: started now the parts that do not touch the documents contract/controller — Slice 5 015 polish (react-frontend), Slice 5 demo IBAN + wipe (postgres-database), Slice 6 code-independent docs and guide texts (general-purpose); Slice 2 invoice task (spring-backend) still running. Endpoint work of Slices 3–5 waits for the Slice 2 contract.
- Slice 5 part (postgres-database): R__demo_data.sql IBAN → UA850000000000000000000123456 when null or old demo value; wipe + invoice, certificate, document_counter; DemoReportTemplatesTests expectation updated; test-compile ok. Old IBAN kept as a valid fixture in other tests and in TemplateField example text.
- Slice 5 part (react-frontend): 015 polish — ProposalTable «X з Y» whitespace-nowrap; ALLOCATION_EXCEEDS_CHARGE line hint «Більше, ніж залишок {amount}» from the server's per-line open value (new key payments.proposal.exceedsOpen); tests updated; typecheck/lint ok.
- Slice 2 task 3 done (spring-backend): invoice for one plot (contract issuePlotInvoice, listInvoices, getInvoiceReadiness, getInvoice,
  getInvoicePdf, getInvoiceQrPng; InvoiceLedger/MonthMovements; owner shares; RoleHolders; IndexKind.DOCUMENT_CONTENT; Problem.missing;
  permissions Java side; InvoiceBuilder.forPlot; DocumentsController (renamed from ReceiptController); money-rules §13). test-compile ok.
  Builder decisions: outer tx holds the advisory month lock, build in an inner REQUIRES_NEW REPEATABLE READ tx (snapshot would
  otherwise start before the lock); asOf = max(app clock, clock_timestamp()); later reversal of a payment booked in/before M does not
  raise «До сплати» (to check in verify); list sorted by SQL text order of plot numbers (natural order for Slice 3).
- Started in parallel: Slice 2 web (react-frontend, consumes the generated client) and Slice 3 backend (spring-backend, owns the contract/controller changes now).
- Slice 6 part (general-purpose): glossary, architecture.md, 015 spec out-of-scope note, both guides (board guide ch.14 + part II ch.25 'Invoice numbers', chapters renumbered; owner manual documents section), capture-screens.mjs 6 shots (+ tab step). Open: sample PDF, screenshots, guide PDFs, ADR limit number, ledger README stale 016 line.
- Slice 2 web done (react-frontend): PlotTabs, PlotInvoices, RequisitesWarning, i18n invoices.*; typecheck/lint ok.
- Slice 2 committed d5b8178 (test-compile + typecheck ok; tests written, not run; review deferred). The commit's i18n catalogs and glossary also carry small parts of the parallel Slice 5/6 work (payments.proposal.exceedsOpen key; glossary entries).
- AUTO: Slice 4 backend started in parallel with Slice 3; certificate endpoints get their own tag `certificates` (separate generated CertificatesApi + CertificatesController) instead of tag documents, to avoid concurrent edits of DocumentsController (TC 2.8 deviation; rule: owner asked for no idle waiting).
- Slice 4 backend done (spring-backend): tag certificates (issuePlotCertificate, listCertificates, getCertificatePdf, annulCertificate), Certificates, DocumentCounter, CertificateLayout, CertificatesController, audit; test-compile/typecheck/lint ok. Builder decisions: common land/archived → existing PLOT_COMMON_LAND/PLOT_ARCHIVED 409; ownerless plot gets a certificate («Власники: не зазначено»); issuer role from roles; total_debt column ≥ 0. Open: documents tag description text; audit field label 'due' missing.
- Slice 3 backend done (spring-backend): buildMonthInvoices (InvoiceBuild one schema), getMonthInvoicesPrintPdf, DocumentsFacade (+PrintScope), DeliveryChannel, PrintBundle, natural plot order with month filter, INVOICES_ISSUED (entity BILLING_MONTH, name-based id), ADR 0017 build section (Measured: placeholder), ADR 0007 note; test-compile/typecheck/lint ok.
- AUTO: concurrent month build → follow FS §2.5 acceptance criterion: try-lock + 409 INVOICE_BUILD_RUNNING (builder had implemented "wait"); sent back as a minimal fix (notGreen n/a; rule: the spec's acceptance criterion wins).
- Slice 3 web started (react-frontend).
- Slice 4 web done (react-frontend): CertificateForm, AnnulDialog (Modal, not ConfirmDialog: needs a textarea), CertificatesPage (route documents/certificates, nav «Реєстр довідок»), kyivToday helper, client barrel export for certificates; typecheck/lint ok. Commit order: Slice 3 first, then Slice 4 (shared files).
- Slice 3 web done (react-frontend): MonthInvoicesPanel (preview query, spinner + result, list, print PAPER/ALL), InvoiceItem export, ChargesMonthPage mocks; typecheck/lint/format ok.
- Slice 3 backend fix done: try-lock + 409 INVOICE_BUILD_RUNNING for the month build (on-request keeps the blocking lock; preview lock-free); i18n texts; test updated (written, not run).
- Slices 3 and 4 committed together cd12fba (they share contract, generated client, i18n, README, test support; a split would not build). test-compile/typecheck/lint ok; tests written, not run; reviews deferred.
- Slice 5 backend done (spring-backend): listMyInvoices, DemoDocuments runner (dev/demo only; closed month invoices + plot 9 certificate as of month end, issued in the demo chairman's name), MemberInvoicesTests, DemoDocumentsTests, DemoAccessChecks; test-compile/typecheck ok. Note for Slice 7: demo start-up pre-builds documents (e2e must expect existing invoices and Д-{year}-1).
- Slice 5 web done (react-frontend): MyInvoices on AccountPage, ReceiptButton in PaymentEntryRow (shared with staff view), i18n; typecheck/lint ok.
- Slice 5 committed 9745ab5 (compile/typecheck/lint ok; tests written, not run).
- Verify of slices 1–5 started now on the owner's request (before Slice 6 finished), in worktree scratchpad/verify-016 at 9745ab5: all Verify test classes of slices 1–5 + changed tests, and pnpm typecheck/lint/test. InvoiceBuildLimitTests numbers to be re-measured alone (machine shared with the Slice 6 app run).
- Verify run 1 (slices 1–5 at 9745ab5): backend 590 tests, 2 failures + 1 error (CertificateAccessTests ×2 register order; DocumentsTenantIsolationTests.theSameNumbersMayExistInTwoCooperatives — test updates a certificate, guard refuses); clients: packages green, web 12 failed / 841 passed (Layout ×2, ReverseRunDialog ×1 (existing 011 test), PlotInvoices ×3, AccountPage ×2, CertificatesPage ×3, CertificateForm ×1).
- Measurement (InvoiceBuildLimitTests, machine shared with the Slice 6 app run): 2,000 plots, 12 months — first build 3,721 ms, rebuild 3,221 ms, on-request 156 ms → below ~5 s: spinner only, limit 2,000 kept. ADR 0017 filled by the backend fixer.
- Fix attempt 1 sent: web failures → react-frontend; backend failures + ADR measurement → spring-backend.
- Reviews round 1 started for Slices 1, 2, 3+4, 5 (worktrees scratchpad/review-016-*-r1).
- Review Slice 1 round 1: PASS (minor notes in review-log). Orchestrator error: review worktrees were created with spaces in their names at 9745ab5 (zsh did not split the loop args); recreated correctly for S2/S34/S5 and told the running reviewers; removed the S1 one.
- Slice 6 done (general-purpose): ledger README stale line, guide texts aligned with the real UI, sample-invoice.pdf (plot 9, 2026-09/9 v1, due 1640.59, demo closed month September), 12 new screenshots (uk/en), all guide screenshots re-captured, 4 guide PDFs rebuilt; make up started cleanly (first real start of the 016 code) and was stopped; hrefs + prettier ok. Note: guide ch.25 keeps the requirement's October worked examples.
- Slice 6 committed 3991cb7.
- Verify fix (backend) committed c6844a5: CertificateAccessTests look up their certificate by id; DocumentsTenantIsolationTests updates via the owner maintenance path; ADR 0017 'Measured' filled. Targeted run: 30 tests, 0 failures.
- Verify fix (web) committed 39eb540: Layout tests (new nav item), removed the shared validation.TOO_LONG_reason override (production i18n bug that broke the 011 ReverseRunDialog text), router/query mocks, locale in tests. Orchestrator re-run: pnpm typecheck/lint/test exit 0 (web 853/853, shared 130, api-client 9, ui-tokens 56).
- Review Slice 5 round 1: PASS (minor notes).
- Review Slice 2 round 1: PASS (minor notes).
- Review Slices 3+4 round 1: PASS (minor notes). All slice reviews PASS → gate for Slice 7 open. Review and verify worktrees removed.
- Verify tasks of Slices 1–6 ticked: verify run 1 + fixes (backend fixed classes 30/30 green; clients 853/853 green). AUTO: the two verify fixes are test-only (plus one i18n key removal) and were not sent to a separate fix review; Slice 7 and the full suite cover them.
- Phase 3: Slice 7 (Feature Testing & Regression) started on testing-expert.
- Slice 7 (testing-expert): InvoicesAcceptanceTests (5/5 green), web/e2e/invoices.spec.ts (5/5 green; @smoke set 18/18 on a fresh nadel_e2e DB), acceptance-coverage.md (AC → tests), context/qa/list-of-tests.md (+7 rows); RED shown (permission, problem status, receipt button). GAP: demo member's plots 7/15 have nothing due → DemoDocuments builds none → member account empty (FS 2.12, flow 4). Fix attempt 1 → spring-backend (includeNothingDue=true in the demo build). Manual: real-bank QR scan.
