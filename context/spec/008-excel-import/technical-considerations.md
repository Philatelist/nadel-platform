# Technical Specification: Excel Import

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Draft
- **Author(s):** Alex Usov (pipeline; inputs from spring-backend, postgres-database and react-frontend agents)

---

## 1. High-Level Technical Approach

- **Module and boundaries:** a new Spring Modulith module `app.nadel.imports` owns file parsing, the preview, history and
  the endpoints. It writes data only through new public facades:
  - `members.MemberImport`: plan and apply for member rows;
  - `plots.PlotImport`: plan and apply for plot rows and first owners.

  Dependencies: `imports` → `members`, `plots`, `audit`, `identity`, `problem`, `tenancy`. Nothing depends on
  `imports`. `plots` → `members` already exists, so the graph stays acyclic.
- **Reused validation:** the facades reuse the existing validation (`PlotData`, `MemberData`, `CadastralNumber`,
  `PhoneNumber`, `DeliveryChannel`, `Share`, `OwnershipRules`) through non-throwing `tryFrom` variants. These return
  field problems instead of throwing.
- **Parsing:** library **fastexcel** (`org.dhatim:fastexcel-reader` for streaming reads, `org.dhatim:fastexcel` for the
  template), exact pinned versions (0.20.2 at design time; re-checked against Maven Central and OSV in Slice 1).
  - A `ZipGuard` checks the archive before parsing: magic bytes, required parts, entry count, real decompressed size.
  - fastexcel's XML parser has DTDs and external entities disabled.
- **Preview:** parsing runs outside any transaction. Planning reads the cooperative's data in one transaction and
  stores an `import_run` row with:
  - the normalised plan (jsonb);
  - a digest of the plan;
  - the counts and problems;
  - an expiry 30 minutes ahead (Kyiv `Clock`).
- **Commit, in one transaction:**
  1. a tenant advisory lock;
  2. lock the run row;
  3. check its status and expiry;
  4. re-plan from the stored rows and compare digests (a different digest is `IMPORT_STALE`);
  5. apply members, then plots, then first owners;
  6. publish the `IMPORT_COMMITTED` audit event;
  7. mark the run COMMITTED and clear the plan.

  Any failure rolls everything back.
- **Web:** one `/imports` page (template download, upload, preview, commit, result, history). `nadelFetch` learns
  binary responses and multipart bodies.

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database (`V9__import_run.sql`)

| Object | Key points |
|---|---|
| `import_run` | `id uuid pk`, `tenant_id` (FK tenant), `file_name text` (1–255, sanitised base name), `created_by_member_id uuid null`, `created_by_subject text`, `created_by_name text` (snapshot for history), `created_at`, `expires_at`, `committed_at null`, `status text` CHECK in (`PREVIEW`,`HAS_ERRORS`,`COMMITTED`,`STALE`), `counts jsonb not null default '{}'`, `problem_count int ≥ 0`, `plan jsonb null`, `plan_digest text null` |
| checks | `plan is null or status = 'PREVIEW'`; `(status = 'COMMITTED') = (committed_at is not null)` |
| indexes | `(tenant_id)`, `(tenant_id, created_at desc)` |
| RLS | enable + `tenant_isolation` (USING + WITH CHECK), as V7/V8 |
| grants | `REVOKE DELETE, TRUNCATE ON import_run FROM nadel_app` (history is kept) |

- "Expired" is derived: a PREVIEW past `expires_at` shows as EXPIRED.
- Plans are cleared:
  - on commit;
  - on STALE and HAS_ERRORS;
  - lazily, on any import call in the same tenant, for expired previews.
- No scheduled purge (JobRunr is not in the project yet; noted in ADR 0012).
- No demo-data change.

### 2.2 Backend

