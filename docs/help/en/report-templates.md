---
id: report-templates
title: "Report templates"
description: "Your own Excel forms for reports: uploading a template with ${...} fields, versions, archive, the tax report, substitution fields and generating a file for a period."
kind: screen
routes:
  - /reports/templates
permissions:
  - REPORT_TEMPLATES_MANAGE
section: reports
order: 30
shots:
  - name: report-templates
    user: chairman
    path: /reports/templates
---

## What the screen is for

If you need your own form (a statement for a meeting, a report for the tax office), prepare it in Excel as usual, and where a value from the system should stand, write a field like `${name.of.field}`. The system replaces the fields with data and keeps your formats, merged cells, column widths and formulas. Here templates are uploaded, updated and archived, and files are generated from them.

## Who sees it

The page is seen by those who have "{{ui:permission.REPORT_TEMPLATES_MANAGE.name}}": by default the chairman only. The treasurer generates files by templates from the [Reports](help:reports) screen, the section "{{ui:reportTemplates.generate.listTitle}}", and needs "{{ui:permission.REPORTS_VIEW.name}}" for it.

## Every element

The button "{{ui:reportTemplates.back}}" returns to the reports.

### The tax report

A separate card "{{ui:reportTemplates.tax.title}}" with the state "{{ui:reportTemplates.tax.notAdded}}" until the chairman uploads the form with the button "{{ui:reportTemplates.tax.upload}}". The official form is added as an ordinary template, without changes to the program. When the form changes, upload a new version ("{{ui:reportTemplates.uploadVersion}}"). A second tax report template cannot be added ("{{ui:reportTemplates.errors.TEMPLATE_SLOT_TAKEN}}").

### Other templates

The section "{{ui:reportTemplates.listTitle}}". If there are no templates, you see "{{ui:reportTemplates.empty}}".

The button "{{ui:reportTemplates.add}}" opens a form: "{{ui:reportTemplates.form.name}}", "{{ui:reportTemplates.form.description}}", "{{ui:reportTemplates.form.file}}" ("{{ui:reportTemplates.form.chooseFile}}"). The button "{{ui:reportTemplates.form.submit}}" uploads the template. A valid file is an ordinary .xlsx workbook up to 2 MB. If another template already has this name, you see "{{ui:reportTemplates.errors.TEMPLATE_NAME_TAKEN}}".

By each template: the status ("{{ui:reportTemplates.status.ACTIVE}}" or "{{ui:reportTemplates.status.ARCHIVED}}"), the version number, who uploaded it and when, and these buttons.

| Button | What it does |
|---|---|
| {{ui:reportTemplates.generate.button}} | Opens the window "{{ui:reportTemplates.generate.title}}" with the fields "{{ui:reportTemplates.generate.from}}" and "{{ui:reportTemplates.generate.to}}" (both days are in the period, the period is no longer than a year). "{{ui:reportTemplates.generate.submit}}" creates the file and downloads it. The system does not store generated files. |
| {{ui:reportTemplates.uploadVersion}} | Creates version 2, 3… The latest version is always the one generated. |
| {{ui:reportTemplates.versions}} | Shows all versions. Each has "{{ui:reportTemplates.download}}", because old versions can be downloaded. |
| {{ui:reportTemplates.archive}} | The template stops being offered for generating and stops accepting versions, its name is freed, and its versions stay available for download (see the window "{{ui:reportTemplates.archiveTitle}}"). |

The name of the file you generate contains the template name. The template and its version are recorded in the file properties and in the change log.

### Substitution fields

The block "{{ui:reportTemplates.catalog.title}}": a list of fields with a type, a description and an example. The search is "{{ui:reportTemplates.catalog.search}}", the button "{{ui:reportTemplates.catalog.copyButton}}" copies a field. Groups: single values and sets of repeating rows (plots, charging months, electricity meters, water, payments).

