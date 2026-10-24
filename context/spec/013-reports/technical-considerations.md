# Technical Specification: Reports and report templates

- **Functional Specification:** `context/spec/013-reports/functional-spec.md` (requirement: `docs/features/013-reports.md`)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

A new backend module `reports` owns the report definitions, the template store, the template filler and the `.xlsx`
output. It adds no money rule: it reads through small public read facades that ledger, meters, plots, members, tariffs,
settings and tenancy each publish for their own tables (no module reads another module's tables, ADR 0001). A second
new module `xlsx` holds the workbook safety guard moved out of `imports`, so import and templates share one
implementation. The contract (`api/openapi.yaml`, tag `reports`) is written first. The web client gets a Reports section,
a templates page and a Requisites block on settings. Everything is delivered in one migration, V20.

Template filling uses a small own streaming OOXML filler (ADR 0015), not Apache POI. Reasons: no new dependency and no
new CVE surface for the OSV gate, streaming memory, one set of zip and XML guards. Fallback: if the first spike cannot
shift formulas and shared formulas correctly, switch to a pinned POI 5.5.x and record it in ADR 0015.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Modules and facades

| Module | New / changed | Responsibility |
|---|---|---|
| `reports` (new) | `internal`: controllers (generated `ReportsApi`), `ReportCatalog`, `ReportDefinition` + one per report, `ReportRunner`, `XlsxReportWriter`, `ReportTexts`, `ReportLanguage`, `TemplateStore`, `TemplateFiller`, `FormulaShifter`, `TemplateField` (catalog enum) | Reports, templates, file output |
| `xlsx` (new) | public `WorkbookGuard`, `UnsafeWorkbookException(reason)` | `ZipGuard` moved here with configurable limits; `imports` maps to `FILE_NOT_XLSX`, `reports` to `TEMPLATE_INVALID` |
| `ledger` | public `LedgerReadModel` (impl `internal.LedgerReportQueries`) | `monthTotals` (by billing month or service month; same predicate as the billing months screen), `debtsAsOf` (wraps `DebtCalculator`), `electricityCharged`, `waterCharged`, `chargedInYear`, `corrections`, `openIntervalItems` |
| `meters` | public `MeterReports` | `tree(from, to)` (same data as `GET /meter-tree`), `missingReadings`, `waterUse` |
| `plots` | `PlotCatalog.findActive(street?)`, `PlotOwners`, `PlotStatuses`, public `PlotStatus` | Active plots, owner count, cached and as-of status |
| `members` | `MemberDirectory.contacts(ids)` returning `MemberContacts` | Only source of names, phones and e-mail for Debtors (feature 014 encrypts them later) |
| `tariffs` | public `ExpectedAmounts.yearByVersion` | Land tax expected amount per rate version; money rule stays in tariffs |
| `settings` | `requisites()`; new columns | Official name, address, bank, IBAN |
| `tenancy` | `TenantSummary.edrpou` | Read-only ЄДРПОУ |

Every facade runs in the caller's transaction and returns public records with `Money` / `Quantity`.

### 2.2 Report framework

- `ReportDefinition`: `code`, `requiredPermissions` (Debtors adds `MEMBERS_VIEW`), filter definitions with validation
  (400 `REPORT_FILTER_INVALID` with field errors), sheets as columns of typed cells, `load(filters)` returning one
  `ReportResult` (rows, totals, header notes, row count). Preview and file use the same result; the preview is
  `rows[0..200)` plus totals and `rowCount`.
- Row cap: facades take `limit = 20001`; more than 20 000 rows is 422 `REPORT_TOO_LARGE`.
- A cell is `{value, reason?}`; an unknown value is null plus a reason code, never 0. Money is a string with two
  decimals, quantities have three. Totals are sums of the listed rows with no re-rounding (`Money` is the only rounding).
- `.xlsx` via fastexcel: five-row header block, frozen header, typed number and date formats, indent for the tree, bold
  totals row. File name `<code>_<from>_<to>.xlsx` (ASCII).
- Language: optional `lang` (`uk`/`en`) query parameter, as for the import template, else `Accept-Language`, else `uk`.
  Server returns column keys for the preview; the web translates them. Files need text, so generated-file bundles
  `backend/src/main/resources/reports/messages_{uk,en}.properties` are used (the ADR 0012 exception). A test asserts the
  bundles and the TS catalogs have equal keys and texts.
- `ReportRunner.run` is a plain `@Transactional` method (not read-only, which would break the outbox insert): load, check
  the cap, render, publish the audit event last. A render failure leaves no audit entry.
- Report contents (see functional spec 2.3 to 2.10): charges-by-month reads `monthTotals` twice; plot-balances and
  debtors read `findActive`, `debtsAsOf`, `PlotOwners`, `PlotStatuses` (Debtors adds `MemberDirectory.contacts` and
  the settings thresholds); electricity-tree reads `MeterReports.tree` and `electricityCharged`; water reads `waterUse`
  and `waterCharged`; missing-readings reads `missingReadings` and `openIntervalItems`; land-tax reads `ExpectedAmounts`
  and `chargedInYear`; corrections reads `corrections` with display names from `MemberDirectory`.
- Payments later add columns inside the definitions; no report definition depends on another.

### 2.3 Template engine (ADR 0015)

- Placeholder regex `\$\{([A-Za-z][A-Za-z0-9_.\-]*)}`, read from `sharedStrings.xml` (rich-text runs flattened with a
  warning) and inline strings.
- Whole-cell placeholder: typed `<c>` (number, or date as Excel serial) keeping the cell style. Placeholder inside text:
  inline string with Ukrainian formats. Unknown placeholder left in place with warning `UNKNOWN_PLACEHOLDER`; unknown
  value written empty with `VALUE_UNKNOWN`.
- Repeating row: the row whose placeholders all belong to one `rows.<set>.*` set (`plots`, `months`, `meters`, `water`);
  at most one per sheet, checked at upload by a dry parse (`TEMPLATE_INVALID`). The row is copied N times (N=0 removes it);
  rows and cells are renumbered; merged ranges, formulas (A1 tokenizer, skipping string literals, `$`, sheet-qualified
  refs, row ranges), shared and array formulas, `dimension`, conditional formatting and data validation ranges,
  hyperlinks, auto filter, defined names, table refs and row breaks are shifted. Chart references are not shifted
  (warning `CHART_REFS_NOT_SHIFTED`).
- No formula is evaluated: `calcChain.xml` is removed, `fullCalcOnLoad="1"` is set, cached values of formula cells are
  dropped.
- Upload refusals: over 2 MB (`TEMPLATE_TOO_LARGE`, checked before parsing; multipart limit set above 2 MB so our
  problem is returned), wrong extension or missing `PK\3\4`, macro content type, `vbaProject.bin`, ActiveX and OLE parts,
  any `WorkbookGuard` failure. External links and connections are stripped at upload and listed as a warning.
  XML parsing uses StAX with DTDs and external entities disabled and refuses any `<!DOCTYPE`.
- Output: a "Попередження"/"Warnings" sheet appended (name made unique if taken), response headers
  `X-Report-Warnings` (count) and `X-Report-Warnings-Detail` (base64url JSON, at most 50), both exposed in CORS;
  document properties carry the template name and version.
- Placeholder catalog: enum `TemplateField` (key pattern, type, row set, example, permission); dynamic keys
  (`totals.charged.<CODE|uuid>`, `rows.plots.debt.<CODE>`) are checked against `ChargeTypeCatalog`. Descriptions come
  from the i18n catalogs. Row sets: `plots` (plot balances as of `to` joined with land tax for the year of `to`),
  `months`, `meters`, `water`. No names or contacts.

### 2.4 Data model (Flyway V20, expand-only)

| Table / change | Key columns and rules |
|---|---|
| `report_template` | `id`, `tenant_id` (not null, FK tenant), `slot` (null or `TAX`), `name` (1..200), `description` (<=500), `status` (`ACTIVE`/`ARCHIVED`), `created_by`, `created_at`, `archived_by`, `archived_at`; unique `(tenant_id, id)`; partial unique `(tenant_id, lower(name))` where active; partial unique `(tenant_id, slot)` where slot not null and active; index on `tenant_id`; RLS `tenant_isolation`; `REVOKE DELETE, TRUNCATE` for `nadel_app` |
| `report_template_version` | `id`, `tenant_id`, `template_id`, `version_no`, `file_name`, `content` bytea, `size_bytes` (1..2 097 152 and equal to the content length), `sha256` (64 hex), `uploaded_by`, `uploaded_at`; unique `(tenant_id, template_id, version_no)`; composite FK to the template; index on `tenant_id`; RLS; `REVOKE UPDATE, DELETE, TRUNCATE` for `nadel_app` plus a guard trigger; next version is max+1 under a row lock on the template, the unique key is the backstop (409) |
| `cooperative_settings` | nullable `official_name` (<=300), `legal_address` (<=500), `bank_name` (<=200), `iban` (`^UA[0-9]{27}$`; mod 97 checked in the app) |
| `seed_base_roles` | re-created from the V19 body with `(CHAIRMAN, REPORTS_VIEW)`, `(CHAIRMAN, REPORT_TEMPLATES_MANAGE)`, `(TREASURER, REPORTS_VIEW)`; existing cooperatives get targeted `INSERT ... ON CONFLICT DO NOTHING`, never a re-run |
| Possible index | `charge (tenant_id, adjusts_charge_id) where adjusts_charge_id is not null`, added only if the effective-amount join plan needs it on the 300 plots x 12 months fixture |

The tax slot is a fixed entry in code, merged first in the list as "not added" until a row with `slot = 'TAX'` exists;
a new upload into an archived slot is allowed. `content` is never loaded by list queries. Demo data:
`db/demo/R__demo_report_templates.sql` loads the sample "Land tax statement" via `decode(base64)` for the demo tenant,
with `ON CONFLICT DO NOTHING`, plus fake requisites with a mod-97-valid IBAN in the demo settings; the sample xlsx is
generated by a script kept in the repository.

### 2.5 API (`api/openapi.yaml`, tag `reports`)

| Endpoint | Guard |
|---|---|
| `GET /reports` | `PERM_REPORTS_VIEW`, list filtered by each report's required permissions |
| `GET /reports/{code}`, `GET /reports/{code}.xlsx` (+ optional `lang`) | `PERM_REPORTS_VIEW`; Debtors also `PERM_MEMBERS_VIEW` (403 otherwise, checked in the runner and with `@PreAuthorize` on the definition) |
| `GET /report-templates`, `GET /report-templates/catalog` | `REPORTS_VIEW` or `REPORT_TEMPLATES_MANAGE` |
| `POST /report-templates`, `POST /{id}/versions` (multipart), `POST /{id}/archive`, `GET /{id}/versions/{versionId}/file` | `PERM_REPORT_TEMPLATES_MANAGE` |
| `POST /report-templates/{id}/generate` (`from`, `to`, `lang`) | `PERM_REPORTS_VIEW`; archived template is 409 `TEMPLATE_ARCHIVED` |
| `GET\|PUT /settings` | requisites added; `edrpou` read-only in the response |

Problem codes: `REPORT_TOO_LARGE` (422), `REPORT_FILTER_INVALID`, `TEMPLATE_INVALID`, `TEMPLATE_TOO_LARGE`,
`TEMPLATE_ARCHIVED`, `IBAN_INVALID` (top-level 400 with field error `iban`). `Permission` gets `REPORTS_VIEW` and
`REPORT_TEMPLATES_MANAGE`; `AuditAction` gets `REPORT_GENERATED`, `REPORT_TEMPLATE_UPLOADED`, `REPORT_TEMPLATE_ARCHIVED`;
`AuditEntityType` gets `REPORT` and `REPORT_TEMPLATE`. Both the preview and the file are audited
(`output = PREVIEW | XLSX`), with filters as ids, codes and dates, row count and a personal-data flag, never contents.

### 2.6 Web

Routes `reports` (list), `reports/templates` (declared before `reports/:code`), `reports/:code`, gated by
`RequirePermission`; a code absent from `GET /reports` renders the no-access view. Menu item "reports" with
`REPORTS_VIEW`. Components: `ReportsPage`, `ReportPage` (generic `ReportFilterForm` driven by the server's filter
definitions, filters in URL search params, `ReportPreviewTable` with unknown cells shown empty with the reason as a
title, totals as received, row-count note), `ReportTemplatesPage` (`TaxSlotCard`, `TemplateList`,
`TemplateUploadForm` with `reportTemplateFile.ts` pre-validation, `GenerateDialog` with warnings, `FieldCatalog` with
search and copy), `RequisitesPanel` in settings (IBAN, read-only ЄДРПОУ). Download through `downloadBlob`; errors mapped
by a `reportError.ts` helper. Links from the billing months screen and the plot account. i18n namespaces `reports`,
`reportTemplates`, `settings.requisites` in both catalogs; new screens added to the language-purity spec.
`packages/api-client/src/generated` is regenerated, never edited. No new frontend dependencies.

### 2.7 Documentation

`reports` module README, ADR 0015 (template engine), ADR 0012 note (guard moved to `xlsx`), ADR 0009 permission list,
`settings/README.md`, `money-rules.md` section 12 "Reports", glossary entries, `context/product/architecture.md` if
modules are listed, both guides in uk and en (board guide chapter 21 to "works", owner manual tables and permissions,
`capture-screens.mjs` with `reports`, `report-electricity`, `report-templates`), PDFs rebuilt with `make guide-shots` and
`make guide`.

---

## 3. Impact and Risk Analysis

- **System dependencies:** seven modules gain small public read facades; `imports` is rewired to the `xlsx` guard
  (behaviour unchanged, `ZipGuardTests` move with it); `Permission`, `BaseRoles`, `PermissionCatalog` and the OpenAPI
  enums change; `seed_base_roles` is re-created again; `ModularityTests` must stay green.
- **Risks and mitigations:**
  - Formula and reference shifting in the own filler is the largest risk: golden-file tests, XML assertions, round-trip
    reading with fastexcel-reader, an early spike, POI as the recorded fallback.
  - JaCoCo floor 0.974: the filler and shifter need near-complete unit coverage.
  - Totals drifting from the billing months screen: charges-by-month reuses the same predicate and has a reconciliation
    test for every month.
  - Read-only transaction breaking the outbox: runner uses a normal transaction and publishes the audit event last.
  - Personal data: Debtors reads only through `MemberDirectory`, audited, never logged; template fields exclude names and
    contacts.
  - Performance: aggregate in SQL, chunk bulk lookups at 500, no N+1; a 300 plots x 12 months fixture must finish in
    under 5 s; check plans with EXPLAIN before adding any index.
  - Malicious templates: size, entry count, ratio, magic bytes, macro and external-link refusal, XXE-safe parser,
    no formula evaluation.
  - `/reports/{code}.xlsx` must win over `/reports/{code}`: covered by a test.
  - With a charge-type filter, today's status still comes from the all-types cache; the header says so.
  - "Oldest unpaid charge" is the oldest charge until payments exist (phase 3); documented in the report header.

---

## 4. Testing Strategy

- **Unit:** report definitions on fixed ledger fixtures with worked numbers (reversal, later-month adjustment,
  proportional losses run, common consumer run, part-month water connection, meter replacement); `Iban` mod 97;
  `FormulaShifter`; `TemplateFiller` (typed vs text cells, repeating rows, unknown placeholders, rich text, macros,
  external links, zip bomb, DOCTYPE); catalog enum vs bundles vs catalogs.
- **Integration (Testcontainers):** each endpoint allowed and denied (Debtors without `MEMBERS_VIEW` is 403, operator
  403, member and electrician 403); audit entries; `REPORT_TOO_LARGE`; preview vs file equality; charges-by-month vs
  billing months for every month; electricity charged vs ledger; isolation tests for both new tables plus privilege and
  constraint tests; migration test of V20 over V19 with existing rows; `BaseRolesMatrixTests` and `SeedBaseRolesTests`;
  300 x 12 performance test (< 5 s); `ModularityTests`.
- **Web (vitest):** pages listed in 2.6, file pre-validation, requisites panel, menu item, catalogs parity.
- **E2E (Playwright):** `reports.spec.ts` (`@smoke`: chairman opens and downloads the electricity report),
  `report-templates.spec.ts` (generate the demo land tax statement), `reports-acceptance.spec.ts` (custom role without
  `MEMBERS_VIEW` does not see Debtors and gets no access by URL; Treasurer sees it; member and electrician have no menu
  item); screens added to `language-purity.spec.ts`.
- **Verification levels:** slices run only their own narrow tests (backend `-Dtest` patterns, client vitest files);
  the full `make check` and `make e2e` run once in Phase 4.