| Area | Classes / changes |
|---|---|
| imports (new) | `README.md`, `package-info`; internal: `ImportsController` (implements `ImportsApi`, all endpoints `@PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")`), `ImportService`, `ImportRun` + `ImportRunRepository`, `TemplateWriter`, `ZipGuard`, `WorkbookReader` (the only fastexcel user), `HeaderMatcher` (normalise: lower-case, drop non-letters/digits; aliases incl. Ukrainian in one resource), `Cells` (text, Excel/`DD.MM.YYYY` dates, yes/no/так/ні/true/false, numbers normalised to 15 significant digits, comma decimals, area m²/sotky with 1 sotka = 100 m² exact), `PlotsSheet`/`MembersSheet`/`OwnershipSheet` parsers (rows starting with `#` skipped), `ImportPlanner`, `PlanDigest` |
| members | public `MemberImport` (`plan(rows, importId)` read-only → per-row NEW/UPDATE/UNCHANGED + member id + changed fields + `FieldProblem`s; `apply(plan, actor)` `Propagation.MANDATORY`), records `MemberImportRow`, `MemberImportPlan`; internal impl; `MemberData.tryFrom`; bulk lookups (active by `lower(email)`; by `lower(last_name)`, `lower(first_name)` and first phone); blank cells overlay the existing record; new members get deterministic ids `nameUUIDFromBytes(importId/member/key)`; per-record audit `MEMBER_CREATED`/`MEMBER_UPDATED` |
| plots | public `PlotImport` (plan plots + ownership; apply), records `PlotImportRow`, `OwnershipImportRow`, plan types; internal impl; `PlotData.tryFrom`; bulk lookups (active by `lower(number)`, cadastral numbers incl. archived, current ownership and history by plot ids); ownership outcome per plot: FIRST_OWNERS (no current owner), SKIP (same set of member + share, date ignored), CONFLICT (`OWNERSHIP_CONFLICT`); all rows of a plot share one owner-since date (`OWNERSHIP_DATE_MISMATCH`); the core of `OwnershipCommands.change` (start rows, status refresh, `OWNERSHIP_CHANGED` audit) is extracted for reuse; per-record audit `PLOT_CREATED`/`PLOT_UPDATED`; new plots get their GREY `plot_status` row |
| problem | public helper mapping Bean Validation violations to field codes (moved from `ProblemExceptionHandler`); 413 → `FILE_TOO_LARGE`; new codes below |
| audit | `AuditAction.IMPORT_COMMITTED`, `AuditEntityType.IMPORT` (entity id = import id, label = file name; fields: counts) |
| config | `spring.servlet.multipart.max-file-size: 10MB`, `max-request-size: 11MB`, `server.tomcat.max-swallow-size: 20MB`; Hibernate `jdbc.batch_size: 50`, `order_inserts`, `order_updates`; inserts via `persist` (no merge per row) |
| dependency | `org.dhatim:fastexcel-reader` and `org.dhatim:fastexcel`, same exact version (PR note; ADR 0012) |

Robustness:
- `ZipGuard` uses JDK `ZipInputStream` and caps the archive at about 200 entries, 50 MB per entry and 100 MB in total.
  It rejects files that are not `.xlsx`, OLE2 files (xls or encrypted) and files missing `xl/workbook.xml`.
- Row streaming stops beyond the limits.
- File names are sanitised and never logged.
- Personal data is never logged.

### 2.3 API contract (tag `imports`)

| Endpoint | Shape |
|---|---|
| `GET /imports/template` (`downloadImportTemplate`) | 200 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `format: binary`, `Content-Disposition` attachment |
| `POST /imports` (`uploadImport`) | multipart, required `file` → 201 `ImportPreview {id, fileName, expiresAt, counts, problems (≤ 1,000), problemCount, committable}` |
| `POST /imports/{id}/commit` (`commitImport`) | 200 `ImportResult {id, counts}` |
| `GET /imports?page&size` (`listImports`) | page of `ImportSummary {id, fileName, createdAt, createdByName, status (PREVIEW, HAS_ERRORS, COMMITTED, STALE, EXPIRED), counts}` |

