# reports

Built-in reports over what the app has booked: charges by month, plot balances, debtors, electricity by the meter tree,
water, missing readings, land tax and corrections. Each is shown as an on-screen preview and downloaded as an Excel
file. Report templates are stored, versioned and archived here, with the field catalog, the values a template is
filled with and the filler (ADR 0015). Requirements:
`docs/features/013-reports.md`; specs: `context/spec/013-reports/`.

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

### Report templates (tag `report-templates`, generated `ReportTemplatesApi`)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /report-templates` | `listReportTemplates` | `REPORTS_VIEW` or `REPORT_TEMPLATES_MANAGE`; the tax report slot first (`NOT_ADDED` while no active template holds slot `TAX`), then the templates, active before archived, by name |
| `POST /report-templates` | `uploadReportTemplate` | `REPORT_TEMPLATES_MANAGE`; multipart `file`, `name` (1..200), `description` (<= 500), `slot` (`TAX`); 201 `ReportTemplateUploadResult` (template with version 1 and `warnings`) |
| `GET /report-templates/{id}/versions` | `listReportTemplateVersions` | `REPORT_TEMPLATES_MANAGE`; newest first |
| `POST /report-templates/{id}/versions` | `uploadReportTemplateVersion` | `REPORT_TEMPLATES_MANAGE`; multipart `file`; 201 with the next version; archived is 409 `TEMPLATE_ARCHIVED` |
| `POST /report-templates/{id}/archive` | `archiveReportTemplate` | `REPORT_TEMPLATES_MANAGE`; 200, idempotent (archiving again changes nothing and writes no audit entry) |
| `GET /report-templates/{id}/versions/{versionId}/file` | `downloadReportTemplateVersion` | `REPORT_TEMPLATES_MANAGE`; the stored `.xlsx`, also of an archived template |
| `GET /report-templates/catalog` | `getReportTemplateCatalog` | `REPORTS_VIEW` or `REPORT_TEMPLATES_MANAGE`; the field catalog (below) |
| `POST /report-templates/{id}/generate` | `generateReportTemplate` | `REPORTS_VIEW`; body `{from, to, lang}`; the filled `.xlsx` of the latest version with `X-Report-Warnings` and `X-Report-Warnings-Detail` (section "Template filler") |

