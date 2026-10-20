# Pipeline log — 008 Excel import

- Status: running
- Phase: 5 — publish (push, PR, checks)
- Branch: 008-excel-import · Base: main (= origin/main) @ bcddbbb
- Requirement: docs/features/008-excel-import.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/27 · Checks: red: Backend, Security
- Review run: on (pending)

## Project facts

Same as 004–007 (see `context/spec/005-ui-preferences/pipeline-log.md`). Re-checked: main = origin/main @ bcddbbb (007 merged as #26); pipeline tooling present on main; clean tree; the 007 review run was stopped (PIDs 21007 21170 21210); ports 8080/5173 free; JaCoCo floor 0.96; latest migration V8 → next V9. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. UI verification with the project's Playwright. Safe local run: `make up`. zsh does not word-split unquoted variables (kill each PID explicitly).

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** load a whole cooperative from one `.xlsx` (sheets Plots, Members, Ownership): upload → preview without saving (counts new/updated/unchanged per sheet, row-level errors) → commit in one transaction; idempotent re-import (plots by number, members by email or last+first name+first phone, existing ownership skipped, conflicting ownership an error); template download; import history; audited; limits 2,000 plots / 3,000 members / 10 MB; streaming reader. Users: the chairman (IMPORT_RUN).

**Prior work:** Agent A — nothing off main. Agent B — `IMPORT_RUN` permission complete (enum, catalog, seed, i18n; Chairman only); no xlsx library, no multipart, no binary download anywhere; reusable validation is package-private (`PlotData`, `CadastralNumber`, `Share`, `OwnershipRules`, `MemberData`, `PhoneNumber`, `DeliveryChannel`); no public write APIs in members/plots; no exact name+phone match query; the web fetch mutator always parses JSON. Verdict: **new; builds on 002, 006, 007**.

**Scope:** sections 2–6 of the feature doc. Out: section 7 (polygons, CSV, Google Sheets, scheduled imports), mobile.

**Verification:** unit (header aliases, area units, shares, matching keys, dates), Testcontainers (preview saves nothing; commit all-or-nothing; rollback on mid-way failure; re-import changes nothing; conflicting ownership error; isolation; limits; stale/expired preview), fixtures (valid 30 plots/35 members/co-owners, typical errors, oversized), Playwright (download template, upload valid fixture, preview, commit, plots in the list), `make check`, `make e2e`, screenshots on `make up`.

**Risks:** new Maven dependency (OSV-Scanner fails on any advisory; Semgrep on XML/zip parsing); zip bomb / XXE in xlsx; memory for 10 MB files; large single transaction with ~5,000 audit events; module boundaries (new public write APIs in members and plots); binary download and multipart in the generated client; 1 MB default multipart limit.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with decisions in section 8).
- AUTO: a new module `app.nadel.imports` (parsing, preview, history, endpoints) calling new public facades in `members` and `plots` (validate-and-collect-errors + apply, plain records); ADR 0012 records it (rule: CLAUDE.md modules talk through public APIs; ADR 0011 pattern).
- AUTO: library `fastexcel` (reader for streaming parse, writer for the template), exact pinned versions, noted in the PR (rule: streaming reader required; smallest dependency surface for OSV-Scanner).
- AUTO: the preview's parsed and normalised rows are kept server-side with the import id for 30 minutes (tenant-scoped table, RLS) and cleared on commit or expiry; at commit the plan is recomputed against current data and must equal the previewed plan, otherwise 409 `IMPORT_STALE` (rule: section 3 "cannot be committed after the data it was based on changed").
- AUTO: blank optional cells leave existing values unchanged on update; the file never archives or deletes records (rule: conservative — no data loss on a partial re-import).
- AUTO: Members sheet gains optional `telegram_username` and `viber_phone` columns so TELEGRAM/VIBER channels can be imported (rule: existing member rule requires the matching contact; without the columns those channels could never be imported).
- AUTO: the Ownership sheet lists current owners only; a plot with no current owner gets them as first owners (same share/date rules as 007, date not in the future); identical current ownership is skipped; any other current ownership is an error ("use change of owner") (rule: section 3 step 4).
- AUTO: only active plots and members match; archived ones are never updated (rule: 006/007 uniqueness is among active records).
- AUTO: rows whose first cell starts with `#` are comments and skipped; the template's instruction and example rows are comment rows (rule: section 2 instruction and example rows must not be imported by accident).
- AUTO: commit runs synchronously in the request (one transaction); no JobRunr (rule: JobRunr not in the project yet; limits keep the run bounded).
- AUTO: one `IMPORT_COMMITTED` audit entry (file name, counts) plus the usual per-record entries (rule: section 3 step 5).
- AUTO: template, upload, commit and history all require `IMPORT_RUN` (rule: ADR 0009).
- AUTO: row errors are returned as codes with parameters (sheet, row, column); the UI renders the message in its language (rule: i18n only in the client catalogs).

