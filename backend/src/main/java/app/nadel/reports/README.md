# reports

Built-in reports over what the app has booked: charges by month, plot balances, debtors, electricity by the meter tree,
water, missing readings, land tax and corrections. Each is shown as an on-screen preview and downloaded as an Excel
file; report templates follow. Requirements: `docs/features/013-reports.md`; specs: `context/spec/013-reports/`.

The module adds no money rule. It reads only through the public read facades that the owning modules publish
(ledger, meters, plots, members, tariffs, settings, tenancy) and never reads their tables (ADR 0001).

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /reports` | `listReports` | `REPORTS_VIEW`; the reports the caller may run with their filter definitions (`debtors` only with `MEMBERS_VIEW` too) |
| `GET /reports/{code}` | `getReport` | `REPORTS_VIEW` (`debtors` also `MEMBERS_VIEW`); the preview: per sheet the columns, the first 200 rows, the totals and the row count |
| `GET /reports/{code}.xlsx` | `downloadReport` | as `getReport`; the workbook with every row, texts in `lang` (`uk`/`en`), else `Accept-Language`, else `uk` |

Every endpoint is guarded with `@PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")`, so a member, the electrician and
the platform operator get 403 for every code, defined or not, before anything else is checked. `{code}` is a
`ReportCode` (`charges-by-month`, `plot-balances`, `debtors`, `electricity-tree`, `water`, `missing-readings`,
`land-tax`, `corrections`); `ReportCodeConverter` reads the kebab-case value, and an unknown code is 400
`MALFORMED_REQUEST` (as every unknown enum value). A code without a definition yet is 404 `NOT_FOUND`; a defined report
that needs a permission the caller lacks is 403 `PERMISSION_DENIED` (`ReportCatalog.require`) and is not listed.
`/reports/{code}.xlsx` wins over `/reports/{code}`: its pattern is the more specific one
(`ChargesByMonthReportTests.theXlsxPathWinsOverTheCodePath`).

Done so far: `charges-by-month`. The other codes are 404 until their definitions arrive.

## Framework (`internal`)

