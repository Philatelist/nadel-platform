# imports

Excel import of a whole cooperative in one go: plots, members and ownership from one `.xlsx` file with three sheets
(`Plots`, `Members`, `Ownership`). Requirements: `docs/features/008-excel-import.md`; spec: `context/spec/008-excel-import/`.

Today the module offers the template download and the upload with a preview of the Plots sheet. The Members and Ownership
sheets, the commit and the history are added in later slices of the feature.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /imports/template` | `downloadImportTemplate` | `IMPORT_RUN` (the platform operator has no permission and gets 403) |
| `POST /imports` | `uploadImport` | `IMPORT_RUN`; multipart, one `file` part; answers 201 `ImportPreview` |

The template is returned as `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` with
`Content-Disposition: attachment; filename="nadel-import-template.xlsx"`.

## The template

`TemplateWriter` (fastexcel writer) builds it in memory. Each sheet has the English canonical header row, then an
instruction row, then an example row. Both of those rows start with `#`; the import ignores every row whose first cell
starts with `#`.

| Sheet | Columns |
|---|---|
| `Plots` | number, street, kind, area_m2, area_sotky, cadastral_number, has_building, notes |
| `Members` | external_id, last_name, first_name, middle_name, phones, email, postal_address, delivery_channel, telegram_username, viber_phone |
| `Ownership` | plot_number, member_external_id, share, valid_from |

Columns that hold identifiers or phone numbers (`number`, `phones`, `viber_phone`, `external_id`, `plot_number`,
`member_external_id`, `cadastral_number`) have the text format, so Excel keeps leading zeros and long digit strings.

## Upload and preview

`ImportService.preview` runs in this order; only the last step uses the database and a transaction.

1. The size is checked (over 10 MB: 413 `FILE_TOO_LARGE`; the multipart limits in `application.yml` and the container
   answer the same way before the controller is reached).
2. `ZipGuard` refuses anything that is not a safe `.xlsx` (400 `FILE_NOT_XLSX`): wrong extension, no zip signature (so
   also the old `.xls` and encrypted workbooks, which are OLE2 files), a corrupt archive, missing
   `[Content_Types].xml` or `xl/workbook.xml`, more than 200 entries, an entry over 50 MB or an archive over 100 MB once
   unpacked. Unpacked sizes are counted from the bytes decompression really produces, never from the sizes the archive
   declares (a zip bomb declares what it likes). The archive is read with commons-compress, which the spreadsheet
   library brings along; the JDK's `ZipInputStream` cannot read files written with data descriptors (as fastexcel does).
3. `WorkbookReader` (the only user of the spreadsheet library) streams the rows; `PlotsSheet` finds the columns with
   `HeaderMatcher`, reads every cell with `Cells` and stops with 400 `IMPORT_LIMIT_EXCEEDED` at the 2,001st plot row.
   Anything the library throws on a damaged file, a DOCTYPE included (DTDs are off), becomes `FILE_NOT_XLSX`. File
   names, sheet names and cell values are never logged.
4. In one transaction: the expired previews of the cooperative lose their plan (there is no scheduled job yet), the plots
   module plans the rows (`plots.PlotImport`), and an `import_run` row is inserted. Nothing else is saved: a preview
   creates no plot and no audit entry.

The preview lists the problems found (at most 1,000; `problemCount` is the total) with sheet, Excel row (0 for the file or
the whole sheet), canonical column and code. A row with a problem is not counted. `committable` is true only without
problems and with something to create or update, so the unchanged template and a workbook that is already imported are
not committable. The run is `PREVIEW` (with the normalised rows in `plan`, valid 30 minutes on the Kyiv clock) or
`HAS_ERRORS` (no plan).

Rules for reading a sheet: the header is the first row that is not empty and not a comment; a row whose first cell
starts with `#` and an empty row are skipped; Excel row numbers are kept. Cells: text is trimmed; a number cell is
cleaned of binary floating point noise (15 significant digits, so `0.059999999999999998` is `0.06`); text numbers accept
a comma as the decimal separator; yes/no accepts yes, no, так, ні, true and false in any case; a date is an Excel
date (a number in a cell formatted as a date) or text `DD.MM.YYYY`. The area is given in exactly one of `area_m2` and
`area_sotky` (1 sotka = 100 m2, computed exactly); both filled is `AREA_BOTH_OR_NONE`, and so is none for a new plot.

## Header aliases

The template headers are English. Header matching compares headers after normalisation (lower case, only letters and
digits kept) and also accepts aliases of the column names, Ukrainian ones among them (`Номер`, `Вулиця`, `Площа, сот.`);
they live in one backend resource file, `src/main/resources/imports/header-aliases.properties`. This is the one exception to the "everything in the
repository is English" rule; it is recorded in ADR 0012 (Slice 7 of the feature).

## Libraries

`org.dhatim:fastexcel` writes the template, `org.dhatim:fastexcel-reader` streams uploaded workbooks. Both are pinned to the same exact version (`fastexcel.version` in `pom.xml`).

Table: `import_run` (V9, RLS `tenant_isolation`; the application role cannot delete rows, the history is kept). It stores the
author as a snapshot (member id, Keycloak subject, display name), the counts, the problem count and, for a preview that
can still be committed, the normalised rows (`plan`, personal data, cleared when no longer needed).

Depends on `identity`, `problem`, `tenancy` and `plots` (later slices also `members` and `audit`).
