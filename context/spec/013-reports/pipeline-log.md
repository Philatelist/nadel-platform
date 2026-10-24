# Pipeline log — feature 013 reports

- Status: running
- Phase: 1 done, entering 2
- Branch: 013-reports (planned) · Base: origin/docs/feature-013-reports @ 6c121fb (user's choice; main d8d7092 is its ancestor)
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
- Slice 4 · (hash below) · slice verify green (backend reports+plots+members+ledger+Modularity, typecheck, lint, vitest 22, shared 130) · review: running (round 1)
