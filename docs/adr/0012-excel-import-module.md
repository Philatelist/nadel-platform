# 0012. Excel import module: facades in members and plots, preview stored in the database, synchronous two-pass commit

**Status:** accepted; implemented in phase 1 by spec 008 (`context/spec/008-excel-import/`)

## Context
Feature 008 loads a whole cooperative from one `.xlsx` file with three sheets (Plots, Members, Ownership): upload, a preview
that saves nothing, a commit in one transaction, an idempotent re-import, a template download and a history of uploads.
Questions that needed a decision:

1. Where does the code live, given that it writes plots, members and ownership, and each of those modules guards its own
   rules (`internal` packages are closed, ADR 0001; no cross-module table reads)?
2. How is an `.xlsx` read safely and within memory limits (10 MB, 2,000 plots, 3,000 members)?
3. Where does the previewed data wait between the preview and the commit, and how does the commit know that the
   cooperative data did not change in between?
4. Does the commit run as a background job (ADR 0007 names JobRunr for long work)?
5. Users upload files from Ukrainian spreadsheets and expect a template in their language. The rule "everything in the
   repository is English" meets column headers such as `Площа, сот.` and a template with Ukrainian sheets, hints and examples.

## Decision
- **A new module `imports`** (`app.nadel.imports`) owns the template, the parsing, the preview, the commit orchestration, the
  history and the endpoints. It has no public API. It changes data only through two **public facades** with plain records:
  `members.MemberImport` and `plots.PlotImport`. Each facade offers `plan` (validate and collect row errors, save
  nothing; a transaction is mandatory), `lock` (take the touched rows `FOR UPDATE` in id order) and `apply` (save a plan,
  audited like a hand-made change). The business rules stay where they were (`MemberData`, `PlotData`, `OwnershipRules`,
  ...); they are reused by the facades, not copied into `imports`. Dependencies: `imports` -> `plots`, `members`,
  `identity`, `problem`, `audit` (it publishes `IMPORT_COMMITTED`); `tenancy` only implicitly, through the transaction manager.
- **Library:** `org.dhatim:fastexcel` 0.20.2 (writer for the template) and `fastexcel-reader` 0.20.2 (a streaming reader),
  pinned to one exact version. The reader's StAX parser (aalto-xml) has DTDs off. `commons-compress` 1.28.0 arrives
  transitively with the reader; `ZipGuard` uses it **directly** (its `ZipFile` over the bytes in memory) because the JDK's
  `ZipInputStream` cannot read archives written with data descriptors, as fastexcel does. A transitive dependency used
  directly is a conscious exception: its version follows `fastexcel-reader`, so a bump of the library re-checks it, and
  OSV-Scanner covers it as it covers every dependency.
- **`ZipGuard` before any parser:** extension `.xlsx`, a zip signature (so old `.xls` and encrypted workbooks, which are OLE2
  files, are refused), `[Content_Types].xml` and `xl/workbook.xml` present, at most 200 entries, 50 MB per entry and
  100 MB in total counted from the bytes decompression really produces, never from the sizes the archive declares.
  Anything the library throws becomes 400 `FILE_NOT_XLSX`. File names, sheet names and cell values are never logged.
  *Note (feature 013):* the guard moved out of `imports` into its own module `xlsx` as the public `WorkbookGuard`
  (refusals are an `UnsafeWorkbookException` with a neutral `UnsafeWorkbookReason`, no HTTP semantics; the limits are
  set per caller, the defaults are the ones above) because report templates (feature 013) check their uploads with the
  same code and tighter limits. The checks and the import's answer are unchanged: `imports` maps every refusal to 400
  `FILE_NOT_XLSX`; report templates map it to `TEMPLATE_INVALID`. commons-compress is used directly by `xlsx` now, under
  the same conscious exception as before.
- **Preview storage:** a preview inserts one `import_run` row (V9, tenant column, RLS `tenant_isolation`) holding the
  normalised rows (`plan`, personal data), the counts, the problem count and a **plan digest** (SHA-256 of a canonical,
  order-independent text of the whole plan; ids of records the import itself creates are left out). A previewed plan is valid
  for **30 minutes** (the application `Clock`). Expired plans are cleared lazily on upload and on commit; `EXPIRED` is a derived status, never
  stored. The application role cannot delete `import_run` rows, so the history stays.
- **Synchronous commit, no JobRunr.** The limits keep a run bounded (measured: 2,000 plots, 3,000 members and 2,000
  ownership rows in about 7 seconds, their audit entries written within about a second after the commit), JobRunr is not in the project yet and a half-finished background import would need
  its own state machine. The commit runs in the request, in one transaction.
- **Two-pass locking commit.** 1) A per-tenant advisory lock (`pg_advisory_xact_lock` on the tenant and `/imports`) so two
  imports of one cooperative never interleave; 2) the run row `FOR UPDATE` and the status checks; 3) the stored rows are
  planned once to find the existing plots and members they touch, those are locked (plots first, then members, each in
  id order, which is the order of a change of owner and of archiving, so no lock cycle exists); 4) the rows are
  **planned again under the locks** and the digest must equal the stored one, otherwise 409 `IMPORT_STALE`; 5) `apply`
  members, then plots with their owners, then one `IMPORT_COMMITTED` audit entry (file name and counts) next to the usual
  per-record entries, which are audited in batches (next bullet). Any other failure rolls everything back, audit events and outbox rows included, and the preview can
  be tried again.