Upload refusals (nothing stored): 400 `VALIDATION_FAILED` (form fields), 422 `TEMPLATE_TOO_LARGE` (over 2 MB, checked
before the file is read; 422 like `REPORT_TOO_LARGE`, while a request over the 10 MB multipart limit stays 413
`FILE_TOO_LARGE`), 400 `TEMPLATE_INVALID` with one `errors[{field: file, code}]` (`NOT_XLSX`, `MACROS`,
`ACTIVE_CONTENT`, `BROKEN_ZIP`, `UNPACKED_TOO_LARGE`, `TWO_ROW_SETS`, `ARRAY_ACROSS_ROW`), 409 `TEMPLATE_NAME_TAKEN` (`errors[{field:
name}]`) and 409 `TEMPLATE_SLOT_TAKEN` (`errors[{field: slot}]`). Links to other workbooks and data connections are
removed and reported as the warning `EXTERNAL_LINKS_REMOVED`. `ReportTemplatesController` checks the permission with
`@PreAuthorize` on every operation before the file is read (`ReportTemplatesAccessTests`). A missing `file` or `name`
is 400 `VALIDATION_FAILED` with that field and `REQUIRED` (a local `@ExceptionHandler`; elsewhere a missing part stays
`MALFORMED_REQUEST`). A new version stored by another request at the same moment despite the row lock (only the unique
key's backstop) is 409 `TEMPLATE_VERSION_CONFLICT`.

## Template store (`internal`, functional spec 2.11 and 2.12)

| Type | Role |
|---|---|
| `ReportTemplateRecord`, `TemplateStatus` | Table `report_template`: name, description, `slot` (`TAX` or none), `ACTIVE`/`ARCHIVED`, created and archived by and at. Archived, never deleted |
| `ReportTemplateVersionRecord` | Table `report_template_version`, `@Immutable`, used only to insert a version (file, size and SHA-256 of the stored bytes, uploader, time) |
| `TemplateVersionRow`, `TemplateFile` | Projections: a version without its file (every list), and the file name with the bytes (the download only) |
| `ReportTemplateRepository`, `ReportTemplateVersionRepository` | Queries that name the cooperative on top of RLS. The version repository is a plain `Repository` without `findAll`/`findById`, so nothing can load a version entity with its file by accident |
| `TemplateUploadChecks`, `TemplateXml`, `ExternalLinkStripper`, `TemplateRowSet` | Upload checks (below), the safe StAX reader, link stripping and the row sets `plots`, `months`, `meters`, `water` |
| `ReportTemplateService` | List, upload, new version, versions, archive, download, the field catalog |
| `ReportTemplatesController` | The endpoints, generated `ReportTemplatesApi` |
| `TemplateField`, `TemplateValue`, `TemplateValues` | The field catalog, one placeholder's value, the values of one fill (sections below) |
| `TemplateGenerator`, `TemplateFiller`, `SheetFiller`, `XmlTree`, `FormulaShifter` | Generating a file: the transaction and the audit entry, the filler, the rewrite of one sheet, the small XML trees and the streaming copy, the A1 reference shifter (section "Template filler") |

- **Cooperative** from the caller's profile (the token), never from the request.
- **Transactions**: the file is checked before any transaction (no database needed, no row lock held while parsing);
  then one `TransactionTemplate` stores it and publishes the audit event last. A refused upload stores nothing and
  writes no entry. The list, the versions and the download are read-only transactions.
- **Files never loaded by lists**: the entity has no lazy basic attribute (no bytecode enhancement), so every read is
  a JPQL constructor projection without `content`; `ReportTemplatesTests.listsNeverLoadAVersionWithItsFile` asserts
  through Hibernate statistics that the list, the versions and the archive load no `ReportTemplateVersionRecord`.
  `content` has `STORAGE EXTERNAL` (V20), so a row without it reads no TOAST.
- **List**: the tax report slot first, either the active template with slot `TAX` or the fixed entry `NOT_ADDED`
  (`slot` `TAX`, `versionCount` 0, nothing else; a fixed entry of the code, not a seeded row), then every other
  template, active before archived, by `lower(name)`, the id breaking ties. Each with its latest version and count.
- **Names and slot**: a name is stripped (blank is 400 `VALIDATION_FAILED` `name` `REQUIRED`) and unique among the active
  templates ignoring case (409 `TEMPLATE_NAME_TAKEN`); one active template holds the slot (409 `TEMPLATE_SLOT_TAKEN`).
  Both are checked first and guaranteed by the partial unique indexes `report_template_name_uq` and
  `report_template_slot_uq` (a violation is mapped to the same 409). Archiving frees both: a new template with the name,
  or a new template in the slot, is allowed again. The same name is free in another cooperative.
- **Versions**: a new version is `max(version_no) + 1` read under `PESSIMISTIC_WRITE` on the template row, so two uploads
  get consecutive numbers; the unique key `report_template_version_no_uq` is the backstop (409
  `TEMPLATE_VERSION_CONFLICT`). An archived template takes no new version (409 `TEMPLATE_ARCHIVED`). Versions are
  listed newest first with `uploadedByName`, the current "Last First Middle" of the uploader from
  `members.MemberDirectory.find` (absent when the member no longer exists).
- **Archive**: idempotent. The first archive sets `archived_by`/`archived_at` and writes the audit entry; archiving again
  answers 200 with the template as it is and writes nothing.
- **Download**: the stored bytes, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
  `Content-Disposition: attachment; filename="<ASCII, other characters as _>"; filename*=UTF-8''<percent-encoded>`.
  Also of an archived template. Not audited. An unknown template or version, a version of another template and
  anything of another cooperative is 404.

### Upload checks (`TemplateUploadChecks`)

In this order; the first that fails answers and nothing is stored:

1. Over 2 MiB (2 097 152 bytes): 422 `TEMPLATE_TOO_LARGE`, from the declared size before the bytes are read and again
   on the bytes. The multipart limit stays 10 MB (`application.yml`), so 2 to 10 MB gets this problem and a larger
   request 413 `FILE_TOO_LARGE`.
2. The name does not end in `.xlsx` (ignoring case): `NOT_XLSX`. `.xlsm` is refused here as `NOT_XLSX` (as the contract
   says), like `.xls`, `.xltx` and a file without a name; a macro-enabled workbook renamed to `.xlsx` is refused below as
   `MACROS`.
3. The content does not start with `PK\3\4`: `NOT_XLSX`.
4. `xlsx.WorkbookGuard.withLimits(200, 20 MiB, 40 MiB)`: 200 entries, 20 MiB per entry, 40 MiB unpacked in total,
   counted from the bytes decompression really produces. Tight on purpose: a 2 MiB template has tens of parts and
   unpacks to a few MiB, while the import's defaults (50/100 MiB) are for 10 MB data files. `NOT_A_ZIP`,
   `NOT_A_WORKBOOK`, `WRONG_EXTENSION` are `NOT_XLSX`; `TOO_MANY_ENTRIES`, `ENTRY_TOO_LARGE`, `TOTAL_TOO_LARGE` are
   `UNPACKED_TOO_LARGE` (a compressed bomb); `UNREADABLE` (corrupt, truncated, encrypted) is `BROKEN_ZIP`.
5. Two entries whose names differ only in case (OPC part names are case-insensitive): `BROKEN_ZIP`.
6. `MACROS`: a part whose name contains `vbaProject`, an Excel 4.0 macro sheet (`xl/macrosheets/`), or a content type in
   `[Content_Types].xml` containing `macroEnabled`, `vbaProject` or `macrosheet`.
7. `ACTIVE_CONTENT`: `xl/activeX/` or `xl/embeddings/` (OLE objects), or a content type containing `activeX` or
   `oleObject`.
8. Every `.xml` and `.rels` part is read with `TemplateXml`: the JDK's own StAX parser
   (`XMLInputFactory.newDefaultFactory()`), DTDs and external entities off, entity references not replaced, and any
   `<!DOCTYPE` refused outright (with DTD support off the JDK parser would skip one silently). Not well-formed or a
   document type: `BROKEN_ZIP`. Every part is read through a bound of 20 MiB, never trusting declared sizes.
9. `TWO_ROW_SETS`, the dry parse of the repeating rows: placeholders `${...}` are read from the shared strings (rich-text
   runs joined, phonetic runs left out) and inline strings of every `xl/worksheets/*.xml`; a row with placeholders of two
   row sets (`rows.<set>.` of `plots`, `months`, `meters`, `water`), or a sheet with two rows of row-set placeholders, is
   refused. Unknown sets and single values are not refused (the filler leaves an unknown placeholder visible and warns).
   `ARRAY_ACROSS_ROW`: an array formula (`<f t="array" ref>`) whose range spans the repeating row and another row
   (Excel refuses to insert rows inside an array); one lying only on the repeating row is copied per copy.
10. Links (`ExternalLinkStripper`): `xl/externalLinks/*` (with their relationships), `xl/connections.xml` and
    `xl/queryTables/*` are dropped; in every other `.rels` part the relationships that point at a dropped part or have
    the type `externalLink`, `connections` or `queryTable` are removed, as are `<externalReferences>` in
    `xl/workbook.xml` and the content-type `Override`s of dropped parts. Only those elements are cut out of the text
    (namespaces and every other byte stay as they were); the archive is written again (deflated, original order) and
    the cleaned bytes are stored with their own size and SHA-256 and the warning `EXTERNAL_LINKS_REMOVED`. A file
    without links is stored byte for byte. Formulas that referred to a link keep their text; pivot caches whose source
    is another workbook are not touched.

Refusals carry a fixed English detail; the file name and anything read from the file are never logged or returned.

### Audit of templates

Entity type `REPORT_TEMPLATE`, entity id the template id, label the template's name as entered (user content).
`REPORT_TEMPLATE_UPLOADED` for a new template and for each new version, new values `version` (the number) and
`fileName` (the stored base name, user content); `REPORT_TEMPLATE_ARCHIVED` with `version` (the latest version
number), once. Never the file. Published last in the transaction that stores the change (`ReportAuditPublisher`).

### Tests

`TemplateUploadChecksTests` (unit: every refusal in order, rich text, inline strings, link stripping and the cleaned
file opening with fastexcel-reader, target resolution, file names), `ReportTemplatesTests` (HTTP in a throw-away
cooperative: versions and byte-identical downloads, list order, the tax slot, names, form validation, every refusal
storing nothing, links removed, the archive, audit entries, another cooperative, unknown ids, lists loading no file),
`ReportTemplatesAccessTests` (401/403 per endpoint, the catalog and generating open to the treasurer, 200 on a stored
template for the chairman and the treasurer). `TemplateFiles` builds the test workbooks with fastexcel and hand-made
zips; `SampleTemplates` builds the sample "land tax statement" (also written to `target/sample-templates/` by
`TemplateFillerTests` for a look in Excel and for the demo data).

## Field catalog (`TemplateField`, feature 013 §4.1 and §4.2)

`GET /report-templates/catalog` lists every placeholder `${key}` the app fills, in the order of the enum, each with its
`type` (`ReportTemplateFieldType`: `TEXT`, `NUMBER`, `MONEY`, `QUANTITY`, `INT`, `DATE`, `MONTH`, `DATE_TIME`), its
`rowSet` (absent for a single value), a language-neutral `example` (absent for text that depends on the language or on
what the cooperative entered) and the `permission` (`REPORTS_VIEW` for every one). The server returns codes only: the
description is `reportTemplates.fields.<field>` in the web catalogs, a nested path. `field` is the key itself for a
fixed placeholder; the two families that depend on the charge types, `totals.charged.<type>` and
`rows.plots.debt.<type>`, have the description codes `totals.charged.byChargeType` and `rows.plots.debt.byChargeType`
(texts with `{chargeType}`, which the client fills with the type's name; `byChargeType` is lower case, so it never
meets a code or an id). They are listed once per charge type of the cooperative, archived ones included, in the
configured order, with `pattern` and `chargeTypeId`; `<type>` is the code of a seeded type and the id of a custom one,
and a template may also name a seeded type by its id (`TemplateField.resolve`). A key the app does not know (not in the
catalog, a type the cooperative lacks) resolves to nothing: the filler leaves it in place with `UNKNOWN_PLACEHOLDER`.

| Group | Keys |
|---|---|
| Cooperative | `cooperative.name`, `cooperative.officialName`, `cooperative.edrpou`, `cooperative.address` (legal address), `cooperative.bankName`, `cooperative.iban` |
| Period and file | `period.from`, `period.to`, `period.year`, `generatedAt`, `generatedBy` |
| Totals | `totals.landTax.expected`, `totals.landTax.charged`, `totals.charged.<type>`, `totals.debt`, `totals.plots.count`, `totals.plots.areaSotka` |
| `plots` | `rows.plots.number`, `.street`, `.area` (m²), `.areaSotka`, `.ownersCount`, `.debt.total`, `.debt.<type>`, `.status`, `.oldestUnpaid`, `.daysOverdue`, `.landTax.rate`, `.landTax.expected`, `.landTax.charged` |
| `months` | `rows.months.month`, `.status`, `.regular`, `.opening`, `.reversals`, `.adjustments`, `.effective` |
| `meters` | `rows.meters.level`, `.name`, `.serial`, `.kind`, `.startValue`, `.endValue`, `.consumption`, `.childrenConsumption`, `.lossesKwh`, `.lossesPercent`, `.plotsFed` |
| `water` | `rows.water.number`, `.street`, `.source`, `.connectedDays`, `.serial`, `.cubicMetres`, `.total` |

No name, phone or e-mail of a person is a placeholder (spec 4.2): the plots carry the number of owners, never who they
are. `generatedBy` is the person making the file, as in the header block of every report file.

## Template values (`TemplateValues`)

`TemplateValues.resolver(from, to, language, generatedBy, generatedAt)`, inside the caller's transaction, gives
`single(key)` (a `TemplateValue`, or empty for an unknown key; a row set's key is filled per row) and `rows(set)` (per
row the value of every placeholder of the set by key; the families under the code and under the id). A `TemplateValue`
is a typed value, empty (written empty, no warning) or not known with a `ReportTemplateValueReason` (written empty and
listed as `VALUE_UNKNOWN`, never `0`). Everything is read when first asked for and kept for the fill. No money rule is
added: the values are the read models of the built-in reports (the same beans: `PlotBalancesReport.balances`,
`LandTaxReport.plots`, `ChargesByMonthReport.load`, `ElectricityTreeReport.consumption`, `WaterReport.load`) and one
ledger facade, and every total is a `Money` sum of the rows it stands for. The period, `from` to `to` (both included,
Kyiv days), means:

- `period.from`, `period.to`: the days; `period.year`: the year of `to`. `generatedAt`: when the fill started, Kyiv
  wall time to the second (`DATE_TIME`); `generatedBy`: the caller's display name.
- `cooperative.*`: name and ЄДРПОУ from `tenancy.TenantDirectory`, the requisites from `settings.Requisites`; one that
  is not entered is not known (`NOT_SET`), so the warnings tell the chairman to fill it in.
- `totals.debt`, `totals.plots.count`, `totals.plots.areaSotka` and the row set `plots`: the plot balances of every
  active plot without common land, every charge type, as of `to`, or as of today when `to` is later (there is no
  balance of a day to come). The debt is the sum of the plots' debts (the plot balances report's total for that day),
  the area the sum of `tariffs.ChargeCalculator.sotkas` of their areas (exact). Statuses as on that report (the map's
  status today, worked out with today's thresholds for a past day), written in the file's language.
- `totals.landTax.*` and `rows.plots.landTax.*`: the land tax report for the year of `to`: its totals; per plot the
  expected and charged amounts and the rate when one rate charged it all year (`SEVERAL_RATES` otherwise); a plot the
  land tax does not charge in that year has these cells empty.
- `totals.charged.<type>`: `ledger.LedgerReadModel.chargedByType(from, to)`, the effective amount (charges, reversals
  and adjustments, without opening balances) of the type's charges whose first service day lies in the period, booked
  whenever, on every plot: the land tax report's year rule over any range of days. A type without such charges is
  `0.00`.
- `months`: per billing month from the month of `from` to the month of `to` that has charges, the cooperative's
  totals of the month: the subtotal rows of the charges by month report's sheet by billing month.
- `meters`: the consumption sheet of the electricity report for the service days (in the order of the tree, `level`
  its depth); a value without a reading and everything worked out from it stay not known with their reasons.
- `water`: the main sheet of the water report for the service days, every source and street.

A row set of more than the reports' row cap (20 000) is 422 `REPORT_TOO_LARGE`. Texts of codes (statuses, meter
kinds, water sources, month statuses) come from the report bundles in the file's language. The bundles also hold the
catalog's descriptions (`reportTemplates.fields.*`, so `TemplateFieldTests` checks every field has both texts and the
web test checks they equal the catalogs), the warnings (`reportTemplates.warnings.<ReportTemplateWarningCode>`, the
same texts the upload uses), the reasons (`reportTemplates.valueReasons.*`) and the warnings sheet
(`reportTemplates.warningsSheet.name` and `.columns.*`).