Shared types:
- `ImportCounts {plots, members, ownerships}`, each `{created, updated, unchanged}`.
- `ImportProblem {sheet: FILE|PLOTS|MEMBERS|OWNERSHIP, row (Excel row number, 0 = file/sheet level), column (canonical key), code, params: map<string,string>}`.

Error codes:
- HTTP status: 413 `FILE_TOO_LARGE`; 400 `FILE_NOT_XLSX` (also corrupt, encrypted or bomb), `IMPORT_LIMIT_EXCEEDED`; 409
  `IMPORT_HAS_ERRORS`, `IMPORT_STALE`, `IMPORT_ALREADY_COMMITTED`; 410 `IMPORT_EXPIRED`; 404 for an unknown or other
  cooperative's id.
- Row codes reuse existing field codes (`REQUIRED`, `TOO_LONG`, `INVALID`, `PHONE_INVALID`, `CHANNEL_CONTACT_MISSING`,
  `CADASTRAL_INVALID`, `CADASTRAL_NUMBER_TAKEN`, `AREA_INVALID`, `SHARE_INVALID`, `SHARES_NOT_ONE`, `DUPLICATE_OWNER`,
  `OWNERSHIP_DATE_INVALID`, `PLOT_COMMON_LAND`, `PLOT_HAS_OWNERSHIP_HISTORY`).
- New row codes: `SHEET_MISSING`, `HEADER_MISSING`, `DUPLICATE_IN_FILE`, `UNKNOWN_MEMBER_KEY`, `UNKNOWN_PLOT`,
  `AREA_BOTH_OR_NONE`, `OWNERSHIP_CONFLICT`, `OWNERSHIP_DATE_MISMATCH`, `DATE_INVALID`, `NUMBER_INVALID`, `YES_NO_INVALID`,
  `MEMBER_AMBIGUOUS`, `MEMBER_KEY_MISSING`.

### 2.4 Web

| Area | Files |
|---|---|
| api-client | `nadelFetch`: keeps a caller's `Accept`, returns a `Blob` for non-JSON success responses, never sets `Content-Type` (FormData boundary), defensive JSON error parsing; barrel exports for `imports`; unit tests |
| helpers | `web/src/downloadBlob.ts` (object URL + anchor + revoke), `importFile.ts` (`.xlsx` extension, 10 × 1024 × 1024 bytes limit) |
| pages | `ImportPage.tsx` at `/imports` (RequirePermission `IMPORT_RUN`, nav item "Імпорт" after members): step 1 template download; step 2 file picker (hidden input + label button, chosen name), upload; `ImportPreviewPanel` (counts table, problems table with i18n messages via `format`, first 200 shown + "and N more", Import button only when committable); `ImportResultPanel` (counts, links to plots and members); `ImportHistory` panel with Pagination; error notices for the import codes (expired/stale → "upload again") |
| i18n | `nav.imports`, `import.*`, `importHistory.*`, `importError.*`, `importProblem.*`, column labels, audit labels for `IMPORT_COMMITTED`/`IMPORT` |

## 3. Impact and Risk Analysis

- **New dependency:** fastexcel and its transitive dependencies (aalto-xml, commons-compress, opczip) go through
  OSV-Scanner and Semgrep in CI. Any advisory fails the build. The versions are checked in Slice 1, and an advisory is
  reported, never ignored.
- **Language rule:**
  - The Ukrainian header aliases are input vocabulary required by the feature doc. They live in one backend resource
    file, and the exception is recorded in ADR 0012.
  - The template's headers and instruction row are English.
  - A CLAUDE.md wording change is a follow-up for the owner.
- **Transaction size:** up to 2,000 plots, 3,000 members and about 2,000 ownership groups, with 5,000–8,000 audit
  events and outbox rows. Mitigation: bulk lookups, batched inserts, and a large integration test that measures it.