| Type | Role |
|---|---|
| `ReportDefinition` | One bean per report: `code`, `requiredPermissions` (default `REPORTS_VIEW`; debtors adds `MEMBERS_VIEW`), `personalData`, `filters` (the period first), `load(filters, context)` returning a `ReportResult`, `filenameRange` (default: the period of the filters) |
| `ReportCatalog` | The definitions in the order of `ReportCode`; `available`/`infos` filter by the caller's permissions, `require` is 404 or 403 |
| `ReportFilterDefinition` | One filter: key, type, query parameters, required, basis, defaults on a day, `apply` (read the parameters into `ReportFilters`, adding field errors) and `describe` (the header block's text). Implemented: `MonthRangeFilter` (`period`, `fromMonth`/`toMonth`, default the previous month), `ChargeTypesFilter` (`chargeTypes`, default all) and `StreetFilter` (`street`, default all); later reports add theirs |
| `ReportFilters` | The typed filter values of one run and the values as the query string carries them (echoed in the preview, written to the audit entry); `parse` throws 400 `REPORT_FILTER_INVALID` with every field error: `REQUIRED` (a required filter without a default), `INVALID` (`toMonth` before `fromMonth`), `NOT_FOUND` (`chargeTypeIds[i]` not a charge type of the cooperative). Malformed values never get here: the contract answers 400 `VALIDATION_FAILED` |
| `ReportResult` | Sheets (`key`, typed `Column`s, `Row`s of kind `DETAIL`/`SUBTOTAL` with a tree level, `Total`s) and header `Note`s (code + params). A `Cell` holds a typed value (`Money`, `BigDecimal`, `LocalDate`, `YearMonth`, `UUID` of a charge type, codes and text) or nothing, or a reason when the value is not known (never 0) |
| `ReportRunner` | `preview` and `file`, each one plain `@Transactional` (not read-only: the outbox inserts the audit publication). Order: caller and permission, filters, `load`, the row cap, the preview or the workbook, the audit event **last**, so a refused run or a failure while writing leaves no entry |
| `ReportLimits` | The row cap, 20 000 per sheet (property `nadel.reports.row-cap`), and the 200 preview rows |
| `XlsxReportWriter` | fastexcel workbook, one worksheet per sheet |
| `ReportLanguage`, `ReportTexts` | The file's language (`lang`, else `Accept-Language`, else `uk`, as `imports.internal.TemplateLanguage`) and its texts |
| `ReportAuditPublisher` | The `REPORT_GENERATED` entry |
| `ReportsController` | The three endpoints, generated `ReportsApi` |

### The preview

`ReportPreview`: `code`, `generatedAt` (Europe/Kyiv offset), `filters` (the values used, defaults filled in),
`headerNotes`, `chargeTypes` (every type a cell, a column or the filter names, in configured order, as
`ChargeTypeRef`) and per sheet the columns, the first 200 rows, the totals over **all** rows, `rowCount` and
`truncated`. Cells are strings formatted by column type: money with two decimals (`Money.toString`), quantities and
percentages as given (`toPlainString`), ISO days, `YYYY-MM` months, codes and charge type ids. Totals are sums of the
rows as listed (`Money.plus`, no re-rounding); a column without a total is empty.

### The file

One worksheet per sheet; name from `reports.<code>.sheets.<key>`, made Excel-safe (no `[]:*?/\`, at most 31
characters, unique). Rows 1-5 are the header block: the cooperative's name (`TenantDirectory`), the report's name, the
filters (`Filters: <label>: <value>; ...`, months as `MM.yyyy`), when it was made (`dd.MM.yyyy HH:mm`, Kyiv) and the
caller's display name. Then one line per header note, an empty line, the column headers (frozen below), every row
and the totals. Money is a numeric cell with `#,##0.00`, quantities `#,##0.000`, percentages `0.00`, integers
`#,##0`, days dates `dd.mm.yyyy`, months the date of their first day as `mm.yyyy`. A `CODE` column writes
`<codes>.<VALUE>` from the bundle (the column names its catalog, for example `billing.status`); a charge type is its
stored name when customised, otherwise `chargeTypes.defaultNames.<CODE>`. A subtotal and a totals line are bold and
write their label (`reports.rows.SUBTOTAL`, `reports.totals.<key>`) into their first empty text column; a tree row is
indented by its level; an unknown value is an empty cell with `reports.reasons.<REASON>` as a comment. File name
`<code>_<from>_<to>.xlsx` (ASCII, `Content-Disposition`), from `filenameRange`: a month range gives the first day of
`fromMonth` and the last day of `toMonth`.

### Bundles

`src/main/resources/reports/messages_uk.properties` and `messages_en.properties` (UTF-8): generated files need text, so
these are the ADR 0012 exception to the English-only rule (Ukrainian only in the `uk` bundle). Every key is the path of
the same text in the web catalogs (`packages/shared/src/i18n/uk.ts`, `en.ts`), so screen and file say the same:
`reports.<code>.title`, `reports.<code>.sheets.<sheetKey>`, `reports.<code>.columns.<columnKey>` (the contract's
column keys), `reports.notes.<ReportNoteCode>`, `reports.filters.*`, `reports.file.*`, `reports.rows.SUBTOTAL`,
`reports.totals.<key>`, `reports.reasons.<ReportCellReason>`, and the shared `billing.status.*` and
`chargeTypes.defaultNames.*`. `ReportTextsTests` checks that both bundles have the same keys and every key a
definition asks for; the web side checks the texts against its catalogs.

### Row cap

A sheet of more than 20 000 rows is 422 `REPORT_TOO_LARGE` ("The report is too large. Narrow the filters."), checked
after `load` and before anything is written or audited. A facade that can return many rows takes
`ReportContext.rowLimit()` (cap + 1) as its limit, so it never loads more than one row past the cap. The cap counts
every row of a sheet, subtotals included. It is `nadel.reports.row-cap` (default 20 000); integration tests lower it
for one test with `ReportLimits.rowCap(int)` and restore `DEFAULT_ROW_CAP` in a `finally`, which keeps the shared
application context.

### Audit

Every preview and file is one `REPORT_GENERATED` entry, entity type `REPORT`, entity id `UUID.nameUUIDFromBytes(
"report:<code>")` (the same for every run of a report, so the log filters by report), label the code. Fields, new
values only: `report`, `output` (`PREVIEW`/`XLSX`), `filters` (JSON array of `{param, value}`: ids, codes, months,
days and the street as typed), `rows` (all sheets) and `personalData`. Never the contents, never logged.

## How to add a report

1. Give the owning modules the read facades it needs (public, `Propagation.MANDATORY` or caller's transaction, ids,
   codes and `Money` only, a `limit` for anything that can grow with the plots).
2. Add a `@Component` implementing `ReportDefinition` in `internal` with its filters (add a
   `ReportFilterDefinition` for a new filter type and a typed field to `ReportFilters`), its sheets and columns
   (keys of the contract), totals as `Money` sums of the listed rows and its header notes.
3. Add `reports.<code>.title`, `.sheets.*`, `.columns.*` and any new notes, codes and filter labels to both bundles
   and to both web catalogs with the same texts.
4. Tests: the definition on a fixture with worked numbers through the API (preview and file), its filters, its
   permission (a definition with `MEMBERS_VIEW` is 403 and not listed without it), and `ReportTextsTests` picks up the
   keys when the definition is added to its list.

## Report: charges by month (`charges-by-month`, functional spec 2.3)

Filters: billing months (`period`, `MONTH_RANGE`, basis `BILLING_MONTHS`, default the previous month in Kyiv), charge
types (default all), street (optional; `plots.PlotCatalog.idsOnStreet`, archived plots included; no plot on the street
gives no rows). Reads `ledger.LedgerReadModel.monthTotals` twice.

- Sheet `byBillingMonth`: per billing month and charge type with charges: `month`, `status` (`OPEN`/`CLOSED`),
  `chargeType` (configured order), `plots` (distinct plots with a row), `regular`, `opening`, `reversals`,
  `adjustments`, `effective`; a `SUBTOTAL` row after each month (no type, no plot count: a plot can have several
  types) and the totals `all`. The effective total of a month equals the billing months screen cent for cent (same
  predicate).
- Sheet `byServicePeriod` ("За період послуг" / "By service period"): the same columns without `status`, grouped by the
  month of `service_from` over the same months, so an adjustment booked in March for January is under January; the
  header note `SERVICE_PERIOD_SHEET` says so. A charge whose service starts before the range (an electricity interval
  from the previous month) is outside this sheet.

Tests: `ChargesByMonthReportTests` (worked ledger in a cooperative of its own, `ReportsTestSupport`: reconciliation with
`GET /billing-months` for every month, reversal, later-month adjustment on both sheets, street and charge type filters,
defaults, the list, the workbook in both languages, route precedence, filter errors, the cap, the audit entries),
`ReportsAccessTests` (401/403 matrix on the demo cooperative), unit tests `ReportFiltersTests`, `ReportCatalogTests`,
`ReportRunnerTests` (preview of 200 of 250 rows, file with all, no audit on a write failure, the cap or bad filters),
`XlsxReportWriterTests`, `ReportLanguageTests`, `ReportTextsTests`.

## Depends on

`ledger` (`LedgerReadModel`), `plots` (`PlotCatalog`), `tariffs` (`ChargeTypeCatalog`), `tenancy`
(`TenantDirectory`), `identity` (`CurrentUser`, `AccessProfile`, `Permission`), `audit`, `money` and `problem`. No
module depends on `reports`.