Tests: `TemplateFieldTests` (unique and well-formed keys, no personal data, row sets by prefix, examples in the format
of their type, the families by code and id, resolving, descriptions and warning texts in both languages),
`TemplateCatalogTests` (the endpoint with a custom and an archived charge type, who may read it), `TemplateValuesTests`
(a worked ledger, requisites, land tax, water and a tree with an unknown quarter: the single values, every total and
row set equal to the built-in report of the same period through the API, an unknown value, two rates, the as-of day,
the row cap), `ledger.LedgerChargedInYearTests` (`chargedByType`).

## Template filler (`TemplateFiller`, ADR 0015)

`POST /report-templates/{id}/generate` with `{from, to, lang}`: `TemplateGenerator.generate`, one plain
`@Transactional` (the value facades are `MANDATORY`; the outbox stores the audit publication). In order: the period
(`to` before `from`, or `to` on or after the same day a year later: 400 `REPORT_FILTER_INVALID` with
`errors[{field: to, code: INVALID}]`), the template of the caller's cooperative (404 for an unknown one or one of another
cooperative, 409 `TEMPLATE_ARCHIVED`), its latest version's file, the values (`TemplateValues.resolver`, the caller's
display name, now), the fill, and **last** the audit event: `REPORT_GENERATED`, entity type `REPORT_TEMPLATE`, entity
id the template, label its name, fields `template` (id), `version`, `filters` (`[{param: from}, {param: to}]`), `rows`
(rows written for row sets), `personalData` (`false`) and `output` (`XLSX`). A refused or failed generation leaves no
entry. The language is `lang`, else `Accept-Language`, else `uk` (`ReportLanguage`). The answer is the workbook with
`Content-Disposition` `<template name>_<from>_<to>.xlsx` (the ASCII fallback and the RFC 8187 `filename*` of the
template download), `Vary: Accept-Language`, `X-Report-Warnings` (the count, `0` without warnings) and, with
warnings, `X-Report-Warnings-Detail`: the first warnings as `ReportTemplateWarning` JSON in base64url without padding,
at most 50 and fewer while the encoded value would reach 4 096 characters (`TemplateGenerator.detail`; the sheet always
lists all). There is no CORS configuration in the backend (the web app is same-origin behind Caddy and the Vite proxy),
so nothing exposes the headers; a cross-origin deployment must list both in `Access-Control-Expose-Headers`.