- **A field alone in a cell** (`${rows.plots.landTax.expected}` and nothing more) is filled with a number or a date, not text, so formulas over it work. For a date give the cell a date format.
- **A field inside text** ("Land tax for `${period.year}`") is filled as text in the Ukrainian format of numbers and dates.
- **Single values:** `${cooperative.name}`, `${cooperative.officialName}`, `${cooperative.iban}` (the details from the settings), `${period.from}`, `${period.to}`, `${period.year}` (the year of the last day of the period), `${totals.debt}`, `${totals.landTax.expected}` and other totals.
- **A repeating row:** if a row has fields of one set (`rows.plots.*`, `rows.months.*`, `rows.meters.*` or `rows.water.*`), that row is repeated as many times as there are rows in the set. One such row per sheet. Two sets in one row, or two repeating rows on a sheet, are rejected by the system.

There are no names or contacts of people in templates: only data of the cooperative, plots, meters and sums.

### What the system does with a template

- An unknown field (a spelling mistake) does not disappear: it stays in the cell as written, and a sheet "{{ui:reportTemplates.warningsSheet.name}}" with the cell and the reason appears at the bottom of the file. The same goes for a field whose value is unknown (no reading, a detail not entered): the cell is empty, not zero. After generating you will see "{{ui:reportTemplates.generate.warningsCount}}".
- The system does not calculate formulas: it writes the fields and leaves formulas to you. Excel or LibreOffice recalculates them when you open the file; a viewer that does not calculate formulas shows them empty.
- Files with macros (.xlsm), active content and embedded objects, damaged ones or ones too large after unpacking are rejected, and nothing is saved. Links to other files are removed from the template with a warning. Charts that refer to a repeating row are not shifted (a warning).

### If a file does not fit

| What is shown | What to do |
|---|---|
| {{ui:reportTemplates.fileErrors.FILE_NOT_XLSX}} | Save the form as an Excel workbook (.xlsx). |
| {{ui:reportTemplates.fileErrors.FILE_TOO_LARGE}} | Make the file smaller. |
| {{ui:reportTemplates.invalid.MACROS}} | Save as an ordinary .xlsx without macros. |
| {{ui:reportTemplates.invalid.TWO_ROW_SETS}} | Leave one repeating row per sheet. |
| {{ui:reportTemplates.errors.TEMPLATE_ARCHIVED}} | Create a new template: an archived one accepts no new versions. |

> [!important]
> An archived template does not come back to work, but its versions can be downloaded. So if the form must change, upload a new version rather than archiving the template.

## Worked example

The template is a land tax statement (a rate of 35.00 ₴ per sotka per year; plots of 600, 800 and 1000 m²). In Excel you write:

| Cell | A | B | C |
|---|---|---|---|
| 1 | `${cooperative.officialName}` | | |
| 2 | Land tax for `${period.year}` | | |
| 4 | Plot | Area, m² | Tax for the year, ₴ |
| 5 | `${rows.plots.number}` | `${rows.plots.area}` | `${rows.plots.landTax.expected}` |
| 6 | Total | | `=SUM(C5:C5)` |

1. The chairman presses "{{ui:reportTemplates.add}}", writes a name, chooses the file and presses "{{ui:reportTemplates.form.submit}}".
2. He presses "{{ui:reportTemplates.generate.button}}", enters the period 01.01.2026 – 31.12.2026 and "{{ui:reportTemplates.generate.submit}}".
3. Row 5 is repeated for each plot: 600 m² gives 6 × 35.00 = 210.00; 800 m² gives 280.00; 1000 m² gives 350.00. The system turned the formula `=SUM(C5:C5)` into `=SUM(C5:C7)`, so "Total" equals 840.00.
4. The heading in row 1 is the full name of the cooperative from its details, in row 2 "Land tax for 2026".

## Screenshots

![The "Report templates" page: the tax report, other templates and substitution fields](shot:report-templates)

## Related pages

- [Reports](help:reports)
- [Report: filters, preview and Excel](help:report)
- [Settings: the cooperative's details](help:settings)