## Phase 2 — branch and specs

- Spec folder and branch `008-excel-import` from main @ bcddbbb (= origin/main). Baseline `make check` started in a detached worktree.
- functional-spec.md written (Creation Mode, no markers; 5 requirement groups).
- Baseline: make check green (691 backend, coverage 0.9610 in a fresh worktree vs floor 0.96 — thin; client 56/65/186). Recorded in review-log.md.
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend; exploration from Phase 1 Agent B).
- AUTO: fastexcel 0.20.2 (reader + writer; transitive aalto-xml 1.4.0, commons-compress 1.28.0, opczip 1.2.0; no OSV advisories at design time) — re-check in Slice 1 (rule: OSV fails on any advisory; never add an ignore).
- AUTO: the template is in English; Ukrainian header aliases live in one backend resource and the exception to the English-only rule is recorded in ADR 0012; a CLAUDE.md wording change is proposed to the owner, not made (rule: CLAUDE.md is protected; the feature doc requires Ukrainian aliases).
- AUTO: a member row with neither email nor phone is an error (MEMBER_KEY_MISSING); name+first phone matching two members is MEMBER_AMBIGUOUS (rule: idempotent re-import — an unmatched key would duplicate members).
- AUTO: ownership compared by member + share, date ignored; one owner-since date per plot in the file (OWNERSHIP_DATE_MISMATCH) (rule: corrections may shift stored dates; a non-ended timeline must sum to 1 every day).
- AUTO: import_run statuses PREVIEW/HAS_ERRORS/COMMITTED/STALE, EXPIRED derived; no scheduled purge (rule: JobRunr not yet in the project).
- AUTO: commit takes a per-tenant advisory lock and the run row lock, re-plans and compares a plan digest; a unique/exclusion violation during apply maps to IMPORT_STALE (rule: section 3 "cannot be committed after the data changed").
- AUTO: e2e workbooks are generated at test time by a dependency-free writer (no new web dependency) (rule: unique data per run on the shared dev DB; supply-chain surface).
- tasks.md written: 8 slices (template; plots preview; members preview; ownership preview; commit; history; e2e/docs/coverage; Feature Testing & Regression by testing-expert). No staffing gaps (xlsx parsing stays with spring-backend; no specialist skill for spreadsheet parsing — noted for the report). No `not-user-reviewed` marker.
- Slice 1 backend: fastexcel 0.20.2 (writer + reader); OSV clean for fastexcel, fastexcel-reader, opczip 1.2.0, aalto-xml 1.4.0, stax2-api 4.3.0, commons-compress 1.28.0, commons-codec 1.21.0, commons-io 2.20.0, commons-lang3 3.20.0. verify 703 tests, coverage 0.9673. Findings: JDK ZipInputStream rejects fastexcel-written files (data descriptors) → ZipGuard must use ZipFile/commons-compress; demo members linked to a Keycloak subject persist across test classes (tests must unlink).
- Slice 1 committed (backend targeted 14 green, verify 703 / coverage 0.9673; ui-tokens 56, shared 65, api-client 7 (new test script, existing vitest version), web 191; typecheck/lint/format green). Review r1 started.
- Slice 1 review r1 PASS.
- Slice 2 backend: verify 867 tests, coverage 0.9714. ZipGuard uses commons-compress ZipFile in memory (commons-compress is transitive via fastexcel-reader, not declared). AUTO: a preview with nothing to import is not committable (rule: an empty commit would only add a no-op audit entry).
- Slice 2 committed (backend targeted 147 green, verify 867 / coverage 0.9714; ui-tokens 56, shared 67, api-client 7, web 204; typecheck/lint/format green). Review r1 started.
- Slice 2 review r1 MAJOR_ISSUES (M1 size-message wording) → fix 974bb6a → r2 PASS.
- Slice 3 backend: verify 913 tests, coverage 0.9745. AUTO: the Members and Ownership sheets are optional (a plots-only workbook is valid; Plots is required) (rule: a partial file is still useful; the template always has all three). AUTO: EMAIL_TAKEN when a row's email belongs to one member but its name and first phone identify another; a present-but-unknown email is a new member (rule: matching keys of section 3 step 4). AUTO: a 9-digit numeric phone cell regains its leading 0 (rule: Excel drops leading zeros of Ukrainian numbers stored as numbers).
- Slice 3 committed (backend targeted 97 green, verify 913 / coverage 0.9745; shared 67, api-client 7, web 205, ui-tokens 56; typecheck/lint/format green). Web agent resumed once to finish its evidence run. Review r1 started.
- Slice 3 review r1 PASS. Owner follow-up: a hand-entered member without email is duplicated when a file adds their email (literal matching rule).
- Slice 4 backend: verify 961 tests, coverage 0.9766. AUTO: a bare whole number share "1" reads as 1/1 (rule: the template instruction promises "1, 1/2 or 1/3"); a member or plot row with problems suppresses its ownership group checks (rule: no cascading problems); the plan digest is order-independent and excludes ids of records the import creates.
- Slice 4 committed (backend targeted 116 green, verify 961 / coverage 0.9766; api-client 7, shared 67, web 207, ui-tokens 56; typecheck/lint/format green). Review r1 started.
- Slice 4 review r1 MAJOR_ISSUES (M1 template share column not text → "1/2" became a date) → fix 45e9bd3 → r2 PASS.