The generated `ReportTemplatesApi` validates the body (`@Valid`) while Spring resolves the arguments, before the
`@PreAuthorize` check runs: a malformed body (`to` missing, `lang` not `uk`/`en`) is 400 for any caller with a token, a
well-formed one is 403 for a caller without `REPORTS_VIEW`, and no token is 401 (`TemplateGenerateTests`). The 400
says nothing about the cooperative, so this order is accepted rather than moving the check into the security filter
chain.

`TemplateFiller.fill(bytes, name, version, values, texts, rowCap)`, three steps:

1. **Checks and plan, no database.** The stored bytes pass `TemplateUploadChecks.check` again (the guard with the
   template limits, macros, active content, every part through `TemplateXml` with `<!DOCTYPE` refused, bounded reads,
   `TWO_ROW_SETS`, `ARRAY_ACROSS_ROW`): a stored file that no longer passes is 400 `TEMPLATE_INVALID` with the reason.
   Then the parts (each read through the 20 MiB bound), `workbook.xml` (sheets, `date1904`), the relationships, the
   shared strings (runs joined, phonetic runs left out, rich or not) and per sheet the upload's `SheetScan`: the
   repeating row and its set, every placeholder key, the master cell and text of each shared formula.
2. **Values**, in the caller's transaction: the rows of each repeating row's set (`Resolver.rows`, 422 past the cap)
   and every single value. More than the cap (20 000) rows for row sets in all is 422 `REPORT_TOO_LARGE`.