- **Audit in batches, not one event per record.** An import at the limits changes about 7,000 records. One `AuditEvent` each
  meant 7,000 outbox publications and 7,000 listener transactions (one single-row insert each); on a slow runner the
  outbox needed more than five minutes, a test timed out and later tests waited behind the backlog. The facades'
  `apply` therefore **return** their audit events (built by the same publishers as the endpoints, so the entries are
  identical: same action, entity type, id, label and field changes) and `ImportCommitter` publishes them as
  `audit.AuditBatch` events of at most 500 entries inside the commit transaction (the outbox stores them atomically with
  the data; 7,000 records are 14 publications). The audit listener writes a batch with one JDBC batch insert in one
  transaction, idempotent by entry id (`on conflict (id) do nothing`), under the tenant bound with
  `TenantContext.runAs` (ADR 0007). The `IMPORT_COMMITTED` summary stays a single `AuditEvent`. Single-record endpoints are
  unchanged. A serialized batch can be a few hundred kilobytes of personal data in `event_publication` until it completes
  (then it is deleted), as for single events. The test suite also gets a surefire `forkedProcessTimeoutInSeconds` of 900 so
  a hung test JVM fails the build instead of blocking it.
- **Error codes of the commit:** `IMPORT_HAS_ERRORS` (409), `IMPORT_ALREADY_COMMITTED` (409), `IMPORT_EXPIRED` (410) and
  `IMPORT_STALE` (409). A preview without problems that would create or update nothing is `committable: false`; trying to
  commit it anyway is **`IMPORT_NOTHING_TO_COMMIT`** (409), because `IMPORT_HAS_ERRORS` would be untrue and an empty commit would only
  add a no-op audit entry.
- **Constraint to `IMPORT_STALE`.** A unique or exclusion violation while saving (plot number, cadastral number, member
  e-mail, ownership overlap) means that someone changed the data between the plan and the flush. The facades translate it
  (shared matcher `problem.ConstraintViolations`: SQLState `23505` or `23P01` plus the constraint name, walking the cause
  chain and `getNextException`) into 409 `IMPORT_STALE` inside the owning module, so `imports` knows nothing about
  tables.
- **Batching and personal data in logs.** New rows are persisted, not merged (no SELECT per row), with Hibernate batching
  (`jdbc.batch_size: 50`, ordered inserts and updates). A failed batch would be logged by Hibernate with the values of the
  row, which are personal data (CLAUDE.md), so `org.hibernate.orm.jdbc.error` is switched OFF and the driver property
  `logServerErrorDetail=false` is set; the exception still propagates and is mapped. A filtered appender is the alternative
  if the owner wants the log line without the values.
- **Ukrainian words are an exception to the English-only rule, in two resource files.** People fill spreadsheets in
  Ukrainian and the template follows the user's UI language, so the import has a Ukrainian vocabulary. (1)
  `backend/src/main/resources/imports/header-aliases.properties` is the **vocabulary**, the single source of truth for
  sheet names, column headers and value words (plot kinds, delivery channels, yes and no) in both languages: the readers
  accept every word listed there, and the Ukrainian template shows the **first word of each line** as its label, so the
  template cannot contain a word the upload refuses and no word list is duplicated in Java. Headers are compared after
  normalisation (lower case, letters and digits only), value words ignoring case and extra spaces. (2)
  `imports/template_uk.properties` holds the Ukrainian texts that are not vocabulary: the hint sentences and the example
  values of the template (they reach the vocabulary's words through placeholders such as `{sheet.members}` and
  `{value.kind.PLOT}`); `template_en.properties` holds the English ones. Both files are UTF-8. The two Ukrainian files are
  the only places outside the UI catalogs (`packages/shared/src/i18n`) where non-English text is allowed. Error messages
  stay codes with parameters; the client translates them.
- **Template language.** `GET /imports/template` takes an optional `lang` (`uk` or `en`; an unknown value is 400
  `VALIDATION_FAILED`, enforced by the contract's `enum` and `pattern`), otherwise the best match of the `Accept-Language`
  header, otherwise `uk`. The file name is ASCII (`nadel-import-template-uk.xlsx`, `-en.xlsx`). An upload accepts both
  languages for sheet names, headers and value words, also mixed in one file; the Ukrainian kind and channel words are
  turned into the canonical English words in `imports` before the plots and members facades see them, so those modules
  stay English-only. The `#` comment rows are unchanged: an untouched template of either language imports nothing.
- **No scheduled purge of old runs until JobRunr is used.** Plans (the only personal data in a run) are cleared lazily as
  above; the run rows themselves are the history and stay. When JobRunr arrives (ADR 0007), a job with
  `TenantContext.runAs` can drop old runs and move long commits to the background.

## Consequences
- Plots and members get new public write surface (`PlotImport`, `MemberImport`); `ModularityTests` still passes and the
  facades are documented in the module READMEs. A future import of another entity adds a facade to its module and a sheet to
  `imports`.
- A preview can be committed only if nothing it touched changed; a busy cooperative may have to upload again (a clear
  message and a re-upload button in the UI).
- A commit holds row locks on every touched plot and member for its duration (seconds at the limits) and one advisory lock per
  cooperative; ordinary edits of those rows wait, other cooperatives are not affected.
- The preview stores personal data in `import_run.plan` for up to 30 minutes (RLS protected, cleared on commit, expiry and
  stale refusal); a cooperative that never uploads again keeps an expired plan until its next upload or commit.
- A bump of `fastexcel` must re-check `commons-compress` and the OSV report; the supply-chain check never gets an ignore for
  this feature.
- `CLAUDE.md` says that everything is English except `uk.ts`; the vocabulary file and `template_uk.properties` are further
  exceptions and the owner should add one sentence to that rule (proposed wording: "The only exceptions are end-user UI
  translations inside `packages/shared/src/i18n` and the Ukrainian vocabulary and template texts of the import in
  `backend/src/main/resources/imports`, see ADR 0012").
