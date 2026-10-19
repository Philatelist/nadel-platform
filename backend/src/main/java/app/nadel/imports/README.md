# imports

Excel import of a whole cooperative in one go: plots, members and ownership from one `.xlsx` file with three sheets
(`Plots`, `Members`, `Ownership`). Requirements: `docs/features/008-excel-import.md`; spec: `context/spec/008-excel-import/`.

Today the module offers the template download. Upload, preview, commit and history are added in later slices of the
feature.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /imports/template` | `downloadImportTemplate` | `IMPORT_RUN` (the platform operator has no permission and gets 403) |

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

## Header aliases

The template headers are English. When upload arrives, header matching will also accept Ukrainian aliases of the
column names; they live in one backend resource file. This is the one exception to the "everything in the
repository is English" rule; it is recorded in ADR 0012 (Slice 7 of the feature).

## Libraries

`org.dhatim:fastexcel` writes the template, `org.dhatim:fastexcel-reader` streams uploaded workbooks (later slices and the
tests that read the template back). Both are pinned to the same exact version (`fastexcel.version` in `pom.xml`).

Depends on `identity`, `problem` and `tenancy` (later slices also `members`, `plots` and `audit`).