3. **Write** with `java.util.zip`, every part not named here byte for byte. Sheets stream through `SheetFiller`
   (StAX reader of `TemplateXml`, the JDK's StAX writer, a row at a time as a small `XmlTree.Node`, no document
   model). Output that would unpack to more than 256 MiB is 422 `REPORT_TOO_LARGE`. Steps 1 and 3 turn any other
   failure (an I/O error, a runtime exception, a stack overflow) into 400 `TEMPLATE_INVALID` `BROKEN_ZIP`, never a 500
   or a stack trace; no regular expression runs over XML (the only patterns are the placeholder and the A1 tokens of a
   formula).

What the filler does (the promise of ADR 0015):

- **Placeholders** `${key}` in shared strings (`t="s"`) and inline strings. Alone in the cell (spaces around it
  ignored): a typed cell with the cell's style (`s`) kept: `MONEY`, `NUMBER`, `QUANTITY`, `INT` as numbers (`<v>`
  plain decimal), `DATE`, `MONTH` (its first day), `DATE_TIME` as Excel serials (`TemplateValue.serial`; 1900
  system: days since 30.12.1899; a workbook with `date1904` counts from 01.01.1904; a time is the fraction of the
  day), `TEXT` as an inline string. Inside text: the text with the values formatted (`TemplateValue.text`: amounts,
  numbers, quantities `1 250,50` with a no-break space in Ukrainian, `1,250.50` in English, their scale as given;
  whole numbers without separators, so a year is `2025`; days `dd.MM.yyyy`, months `MM.yyyy`, times
  `dd.MM.yyyy HH:mm`), written as an inline string. `sharedStrings.xml` is never changed (other cells may share a
  string). A cell whose shared or inline string had rich-text runs loses the run formatting: `RICH_TEXT_FLATTENED`.