### Slice 5 backend returned
- Agent report: backend `verify` 986 tests green, line coverage 0.9718 (floor 0.96). Large-file test: 2,000 plots / 3,000 members / 2,000 ownership rows, commit 7.3 s, tagged `large`. RED checks: digest skipped, atomicity removed and expiry skipped each made a test fail; the code was restored.
- AUTO: new code `IMPORT_NOTHING_TO_COMMIT` (409) for an error-free preview with nothing to create or update, instead of reusing `IMPORT_HAS_ERRORS`, which would be inaccurate (rule: conservative choice; the spec already says such a preview is not committable).
- AUTO: facade signatures take the validated rows too (`MemberImport.apply(tenantId, rows, plan, actor)`, `PlotImport.apply(tenantId, plotRows, plotPlan, ownershipRows, ownershipPlan, actor)`), plus `lock` methods; the commit plans twice (find rows to lock, then re-plan on the locked rows) and compares the digest.
- AUTO: constraint violations are mapped to `IMPORT_STALE` inside the owning modules instead of in `ImportService` (module boundaries).
- Slice 5 web returned: `ImportResultPanel`, commit button, error notices (NOTHING_TO_COMMIT as a warning), AuditPage labels. AUTO: plain `useMutation({ mutationFn: commitImport })` instead of the generated hook so page tests can mock the client.
- Slice 5 verify (orchestrator): targeted backend classes green (ImportCommitTests 20, ImportFixturesTests 3, preview, ownership, members, plots, audit, ModularityTests); client: ui-tokens 56, shared 67, api-client 7, web 221; typecheck, lint, format green.
- Slice 5 committed fbf589b; review r1 started (worktree review-008-s5-r1, range 45e9bd3..fbf589b). Slice 6 backend started.
- Slice 5 r1: MAJOR_ISSUES (M1 untested constraint→IMPORT_STALE mapping). Fix queued after the Slice 6 backend agent finishes (same backend tree and target dir). Cheap minors folded in: Postgres-order UUID comparator, logServerErrorDetail=false (personal-data rule), disable Import after a final refusal, comment fix.
- Slice 5 r1 web fix done (Import disabled after a final refusal; web 227). Backend fix for M1 started after the Slice 6 backend agent returned.
- Slice 6 backend returned: `GET /imports` → `ImportPage` of `ImportSummary` (status schema `ImportRunStatus` to avoid a clash with the generated Java enum; `problemCount` added; `uploadedBy` = current display name with the upload-time name as fallback; tie-break `id desc`; no migration — V9 index suffices). ImportHistoryTests 17/17, RED checked. Slice 6 web started.
- Slice 6 web returned: ImportHistory panel (page size 20, Kyiv time, five status labels), refresh after upload and commit; client checks green (web 237, shared 67, api-client 7, ui-tokens 56). Backend Slice 6 verify waits for the Slice 5 fix agent (same backend target dir).
- Slice 5 r1 backend fix returned: `ImportConstraintViolationTests` (all four constraints at a real batched flush → 409 IMPORT_STALE; the old matcher already matched), shared `problem.ConstraintViolations` matcher (SQLState 23505/23P01 + constraint name, walks causes and `getNextException`), `UuidOrder.POSTGRES` in plots and members, `logServerErrorDetail=false` via Hikari data-source properties, `org.hibernate.orm.jdbc.error` logger OFF (it logged whole row values at WARN; tests capture the log), README notes. RED checked for each.
- AUTO: accept the `org.hibernate.orm.jdbc.error` logger set to OFF (rule: personal data is never logged; the exception still propagates and is mapped). PR follow-up: owner may prefer a filtered appender instead.
- AUTO: `imports/README.md` holds both the Slice 5 fix and the Slice 6 notes; it goes into the Slice 5 fix commit as one file (no partial staging).
- Orchestrator verify (one run): ImportConstraintViolationTests 6, ConstraintViolationsTests 7, UuidOrderTests 2+2, ImportCommitTests 20, ImportHistoryTests 17, ImportFixturesTests 3, OwnershipChange 28, MembersWrite 22, PlotsWrite 42, AuditRead 24, Modularity 2 — all green; client green (web 237).
- Committed c12a3dd (Slice 5 r1 fixes) and fc3f9f6 (Slice 6). Reviews started: Slice 5 r2 (fbf589b..c12a3dd), Slice 6 r1 (c12a3dd..fc3f9f6). Slice 7 (testing-expert) started; its commit waits for Slice 5 PASS (gate).
- Slice 5 r2: PASS. Owner follow-up for the PR: app-wide OFF of `org.hibernate.orm.jdbc.error`.
- Slice 6 r1: PASS. Slices 1–6 all PASS.
- Slice 6 r1 minor fixed by the orchestrator: history refreshed after a refused commit (ImportPage onError + test; RED checked: without the fix the new test fails). Other Slice 6 minors left for the PR follow-ups.
- Slice 7 returned (testing-expert): `web/e2e/imports.spec.ts` (5 tests) + `web/e2e/support/xlsx.ts` (writes a minimal styles part — the reader needs it), ADR 0012 + index, architecture.md, glossary (Import, Preview). Agent: make check green, backend 1020 tests, coverage 0.9785 → floor 0.96 → 0.97, make e2e 77/77. Orchestrator re-running make check and make e2e.
- Proposed CLAUDE.md change (owner commits; not made by the pipeline): Language exception line → "The only exceptions are end-user UI translations inside `packages/shared/src/i18n` (Ukrainian catalog `uk.ts`) and the Ukrainian column-header aliases of the Excel import in `backend/src/main/resources/imports/header-aliases.properties` (ADR 0012)."
- Ignored the agent's suggestion to git-ignore docs/screenshots/ (the pipeline commits screenshots there).
- Slice 7 orchestrator verify: make check exit 0 (backend 1020 tests, coverage checks met at floor 0.97); make e2e 77 passed (2.5m); ports 8080/5173 free afterwards.
- Slice 7 r1: PASS. All implementation slices 1–7 PASS. Doc minors fixed by the orchestrator. Starting Slice 8 (Feature Testing & Regression, testing-expert).
- Slice 8 returned (testing-expert): `ImportAcceptanceTests` (11, `@Tag("regression")`), `web/e2e/imports-acceptance.spec.ts` (3 Ukrainian-UI specs), `context/qa/list-of-tests.md` entries; every acceptance criterion mapped to tests; RED checked by 7 backend mutations and 2 e2e message mutations. Agent: verify 1031 tests, e2e 80/80 (before Prettier reformatted the new spec), client green (web 238). Orchestrator re-running make check and make e2e on the formatted tree.
- Slice 8 orchestrator verify: make check exit 0 (backend 1031, coverage met at 0.97; web 238, shared 67, api-client 7, ui-tokens 56); make e2e 80 passed (2.6m).
- Phase 4 started: make up running (detached, log in scratchpad makeup008.log); screenshots docs/screenshots/008-excel-import-{page,preview-errors,preview-valid,no-access-treasurer}.png captured (preview only, nothing committed in the dev DB). Note for follow-ups: an error preview still shows "Можна імпортувати до …".
- Slice 8 r1: PASS. All slices 1–8 PASS. Stack from Phase 4 screenshots stopped (PIDs 54124 54022 53853). AUTO: tighten the Slice 8 acceptance assertions (review minors 1–8, 10–12) before the PR — they are the evidence for the criteria.
- Slice 8 review minors tightened (testing-expert): exact owner counts, real template layout check, uploader name, phone read back, email column, audit value, conflict row, javadoc, robust e2e cleanup, list-of-tests rows for §2.3 #2–#4.
- Phase 4 verify: all 24 criteria marked with evidence in functional-spec.md, Status Completed. Final run on the full tree: make check exit 0 (backend 1031, coverage met at 0.97; web 238, shared 67, api-client 7, ui-tokens 56); make e2e 80 passed (2.6m).
- Phase 4 end. Phase 5 start: push the spec branch, open the PR.
- Pushed 008-excel-import (d0bdc25, no force); opened PR #27 (no AI attribution). Review of the verification commit d0bdc25 started.
- Verification commit d0bdc25 review: PASS (minor: §2.1 #1 evidence should cite TemplateWriterTests; screenshot history full of test runs).
- CI run 39418849494 on PR #27: Clients, E2E, Semgrep green; Backend red (`AuditReadTests.theDemoTreasurerHoldsAuditView` 403 MEMBER_NOT_LINKED — same failure on main's run 39338526236 after the 007 merge: test-order pollution of the demo treasurer link); Security red (OSV: tomcat-embed-core 11.0.24 and jackson 3.1.5 from the Spring Boot 4.1.1 BOM, new advisories, patched 11.0.25 / 3.1.7). Neither caused by 008. Fix attempt 1 started (spring-backend): fix the leaking test, pin patched versions; no check weakened.
- CI fix attempt 1 returned: polluter SettingsTests (fixed subject, never unlinked) → cleanUp unlinks the four demo members; AuditReadTests unlinks before signing in. Repro: SettingsTests then AuditReadTests failed before, passes after (orchestrator re-ran: exit 0). pom: tomcat.version 11.0.25, jackson-bom.version 3.1.7 (no Boot 4.1.x patch yet); dependency:tree confirms. Agent verify 1031 green, coverage met.
