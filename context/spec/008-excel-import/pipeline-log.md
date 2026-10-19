# Pipeline log — 008 Excel import

- Status: running
- Phase: 1 — analysis
- Branch: 008-excel-import · Base: main (= origin/main) @ bcddbbb
- Requirement: docs/features/008-excel-import.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
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