- An **unknown placeholder** (not in the catalog, a charge type the cooperative lacks, an unknown row set) stays in the
  cell as written (the cell untouched when nothing else in it is known): `UNKNOWN_PLACEHOLDER`, once per template cell.
  A known placeholder **without a value** is written empty, never `0`: `VALUE_UNKNOWN` with the reason, once per
  output cell. An empty value is written empty without a warning.
- **The repeating row** (the row with placeholders of one row set) is written once per row of the set, renumbered
  (`<row r>`, `<c r>`); with an empty set it is removed. Placeholders of single values on it are filled in every copy.
  Rows below move by `copies - 1`.
- **References** (`FormulaShifter`): cell formulas on every sheet (references to another repeated sheet first, then the
  sheet's own), formulas of the copies filled down (`copy`), shared formulas expanded to plain formulas (`offset`) on a
  repeated sheet and wherever the master names a repeated sheet (others stay shared), array formulas' text and `ref`
  (an array on the repeating row is copied per copy), merged ranges (one only on the repeating row copied per copy,
  the `count` kept true), the `dimension`, the `autoFilter`, conditional formatting and data validation `sqref` lists
  (a cell on the repeating row grows to every copy: `D5` becomes `D5:D7`) and their formulas, hyperlinks (`ref` per
  copy on the repeating row, `location` shifted), row breaks after the repeating row or below it, defined names (print
  area, print titles, names), tables (`ref` and their `autoFilter`) and drawing anchors (`xdr:row` below the repeating
  row). An element whose reference is lost with an empty set (`#REF!`) is dropped. Charts are not shifted: a chart
  that names a repeated sheet gives `CHART_REFS_NOT_SHIFTED` (once per sheet).
- **No formula is evaluated**: `xl/calcChain.xml` is removed with its relationship and content type override,
  `<calcPr fullCalcOnLoad="1">` is set (added before the elements that follow it in the schema when missing), and the
  cached value `<v>` and value type `t` of every formula cell are dropped.
- **Warnings sheet**: added last when there is at least one warning, named `reportTemplates.warningsSheet.name`
  («Попередження» / "Warnings", ` (2)`, ` (3)` ... when a sheet of that name exists, ignoring case), columns sheet,
  cell, placeholder, warning (`reportTemplates.warnings.<code>`) and reason (`reportTemplates.valueReasons.<reason>`),
  inline strings; with its own part, relationship (`rIdNadel<n>`) and content type.
- **Document properties**: `docProps/core.xml` gets `dc:title` (the template's name) and `cp:version` (the version
  number), every other property kept; a template without the part gets one with its relationship and content type.

Engine size (ADR 0015 fallback trigger: about 2 000 lines of filler and shifter without tests): `FormulaShifter`,
`TemplateFiller`, `SheetFiller` and `XmlTree` together are about 1 910 lines with their documentation, about 1 510
without blank and comment lines: close to the threshold, not past it (how a value is written, `TemplateValue.text` and
`.serial`, lives with the value). A further feature of the filler should reopen the POI question.

Tests: `TemplateFillerTests` (unit: typed cells and styles, the 1904 system, text in both languages, unknown
placeholders and values, rich text, inline strings, the repeating row with 0, 1, 3 and 300 copies and the sums below
it, every place references move, the empty set, shared and array formulas, other sheets, drawings, tables and charts,
two repeated sheets, the calculation chain, the warnings sheet and its name, document properties, the checks again at
fill time, the row cap and the output bound, a 100 KB attribute list, 20 000 rows of 20 columns under 5 s, every file
read back with fastexcel-reader), `TemplateGenerateTests` (HTTP: the sample land tax statement equal to the land tax
report, the audit entry once, the headers and the warnings sheet in English with an unknown tree value, the period,
404, 409, the row cap and a stored file that no longer passes, all without an entry, and the order of validation and
permission), `TemplateUploadChecksTests` (`ARRAY_ACROSS_ROW`).

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
| `ReportAuditPublisher` | The `REPORT_GENERATED` entry, and `REPORT_TEMPLATE_UPLOADED` / `REPORT_TEMPLATE_ARCHIVED` |
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

## Measured (performance, functional spec 2.2)

`ReportsPerformanceTests` reports a throw-away cooperative of the size the spec names: 300 plots (60 with two owners),
12 billing months, about 14 600 ledger rows (membership, electricity, land tax and water charges, a tenth reversed and
a tenth adjusted in the next month, two losses and two common consumer runs with their charges), the electricity tree
(1 substation, 10 quarters, 5 common consumers, 300 plot meters), 40 water meters and 4 400 readings. Each report runs
through MockMvc as the chairman, as a preview and as a file, after one warm-up call; every call must take under 5 s.
Indicative numbers (dev machine, Testcontainers PostgreSQL, one run; they vary by a few tens of ms):

| Report | Rows | Preview ms | File ms |
|---|---|---|---|
| `charges-by-month` (year 2025) | 124 | 50 | 52 |
| `plot-balances` | 300 | 63 | 73 |
| `debtors` | 300 | 48 | 49 |
| `electricity-tree` (year 2025, both sheets) | 5 853 | 300 | 305 |
| `water` (year 2025) | 42 | 46 | 49 |
| `missing-readings` (all meter kinds, a range with no reading day) | 357 | 23 | 30 |
| `land-tax` (2025) | 300 | 47 | 38 |
| `corrections` (year 2025) | 2 162 | 44 | 58 |

The plot balances and debtors previews run 18 SQL statements each for 300 plots (the test bounds them at 40, counted
with Hibernate statistics), so no query runs per plot. EXPLAIN (ANALYZE, BUFFERS) of the heaviest ledger queries on this
fixture, as `nadel_app` under RLS: corrections 6.6 ms, the electricity "charged" rows 18.7 ms (the correction lookup
uses `charge_adjusts_idx`, V19, 690 index probes), debts as of a day 19 ms; all read the 14 600 rows through
`charge_tenant_idx`. Nothing was slow, so no index was added (no V21).

## Depends on

`ledger` (`LedgerReadModel`), `meters` (`MeterReports`, `Quantity`), `plots` (`PlotCatalog`, `PlotOwners`, `PlotStatuses`), `members` (`MemberDirectory`:
debtors' contacts, names of who booked a correction), `settings` (`CooperativeSettings`: red amount and days, losses mode and percent, requisites),
`tariffs` (`ChargeTypeCatalog`, `ExpectedAmounts`, `ChargeCalculator.sotkas`), `tenancy` (`TenantDirectory`), `identity` (`CurrentUser`,
`AccessProfile`, `Permission`), `audit`, `money` and `problem`. No module depends on `reports`.
