# Pipeline log — feature 013 reports

- Status: running (resumed 2026-10-27 after a user stop)
- Phase: 3, Slice 11 in progress (tasks 1 docs done; task 3 e2e specs written, NOT run; task 2 guides not started)
- Branch: 013-reports · Base: origin/docs/feature-013-reports @ 6c121fb (user's choice; main d8d7092 is its ancestor)
- Requirement: document docs/features/013-reports.md as on origin/docs/feature-013-reports
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (make up) after green checks

## Project facts
- Build/tests: `./mvnw verify` (backend, Testcontainers, JaCoCo floor), single class `cd backend && ./mvnw test -Dtest='app.nadel.<module>.**'`, clients `pnpm -r typecheck && pnpm -r lint && pnpm -r test`, `make check` (all CI), `make e2e`, `pnpm --filter web exec playwright test <spec>`, `make gen` (orval + backend generated interfaces from api/openapi.yaml).
- Agents: spring-backend, postgres-database, react-frontend, testing-expert (testing agent), devops-infra; no mobile work.
- UI: yes (web). Browser tool: claude-in-chrome. Safe local run: `make up` with local docker compose and demo data.
- Git rules: Conventional Commits, no AI mention, no Co-Authored-By (CLAUDE.md wins over the harness attribution reminder), never push main, only spec branch.
- Verification levels: slices run only slice Verify + changed tests; full `make check` + `make e2e` once in Phase 4 (memory: no-full-suite-in-slices).

## Phase 1 findings
- Agent A: no implementation of 013 on any branch. Only origin/docs/feature-013-reports (6c121fb, docs-only, fast-forwards).
- Agent B: no report code, no requisites, no permissions REPORTS_VIEW / REPORT_TEMPLATES_MANAGE. Latest migration V19 → next V20.
  - Reuse: imports/internal ZipGuard (package-private; needs a public facade or move), fastexcel writer, Permission/BaseRoles/PermissionCatalog + seed_base_roles pattern (V19), audit AuditEvent + AuditAction in openapi, settings module (cooperative_settings, V14 pattern), downloadBlob.ts, ImportPage upload pattern, Layout.tsx menu, App.tsx routes.
  - Gaps: no public read facades in ledger, meters (tree/losses/common consumer runs), plots/ownership, members contacts (use MemberDirectory); template filling needs ADR 0015 (POI vs own OOXML filler; only fastexcel exists today).
- Verdict: new feature.

## Scope
In: 8 built-in reports, xlsx output, templates (upload, versions, archive, catalog, generate), tax slot, requisites in settings, permissions, audit, web pages, guides (uk+en), demo template, ADR 0015, money-rules section 12.
Out: official tax form, PDF, payment reports, scheduled reports, operator-shared templates, charts.

## Decisions
- Phase 1 question: spec source → AskUserQuestion answer: use the reconciled spec (user answer).

## Phase 2
- AUTO: Phase 1 answer: reconciled spec used; branch 013-reports cut from 6c121fb.
- Baseline (backend verify at 6c121fb): green, exit 0. Client checks and e2e not run for the baseline.
- functional-spec.md, technical-considerations.md written. Assumptions: preview audited as well as file; IBAN_INVALID top-level 400; new upload into an archived tax slot allowed; slot code TAX; own OOXML filler chosen (POI fallback, ADR 0015); single migration V20.
- tasks.md written (12 slices). No staffing gaps; slice 11 docs/guides run under general-purpose (no docs specialist), noted for the final report. The `not-user-reviewed` marker was never written to the file (nothing to remove).
- Slice 1 · c4057b0 · slice verify green (backend narrow, typecheck, lint, vitest 24, shared 130) · review: PASS round 1
- Slice 2 · 4562a10 · slice verify green (xlsx+imports+Modularity) · review: PASS round 1
- Slice 3: the 'Tests' task was delivered inside the framework task (ChargesByMonthReportTests, ReportsAccessTests, ReportRunnerTests); marked done on that evidence.
- Slice 3 · c69e9b5 · slice verify green (backend reports+ledger+Modularity, typecheck, lint, vitest 17, shared 130) · review: PASS round 1
- Slice 4: the 'Tests' task was delivered inside the definitions task (PlotBalancesReportTests, DebtorsReportTests, LedgerDebtsAsOfTests, PlotReportFacadesTests, MemberContactsTests); marked done on that evidence.
- Slice 4 · ac306f8 · slice verify green (backend reports+plots+members+ledger+Modularity, typecheck, lint, vitest 22, shared 130) · review: PASS round 1
- Slice 5: new column type DECIMAL added to the contract (rates and sotkas have 4 decimals; QUANTITY is 3). Reason column shown on every correction row, not only recalculations. 'Tests' task delivered inside the definitions/facade tasks.
- Slice 5 · e0b1a38 · slice verify green (backend reports+tariffs+ledger+Modularity, typecheck, lint, vitest 22, shared 130) · review: PASS round 1
- Slice 6: contract notes LOSSES_FIXED_PERCENT / LOSSES_PROPORTIONAL replace LOSSES_MODE; new cell reason SEVERAL_RATES; Quantity.toDecimal() added (signed display, same rounding); row cap stays per sheet; members fed omitted (plots fed only). 'Tests' task delivered inside the definition task. Open point for review: TreeFigures losses percent uses its own RoundingMode in meters (ADR 0014 / one-rounding rule).
- Slice 6 · 5943a23 · slice verify green (backend reports+meters+ledger+Modularity, typecheck, lint, vitest 23, shared 130) · review: PASS round 1
- Slice 7 facades: a meter installed/removed INSIDE the range counts as having a reading point and is not listed in missing-readings (spec only excludes installed after / removed before the range) - decision to confirm in review. A well pump's share (COMMON_CONSUMER, split CONNECTED_PLOTS) is attributed to water and also appears in electricityCharged.
- Slice 7: water source option METER relabelled 'Well'; pump share can appear in both electricity-tree and water (README warns); water report refuses when water ledger rows reach cap+1. 'Tests' task delivered inside the definitions task.
- Slice 7 · ac01355 · slice verify green (backend 985, typecheck, lint, vitest 25, shared 130) · review: PASS round 1. Note: the ReportsPage test 'shows nothing for a report code the catalog has no title for' still passes without the guard line (weak test, other test guards it).
- Slice 8 contract: new tag report-templates; extra problem code TEMPLATE_SLOT_TAKEN (409); versions list needs REPORT_TEMPLATES_MANAGE; the tax-word guard test (MembersAccessAcceptanceTests) relaxed a second time with exact exceptions for 'tax report' and 'enum: [TAX]'.
- Slice 8 backend: contract adds TEMPLATE_VERSION_CONFLICT (409 backstop); TWO_ROW_SETS dry parse implemented at upload (reuses TemplateRowSet for the filler); external links, connections and queryTables stripped at upload.
- Slice 8 web: ReportTemplatesPage built; audit rendering for REPORT / REPORT_TEMPLATE fields added; RED not run for macros mapping, slot taken, new version upload, route gating, tax slot, warning notice (reported by the agent).
- Slice 8 · 0af4b79 · slice verify green (backend reports+xlsx+Modularity+MembersAccess, typecheck, lint, vitest 42, shared 130) · review: MAJOR_ISSUES round 1 (M1), fixed in 65a96af, PASS round 2
- Slice 9 spike: ADR 0015 accepted (own OOXML filler, POI fallback not triggered); FormulaShifter written (178 cases, 100% lines). New codes to wire in the contract/i18n: ARRAY_ACROSS_ROW (upload refusal under TEMPLATE_INVALID), RICH_TEXT_FLATTENED (warning). X-Report-Warnings-Detail capped at 4 KB encoded.
- Slice 9 backend done in two parts (catalog/values/contract, then filler/generate). Engine size 1912 lines (1513 without blanks/comments) vs ADR trigger ~2000: README says the next filler feature should reopen the POI question. Warnings sheet only appended when >=1 warning; X-Report-Warnings: 0 still sent. Body validation (400) runs before PreAuthorize (403) for malformed bodies (accepted, tested, documented). Fill-time refusals map to 400 TEMPLATE_INVALID/BROKEN_ZIP. No file opened in real Excel/LibreOffice yet.
- Slice 9 · 15674cb · slice verify green (backend reports+ledger+xlsx+Modularity, typecheck, lint, vitest 26 (full web 738 by agent), shared 130, api-client 9) · review: MAJOR_ISSUES round 1 (M1), fixed in 8f78b3e, PASS round 2
- Slice 10 demo: template and requisites are loaded only when the Flyway placeholder demoledger=on (dev profile); integration tests keep it off (existing tests assume no template in the demo cooperative); DemoReportTemplatesTests runs the script itself. Demo tenant edrpou set to 12345678 only WHERE NULL.
- Slice 10 links: the plot link sits on the plot card next to PlotAccountPanel (the 'plot account' of the spec) and only for non-common plots; the month link shows for closed months too.
- Slice 10 performance: all 8 reports < 350 ms preview on 300 plots x 12 months (14.6k charges); no index/V21 needed (charge_adjusts_idx exists since V19); statements per preview 18 (bound 40). Missing-readings worst case = all meter kinds.
- Slice 10 · e7a811a · slice verify green (backend reports+Modularity, typecheck, lint, vitest 35, shared 130) · review: PASS round 1
- AUTO/USER: ports 8080/5173 were held by another session's old feature-012 stack (vite pid 89673, java pid 89864, no pids file). Asked the user; answer: stop them myself. Stopped both by PID.

## STOPPED by the user — resume notes (saved state)
- Stopped at the user's request while the e2e agent was about to run the narrow Playwright specs. The agent was stopped; no process of this run is left (ports 8080/5173 free, no review worktrees of this run; the `run-012` worktree belongs to another session and was not touched).
- Commits on `013-reports` (all slices 1-10 PASS in review): slice 1 c4057b0, 2 4562a10, 3 c69e9b5, 4 ac306f8, 5 e0b1a38, 6 5943a23, 7 ac01355, 8 0af4b79 (+fix 65a96af), 9 15674cb (+fix 8f78b3e), 10 e7a811a. Base: spec commit 3eedb2f on 6c121fb.
- Slice 11 state (see tasks.md): [x] task 1 docs (money-rules section 12, glossary, architecture.md, imports README) — committed together with these notes. [ ] task 2 guides uk+en, `docs/guide/scripts/capture-screens.mjs` (reports, report-electricity, report-templates), PDFs (`make guide-shots`, `make guide`; needs the app running) — NOT started. [ ] task 3 Playwright specs — files written but NEVER RUN and left UNCOMMITTED in the working tree: web/e2e/reports.spec.ts, report-templates.spec.ts, reports-acceptance.spec.ts, support/reportFile.ts, and the edit of language-purity.spec.ts. Untested risks: inline-string xlsx from support/xlsx.ts may be refused as a template; locators inferred from markup; /members/{id}/roles and DELETE /roles/{id} behaviour assumed. [ ] Verify task.
- Next steps on `/feature-pipeline resume`: (1) check ports 8080/5173 are free; run the e2e agent (react-frontend) for the narrow specs (`pnpm --filter web exec playwright test reports report-templates reports-acceptance language-purity` then `--grep @smoke`; stack as in `make e2e`, JDK 25), fix test-side problems, commit as `013: Slice 11 ...`; (2) guides task; (3) Slice 12 Feature Testing & Regression (testing-expert); (4) Slice 11 and 12 reviews; (5) Phase 4: awos:verify, full `make check`, `make e2e`, compare with the baseline (backend baseline green at 6c121fb; client/e2e baseline not run), final report; (6) Phase 5 publish (pr+checks) and Phase 6 review run.
- Known open points for the final report / owner: see review-log.md minor notes. Highlights: missing-readings does not list a meter installed/removed INSIDE the range (spec only excludes installed after / removed before) — owner to confirm; Land tax rejects a future year (not in spec); row cap is per sheet (spec says per report); well pump share appears in both electricity-tree and water reports; filler engine is 1912 lines vs the ADR 2000-line POI trigger (record a decision); the tax-word guard test (MembersAccessAcceptanceTests) was relaxed twice with exact exceptions; DECIMAL column type, new problem codes TEMPLATE_SLOT_TAKEN / TEMPLATE_VERSION_CONFLICT and the `report-templates` tag were added beyond the spec; no filled template has been opened in real Excel/LibreOffice; the tax card shows no message when a 409 TEMPLATE_NAME_TAKEN occurs; demo template/requisites load only in the dev profile (demoledger=on).
- AUTO/USER decisions: spec source = reconciled spec on origin/docs/feature-013-reports (user answer); stop old 012 stack on ports 8080/5173 (user answer).

## Resumed 2026-10-27
- State rebuilt: branch 013-reports at 44e19c9; slices 1-10 PASS; Slice 11 task 1 done; e2e specs uncommitted; ports 8080/5173 free; docker infra up. Resumed the e2e agent to run the narrow Playwright specs.
- Slice 11 guides part A done (text uk+en for board guide ch.21 + owner manual + capture-screens.mjs: figures reports, report-electricity, report-templates, settings-requisites). Part B (make guide-shots, make guide) waits for the e2e agent to free ports 8080/5173.
- Slice 11 e2e: new specs green (6 passed), @smoke 15/15. Product defects found by language-purity: (1) audit log shows raw field key 'template' for REPORT_GENERATED; (2) field catalog on /reports/templates leaks ~200 Latin words in Ukrainian (keys, aria-labels, examples, codes). The first e2e agent skipped /reports/templates in Ukrainian in language-purity.spec.ts as a stop-gap: NOT acceptable, fix agent re-enables it. Infra note for the owner (not feature 013): default CREATE DATABASE nadel_e2e OWNER nadel_owner fails in the dev compose Postgres (template1 collation 2.36 vs OS 2.31); make e2e would fail the same on this infra; worked around with TEMPLATE template0.
- Guide part B: started backend (java 41297) and web (node 41145) for screenshots; to stop: kill 41297 41145
- Guide part B done: make guide-shots (8 new images, 82 existing regenerated because every screen now has the Reports menu item) and make guide (4 PDFs). Stopped backend 41297 and web 41145 by PID; ports free.
- Slice 11 · (hash below) · verify: narrow Playwright (reports, report-templates, reports-acceptance, language-purity) 12/12 and @smoke 15/15 by the e2e agents; own checks: typecheck, lint, full web vitest 747, shared 130, prettier. Full make e2e runs in Phase 4. Review: running (round 1)
