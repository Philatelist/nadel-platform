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

Every code of `ReportCode` has its definition: `charges-by-month`, `plot-balances`, `debtors`, `electricity-tree`,
`water`, `missing-readings`, `land-tax` and `corrections`. `GET /reports` runs in a read-only transaction: the debtors' defaults are settings of
the cooperative.

## Framework (`internal`)

| Type | Role |
|---|---|
| `ReportDefinition` | One bean per report: `code`, `requiredPermissions` (default `REPORTS_VIEW`; debtors adds `MEMBERS_VIEW`), `personalData`, `filters` (the period first), `load(filters, context)` returning a `ReportResult`, `filenameRange(filters, context)` (default: the period of the filters; a report for one day gives that day twice) |
| `ReportCatalog` | The definitions in the order of `ReportCode`; `available`/`infos` filter by the caller's permissions, `require` is 404 or 403 |
| `ReportFilterDefinition` | One filter: key, type, query parameters, required, basis, defaults on a day, `apply` (read the parameters into `ReportFilters`, adding field errors) and `describe` (the header block's text). Implemented: `MonthRangeFilter` (`period`, `fromMonth`/`toMonth`, default the previous month), `DateRangeFilter` (`period`, `DATE_RANGE`, `from`/`to`, default the first and last day of the previous month in Kyiv), `ChargeTypesFilter` (`chargeTypes`, default all), `StreetFilter` (`street`, default all), `AsOfFilter` (`asOf`, `DATE`, required, default today; a day after today is `INVALID`), `StatusesFilter` (`statuses`, `STATUS`, default all), `MinDebtFilter` (`minDebt`, `MONEY`) and `OlderThanDaysFilter` (`olderThanDays`, `INT`), both required with a default the definition gives (the debtors: the cooperative's red amount and red days, read when the list or the report is made), `YearFilter` (`year`, `YEAR`, required, default the current year in Kyiv; a year after it is `INVALID`), `CorrectionKindsFilter` (`correctionKinds`, `CORRECTION_KIND`, default both), `WaterSourceFilter` (`waterSource`, `WATER_SOURCE`, default every source), `ResourceFilter` (`resource`, `RESOURCE`, default both) and `MeterKindsFilter` (`meterKinds`, `METER_KIND`, several, default `PLOT`, listed as the filter's defaults). In the file a filter is named by `reports.filters.<key>` (period, charge types, street) or `reports.filters.params.<param>`, the web form's labels |
| `ReportFilters` | The typed filter values of one run and the values as the query string carries them (echoed in the preview, written to the audit entry); `parse` throws 400 `REPORT_FILTER_INVALID` with every field error: `REQUIRED` (a required filter without a default), `INVALID` (`toMonth` before `fromMonth`, `to` before `from` or a range of days of more than one year, `asOf` after today, `year` after the current year), `NOT_FOUND` (`chargeTypeIds[i]` not a charge type of the cooperative). Malformed values never get here: the contract answers 400 `VALIDATION_FAILED` |
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
percentages and `DECIMAL` (rates and areas, never rounded) as given (`toPlainString`), ISO days, `YYYY-MM` months, codes and charge type ids. Totals are sums of the
rows as listed (`Money.plus`, no re-rounding); a column without a total is empty.

### The file

One worksheet per sheet; name from `reports.<code>.sheets.<key>`, made Excel-safe (no `[]:*?/\`, at most 31
characters, unique). Rows 1-5 are the header block: the cooperative's name (`TenantDirectory`), the report's name, the
filters (`Filters: <label>: <value>; ...`, months as `MM.yyyy`), when it was made (`dd.MM.yyyy HH:mm`, Kyiv) and the
caller's display name. Then one line per header note, an empty line, the column headers (frozen below), every row
and the totals. Money is a numeric cell with `#,##0.00`, quantities `#,##0.000`, percentages `0.00`, integers
`#,##0`, `DECIMAL` `#,##0.00##` (a rate 35.0000 shows 35.00, 1.4532 all four decimals), days dates `dd.mm.yyyy`, months the date of their first day as `mm.yyyy`. A `CODE` column writes
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
`reports.totals.<key>`, `reports.reasons.<ReportCellReason>`, the filter labels and options
(`reports.filters.params.*`, `reports.filters.options.statuses.*`, which also name the plot balances' status codes,
`reports.filters.options.correctionKinds.*`, which also name the corrections' kinds,
`reports.filters.options.meterKinds.*`, which also name the electricity tree's node kinds and the missing readings'
kinds, `reports.filters.options.resource.*`, which also name the missing readings' resources,
`reports.filters.options.waterSource.*`, which also name the water report's sources), the corrections' sources
(`reports.corrections.sources.*`), the electricity report's codes (`reports.electricity-tree.valueKinds.*`,
`.sources.*`, `.rowKinds.*`, `.runStatuses.*`, `.outcomes.*`, `.splits.*`), and the shared `billing.status.*`, `chargeTypes.defaultNames.*` and
`recalculation.causeKind.*` (the corrections' recalculation causes). On the web `ReportPreviewTable.codeLabels` maps a
`CODE` column (`<report>/<column key>`) to the same catalog. A column per charge type (`debt.<chargeTypeId>`,
`chargeTypeId` set) has no key of its own: its header is the charge type's name, in the file
`chargeTypes.defaultNames.<CODE>` for a seeded type that was not renamed and the stored name otherwise (the runner's
`chargeTypeNames`), on the web `chargeTypeName`. `ReportTextsTests` checks that both bundles have the same keys and every key a
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

## Debt per charge type (plot balances and debtors)

`DebtColumns`: one `MONEY` column per charge type in the configured order, key `debt.<chargeTypeId>` with
`chargeTypeId` set, headed by the charge type's name (see Bundles). With a charge type filter the columns are the chosen
types; without one every type that is not archived, plus an archived type some listed plot still has a counted charge
of. A plot without a counted charge of a type shows `0.00`. The debts are `ledger.LedgerReadModel.debtsAsOf` (the
rule of the plot account and the status cache: charges dated on or before the day, reversals negative, adjustments
with their sign; the oldest unpaid date and the days overdue on that day). The totals line `all` sums every money column
of the rows listed (`Money.plus`, no rounding of its own); other columns are empty. Both reports add the header note
`OLDEST_UNPAID_IS_OLDEST_CHARGE` (until payments exist the oldest unpaid charge is the oldest charge with a debt) and
read the plots from `plots.PlotCatalog.findActive(street)`: active plots without common land, natural order.

## Report: plot balances (`plot-balances`, functional spec 2.4)

Filters: as of (`asOf`, default today in Kyiv, not after today), charge types, street, statuses (`RED`, `YELLOW`,
`GREEN`, `GREY`). Needs `REPORTS_VIEW` only and holds no personal data. Sheet `main`, one row per active plot:
`number`, `street`, `owners` (the **number** of current owners, `plots.PlotOwners.ownerCounts`; never a name), the debt
columns, `total`, `oldestUnpaid`, `daysOverdue`, `status` (`CODE`, in the file `reports.filters.options.statuses.*`).

- The status always counts every charge type. As of today it is the status on the map (`plots.PlotStatuses.current`,
  the `plot_status` cache, the current thresholds). For a past day it is `PlotStatuses.classify` of every type's debt
  on that day with today's thresholds (and today's owners and kind); the header note `PAST_DATE_STATUS` says so.
- With a charge type filter that leaves out some type the debts and dates count only the chosen types, the status still
  every type, and the header note `STATUS_ALL_CHARGE_TYPES` says so.
- The status filter applies after the status is worked out; the totals are the sums of the rows it keeps.
- File name `plot-balances_<asOf>_<asOf>.xlsx`.

## Report: debtors (`debtors`, functional spec 2.5)

Needs `REPORTS_VIEW` **and** `MEMBERS_VIEW` (`requiredPermissions`): without the second it is not in `GET /reports`
(so the web shows no card) and both its endpoints are 403 `PERMISSION_DENIED`; `load` also carries
`@PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') and hasAuthority('PERM_MEMBERS_VIEW')")` as defence in depth.
`personalData` is true: its `ReportInfo` and audit entries say `personalData=true`, and the entry holds the filters
and the row count only, never a name, phone or e-mail. Nothing logs them.

Filters: `minDebt` (default the cooperative's red amount) and `olderThanDays` (default its red days), charge types,
street; the day is today in Kyiv. A plot matches by the red rule of the map (money rules 9, `PlotStatusPolicy`) with the
filter's thresholds: no debt (zero or less) never matches; otherwise the debt is **above** `minDebt` **or** its oldest
unpaid charge is **more than** `olderThanDays` days old. Debt and age count only the chosen charge types. A matching
plot without a current owner is listed with empty owner cells (its debt is still owed), so with the defaults and every
type the report lists the red plots plus the ownerless plots that would be red.

Sheet `main`, one row per matching plot: `number`, `street`, `owners` (the full names "Last First Middle" of the
current owners from `members.MemberDirectory.contacts`, the earliest ownership first, separated by `; `), `phones` (per
owner its phones joined by `, `, the owners in the same order separated by `; `; an owner without a phone is left
out), `emails` (the owners' e-mail addresses in the same order separated by `; `; an owner without one is left out),
the debt columns, `total`, `oldestUnpaid`, `daysOverdue`. File name `debtors_<today>_<today>.xlsx`.

Tests: `PlotBalancesReportTests` and `DebtorsReportTests` on the worked balances of
`ReportsTestSupport.bookTheBalances` (five active plots, common land and an archived plot left out, two current owners
and an ended one on plot 1, dates counted back from today): debts per type, totals, dates, days and every status; the
map's status today; a past day without the later charges and with today's thresholds; status, street and charge type
filters and their notes; no name in the plot balances preview or file; the debtors' OR rule, defaults from the
settings, both owners with contacts, a lower amount listing more plots; 403 and no card for a custom role with
`REPORTS_VIEW` but not `MEMBERS_VIEW` (allowed with both), the definition's own refusal; the audit entries.

## Report: land tax (`land-tax`, functional spec 2.9)

Filter: the year (`year`, default the current year in Kyiv, not after it). Needs `REPORTS_VIEW` only, no personal data.
The money rule stays in tariffs and ledger; the definition only lays the two side by side:

- **Expected**: `tariffs.ExpectedAmounts.yearByVersion("LAND_TAX", year, plots)`: month by month as the month run
  charges (the tariff `TariffResolver` names, `ChargeCalculator` over every day of the month, rounded once per month),
  one share per tariff version with its days; the shares of a plot add up to its yearly amount exactly. 35.00 per
  sotka per year on 600 m² is 6 sotkas and 17.50 a month, 210.00 a year.
- **Charged**: `ledger.LedgerReadModel.chargedInYear("LAND_TAX", year, plots)`: the effective amount (charges plus
  adjustments minus reversals) of the land tax charges whose service period starts in the year, whenever they were
  booked; opening balances left out.

Sheet `main`: the active plots without common land (`PlotCatalog.findActive`, natural order) that the land tax charges
on some day of the year; a plot outside every tariff's scope, excluded, archived or without a version in the year is
not listed (even if something was charged to it). One row per tariff version, oldest first: `number`, `street`,
`areaM2`, `areaSotka` (exact, `DECIMAL`), `rate` (per sotka per year, `DECIMAL`, as entered), `from`, `to`, `days`,
`expected` (the version's share), `charged`, `mismatch` (`BOOLEAN`). The plot's first row carries the area, the
charged amount and the mark; a continuation row (another rate in the year) repeats number and street and leaves area,
`charged` and `mismatch` empty, so each amount is listed once. `mismatch` is true when the plot's expected total (the
sum of its shares) is not its charged amount. The totals `all` sum `expected` and `charged` of the rows listed. With
at least one mark the header note `RECALCULATION_SUGGESTED` (param `count`: plots marked) suggests a recalculation
(feature 012); the report books nothing. File name `land-tax_<year>-01-01_<year>-12-31.xlsx`.

## Report: corrections (`corrections`, functional spec 2.10)

Filters: billing months (`period`, default the previous month), charge types (default all), kinds of correction
(`correctionKinds`, default both). Needs `REPORTS_VIEW` only. Reads `ledger.LedgerReadModel.corrections` with the row
cap plus one as its limit, the plot numbers from `PlotCatalog.findAll` (500 ids a call, archived plots included) and
the authors' names from `MemberDirectory.find` (one call for the distinct authors).

Sheet `main`, one row per reversal or adjustment booked in the months, in the ledger's order (billing month, time
booked, id): `bookedOn` (the Kyiv day), `month` (the billing month), `number`, `chargeType`, `kind` (`REVERSAL`,
`ADJUSTMENT`), `originalFrom`, `originalTo`, `originalAmount` (the service period and booked amount of the corrected
charge), `amount` (signed as booked), `source`, `cause`, `reason`, `bookedBy` ("Last First" of the member; empty
without an author). Sources (`ledger.CorrectionSource`): `MANUAL_REVERSAL` (one charge reversed by hand),
`RECALCULATION` (every adjustment and the reversals a recalculation books), `RUN_REVERSAL` (the charges of a losses or
common consumer run reversed with the run). `cause` is the recalculation's cause (`MANUAL`, `TARIFF_VERSION`,
`READING`) and only set for `RECALCULATION`; `reason` is the reason booked with the correction (the recalculation's,
or the one given for the reversal), user content shown as entered. The totals `all` sum `amount` only (original
amounts would count a charge twice when it was corrected twice). No corrections in the range: the sheet has no rows
and no totals line; the screen says there is nothing to show, the file has the header block and column headers only.

Tests: `LandTaxReportTests` and `CorrectionsReportTests` (worked fixtures in a cooperative of their own, preview and
file, filters, permission, audit), `ReportFiltersTests` (year and kinds), `ReportTextsTests`.

## Report: electricity by meter tree (`electricity-tree`, functional spec 2.6)

Filter: service days (`period`, `DATE_RANGE`, `from`/`to`, basis `SERVICE_DAYS`, default the previous calendar month
in Kyiv; `to` before `from` or more than one year after it, `to` on or after the same day a year later, is `INVALID`
on `to`: a year is the longest period a board reports on and it bounds the tree's work). Needs `REPORTS_VIEW` only, no
personal data. Two sheets, made from `meters.MeterReports.tree` (the calculation of `GET /meter-tree`) and
`ledger.LedgerReadModel.electricityCharged` (the row cap plus one as its limit), `lossesRunsInRange` and
`commonConsumerRunsInRange`; plot numbers and their natural order from `PlotCatalog.findAll` and `inNaturalOrder` (one
call each: the distinct plots of the rows). Every kWh is written with three decimals by `meters.Quantity.toDecimal`
(the one display rounding of a quantity, with the sign, so negative losses and reversed kWh show as such; ADR 0014);
the report rounds nothing.

- Sheet `consumption` ("Споживання"): one row per node under each parent it had in the range, in the order of the
  tree, `level` its depth (the indent of the first column on screen and in the file): `name` (the meter's name, a plot
  meter's plot number), `nodeKind` (`PLOT`, `SUBSTATION`, `QUARTER`, `COMMON_CONSUMER`), `serial` (the meter shown: the
  newest of a replaced chain), `start` and `end` (the value on the first day of the node's first slice and on the last
  day of its last slice; after a replacement the start is read off the old meter and the end off the new one) each with
  `startKind`/`endKind` (`REAL`, `CALCULATED`, `UNKNOWN`) and `startLastReading`/`endLastReading` (the day of the last
  reading, only when unknown), `consumption`, `children` (the sum of the children's consumption), `losses` (kWh, may be
  negative) and `lossesPercent` (of the node's consumption, two decimals as the meters module gives it; empty when the
  consumption is not above zero), the last three only for substations and quarter meters, and `plotsFed` (the distinct
  plots of the plot meters in its subtree). A value not known is empty with `NO_READING`; every figure worked out from
  one is empty with `DERIVED_FROM_UNKNOWN`, never 0. No totals: the levels of a tree do not add up. A node that left a
  parent and came back within the range has several slices there: its row shows the first start and the last end, and
  its consumption counts only its days under that parent. The number of members fed is not shown: the meters module
  knows plots, not owners (spec 2.6 asks for plots fed).
- Sheet `charged` ("Нараховано"): the electricity ledger rows whose service period overlaps the range, in the sections
  `CONSUMPTION`, `LOSSES`, `COMMON_CONSUMER`, each closed by a `SUBTOTAL` row (`kwh`, `amount`). In a section every
  regular charge is followed by its reversals and adjustments, each its own row (`rowKind` `REGULAR`, `REVERSAL`,
  `ADJUSTMENT`) with the **original** charge's service period and its own billing month; groups in the natural order of
  the plot number, then by service period. Columns: `source`, `rowKind`, `number` (the plot), `serviceFrom`,
  `serviceTo`, `month`, `kwh` (signed: a reversal negative, an adjustment the change it makes), `rate` (the price per
  kWh when every tariff part of the row has the same rate; empty with `SEVERAL_RATES` when the rate changed within the
  days, the amount being exact; empty when the row has no parts), `amount`. Losses without a run (the fixed
  percentage of an interval) come first; then, per losses run of the range (`lossesRunsInRange`, booked or reversed, as
  booked), one `RUN` row per node (`number` the node's name as the run showed it, `serviceFrom`/`serviceTo` the run's
  days, `month`, `runStatus`, `outcome`, `consumption`, `children`, `losses`, `lossesPercent`, `runAmount` (what the run
  booked for the node), `plots` (plots charged)) followed by the run's charges; the same for the common consumer runs
  (`split`, `consumption`, `runAmount`, `plots` (plots that shared it), `plotDays`). A reversed run is listed with
  `REVERSED`, its charges and their reversals, which add up to zero. The figures of a `RUN` row stay out of `kwh` and
  `amount` (a node's `runAmount` prices its share on its own, so it need not add up to the plots' charges): those two
  columns hold only ledger rows, and the sum of `amount` over the sheet is the electricity and common consumer charges
  of the range on the plot accounts. Whatever mode a charge or run was booked under, it shows what it booked.
- Totals of `charged`: `CONSUMPTION` (kWh used by plots and their amount), `LOSSES` (kWh of losses charged),
  `COMMON_CONSUMER` (kWh of common consumers charged), each with `kwh` and `amount`, and `all` (the amount); sums of the
  rows as listed (the displayed kWh, `Money.plus`). No rows: no totals.
- Header note: `LOSSES_FIXED_PERCENT` (param `percent`, two decimals) or `LOSSES_PROPORTIONAL`, the cooperative's mode
  now (`CooperativeSettings`).
- The row cap counts each sheet, as for every report (the contract's rule): the charged sheet's rows include the `RUN`
  and `SUBTOTAL` rows. File name `electricity-tree_<from>_<to>.xlsx`.

Tests: `ElectricityTreeReportTests` (the worked tree of `meters.internal.MeterReportsTests` with a meter replacement
and an unknown quarter, its ledger with fixed-percentage losses, a mixed-rate charge reversed, a later adjustment and a
common consumer run; the proportional runs booked, reversed and booked again through the API; the reconciliation with
the plot accounts, preview and file number for number, defaults and filter errors, the cap, the audit entries, 403 for
a member and the electrician), `ReportFiltersTests` (the range of days), `ReportTextsTests`.

## Report: water (`water`, functional spec 2.7)

Filters: service days (`period`, `DATE_RANGE`, basis `SERVICE_DAYS`, default the previous calendar month, at most a
year, as the electricity report), the kind of water source (`waterSource`: `METER`, every well, shown as "Well", or
`COOPERATIVE_SUPPLY`; default all) and the street (`PlotCatalog.idsOnStreet`). Needs `REPORTS_VIEW` only, no personal
data. Reads `meters.MeterReports.waterUse` (always for every source) and `ledger.LedgerReadModel.waterCharged` for the
plots of its rows, with the row cap plus one as the limit; the plot numbers' natural order from
`PlotCatalog.inNaturalOrder`. Every m³ is `Quantity.toDecimal` (three decimals); nothing is rounded here.

- Sheet `main` ("Вода за ділянками"): one row per plot and source it was connected to on at least one day of the range
  (a plot that changed source has two rows; archived plots and common land too when connected), plots in natural
  order, a plot's rows by their first connected day. Plots not connected on any day are not listed, whatever was
  charged to them (money rules 11). Columns: `number`, `street`, `source` (`CODE`), `sourceName` (the well's meter
  name), `connectedDays`, `serial` (the plot's water meter shown), `cubicMetres` (the range consumption of 010 on the
  connected days only; empty with `NO_READING` when a value is not known, with `lastReading` the day after which a
  reading is missing; empty without a reason when the plot has no water meter), then the water charges whose service
  period overlaps the range, each the net of its regular charges, reversals and adjustments: `consumption` (by meter),
  `fixedFee` (the month run's tariff charge, `ChargedSource.TARIFF`), `pumpShare` (a well pump's `COMMON_CONSUMER`
  share), `corrections` ("of which reversals and adjustments", every kind) and `total` (the three kinds).
- Attribution of a charge to a row of its plot: a pump share to the row of the well its calculation names; any other
  charge to the row whose connected days overlap the charge's days within the range most (the first row on a tie or
  without any overlap). It is made over every source before the source filter applies, so a plot listed for one
  source never shows the charges of its other one.
- Sheet `summary` ("За джерелами"), chosen over subtotal rows so the main sheet stays one row per plot and source:
  per source (the cooperative's supply first, then the wells by name) `source`, `sourceName`, `plots` (distinct),
  `plotDays` (connected days of its rows), `cubicMetres` and `amount`.
- Totals `all` on both sheets: the sums of the rows listed (`Money.plus`, the displayed m³); the m³ total is empty with
  `DERIVED_FROM_UNKNOWN` when a listed value is not known and empty when nothing was measured; the plot count has no
  total (a plot that changed source counts under both sources).
- Row cap: the sheets as every report, and the water ledger rows read: a range with more than the cap of them is 422
  `REPORT_TOO_LARGE` too, because amounts summed from a cut list would be wrong.
- **A well pump's share is counted twice across reports.** It is electricity priced at the common consumers tariff, so
  `electricity-tree` lists it in its `COMMON_CONSUMER` section and `water` in `pumpShare`. The board must not add the
  two reports' totals together.
- File name `water_<from>_<to>.xlsx`.

## Report: missing readings (`missing-readings`, functional spec 2.8)

Filters: reading dates (`period`, `DATE_RANGE`, basis `READING_DATES`, default the previous calendar month, at most a
year), resource (`resource`, default both), meter kinds (`meterKinds`, default `PLOT`; substations, quarter meters and
common consumers optional) and the street (only the plot meters of its plots: the named meters have no plot). Needs
`REPORTS_VIEW` only. Reads `meters.MeterReports.missingReadings` (the row cap plus one as the limit), in the order of the
meter register (plot meters in street order, then the other kinds by kind and name), and
`ledger.LedgerReadModel.openIntervalItems` for the listed meters.

- Sheet `main`, one row per meter that worked through the range and has no approved reading dated in it: `resource`
  and `kind` (`CODE`), `name` (a plot meter's plot number, another meter's name), `street`, `serial`, `lastReadingOn`
  and `lastReadingValue` (its last point before the range, three decimals), `initialValue` (true when that point is the
  installation's initial value, not a reading), `daysSince` (to the last day of the range) and `pending` (a member's
  reading dated in the range waits for approval; pending, rejected and superseded readings never count). No totals.
- Not listed: a water meter of a plot not connected to water on any day of the range; a meter installed after the
  range or removed before it. **Known interpretation:** a meter installed or removed *within* the range is not listed
  either, because its initial or final value is a point of the range (money rules 6.4, as the register's
  `noReadingSince` filter counts it). The spec names only "after" and "before"; a meter replaced mid-month is
  therefore never listed for that month.
- Header note `OPEN_NOT_CHARGED` (param `count`): the open "not charged" items of kind `INTERVAL` of the listed meters,
  only when there are any. The web shows it with a link to the queue (`/charges/not-charged`); the file shows the
  sentence. The report does not replace the queue.
- File name `missing-readings_<from>_<to>.xlsx`.

Tests: `WaterReportTests` (part-month connection, a plot that changed source, an unconnected plot that was charged,
well and street filters, net charges with an adjustment and a reversal, unknown and unmeasured m³, the summary, the file
number for number, defaults, the cap over the ledger rows, audit, 403 for a member and the electrician),
`MissingReadingsReportTests` (read, unconnected, after, before and within-range meters left out, the pending mark, the
default kinds and the named meters, resource and street, the note count, the file, the cap, audit, 403),
`ReportTextsTests`; on the web `ReportPage.test.tsx` (code texts and the queue link).

## Depends on

`ledger` (`LedgerReadModel`), `meters` (`MeterReports`, `Quantity`), `plots` (`PlotCatalog`, `PlotOwners`, `PlotStatuses`), `members` (`MemberDirectory`:
debtors' contacts, names of who booked a correction), `settings` (`CooperativeSettings`: red amount and days, losses mode and percent),
`tariffs` (`ChargeTypeCatalog`, `ExpectedAmounts`), `tenancy` (`TenantDirectory`), `identity` (`CurrentUser`,
`AccessProfile`, `Permission`), `audit`, `money` and `problem`. No module depends on `reports`.