- **Plan drift:** the plan is recomputed under locks and compared by digest. A unique or exclusion violation during
  apply maps to `IMPORT_STALE`.
- **Personal data at rest:** `import_run.plan` holds personal data until it is cleared. It is under RLS and nulled on
  commit, error, stale and expiry.
- **Coverage:** the floor is 0.96, and the local baseline measured 0.9610. This branch-heavy module needs thorough
  tests.
- **Client:** changing `nadelFetch` touches every API call; it is covered by unit tests and the full e2e suite.

## 4. Testing Strategy

- **Unit:**
  - `HeaderMatcher` (aliases, case, order, Cyrillic).
  - `Cells`:
    - Excel serial dates and `DD.MM.YYYY`;
    - yes/no variants;
    - area units and both/none;
    - 15-digit noise and comma decimals.
  - Member matching keys: email, name + first phone, ambiguous, missing key.
  - Ownership planner: first owners, skip, conflict, date mismatch, common land.
  - `ZipGuard`: a bomb, a non-zip file, an OLE2 file, a missing workbook part.
  - XXE: a DOCTYPE file returns a clean 400.
  - The unchanged template previews as zero rows and zero problems.
  - `PlanDigest` stability.
- **Integration (Testcontainers):**
  - Preview saves nothing; commit saves everything and audits one summary plus the per-record entries.
  - Re-import is all unchanged; update by email; ownership conflict.
  - Stale (a plot added after the preview); expired (mutable `Clock`); already committed.
  - A mid-way failure (spy on `PlotImport.apply`) rolls back members, plots, ownership, audit and the run status.
  - Limits and an oversized file return 413.
  - 403 on all four endpoints for treasurer, member and operator.
  - `ImportRunTenantIsolationTests`, and an import never touches another cooperative.
  - A large-file performance test at 2,000 plots and 3,000 members.
- **Fixtures:**
  - Generated at test time by an `ImportWorkbooks` helper (fastexcel writer).
  - Small committed binaries under `backend/src/test/resources/imports/`: `valid.xlsx` (30 plots, 35 members,
    co-owners) and `errors.xlsx`. A test asserts their counts. `*.xlsx binary` goes in `.gitattributes`.
  - No 10 MB binary in the repository; oversized files are generated in tests.
- **Web unit:**
  - `nadelFetch` (Blob, Accept, FormData, errors), `downloadBlob`, `validateFile` boundaries.
  - Preview panel: commit disabled, params interpolation, the 200 cap.
  - `ImportPage` flows (stale shows "upload again"; history empty state).
  - Nav gating.
  - Every planned code has a catalog entry.
- **E2E (Playwright):**
  - The chairman downloads the template (a download event, file size above 0).
  - The chairman uploads a valid workbook, sees the preview counts, commits, sees the plots in the list, and re-uploads
    to see everything unchanged.
  - The chairman uploads an invalid workbook: the problems are listed and Import is disabled.
  - A member gets "no access".
  - Workbooks for the shared dev database are built at test time with a small dependency-free writer in
    `web/e2e/support/xlsx.ts` (stored zip + inline strings), with unique plot numbers and emails per run; the oversized
    file is built with `Buffer.alloc`.
- **Gate:** `make check` (coverage floor raised to the measured value, rounded down) and `make e2e`.

## Assumptions (pipeline)

- The template is in English (headers, instruction and example rows); Ukrainian headers are accepted on upload.
- A member row with neither email nor phone is an error (`MEMBER_KEY_MISSING`), because it could not be matched on a
  re-import; 2 or more members matching name + first phone is `MEMBER_AMBIGUOUS`.
- Comparing ownership with the file uses member + share only; the owner-since date is ignored. Every row of one plot
  carries the same owner-since date.
- A blank kind on a new plot is required; a common-land plot with no area gets 0.
- The history lists every upload, including previews with errors and stale ones; expired is shown for uncommitted
  previews past 30 minutes.
- Commit is synchronous in the request; no background job.
