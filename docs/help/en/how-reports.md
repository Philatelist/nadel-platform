---
id: how-reports
title: "Reports and templates"
description: "Who sees the reports, which reports and Excel templates exist, how the system fills a template and what the tax report holds."
kind: topic
routes: []
permissions:
  - REPORTS_VIEW
  - CASHBOOK_VIEW
section: how-it-calculates
order: 90
shots: []
---

Reports put into one table what the system already knows: charges, payments, debts, electricity, water, corrections. Every report is shown on screen and can be downloaded as an Excel file. Besides the built-in reports, the chairman can upload their own Excel form (a template) and the system fills it in with the cooperative's data.

> **Reports introduce no new rules**
>
> A report calculates nothing "its own way": it only selects, groups and adds up the amounts already booked under the rules of chapters 18-25 and 27. So any amount in a report can be reconciled to the kopeck with a plot account or the billing months screen. Amounts in reports are always **effective** amounts: charges plus adjustments minus reversals.

## Who sees reports

| What | Permission | Who has it by default |
|---|---|---|
| Open and download reports, generate files from templates | View reports | Chairman, treasurer |
| The {{ui:reports.debtors.title}} report (this permission is needed as well) | View members | Chairman, treasurer |
| The {{ui:nav.payments}} report (this permission is needed as well; the note about the payer is shown only to someone who also has {{ui:permission.MEMBERS_VIEW.name}}) | View payments | Chairman, treasurer |
| Upload, replace, download and archive templates | Manage report templates | Chairman only |
| The four cash book reports: {{ui:reports.cash-book.title}}, {{ui:reports.bank-movements.title}}, {{ui:reports.income-expense.title}}, {{ui:reports.totals.expenses}} (only this permission is needed, not {{ui:permission.REPORTS_VIEW.name}}) | View cash and bank | Chairman, treasurer |

A member and the electrician have no {{ui:nav.reports}} item in the menu, and a direct address shows a message that they have no access. You can give your own role these permissions in {{ui:nav.roles}} (the page [Roles](help:roles)). A role with {{ui:permission.REPORTS_VIEW.name}} but without {{ui:permission.MEMBERS_VIEW.name}} sees every report except {{ui:reports.debtors.title}}.

## The {{ui:nav.reports}} screen

Menu → **Reports**. At the top are the cards of the reports available to you, below them the templates. A card has the report's name and one sentence about it; a click opens the report.

- **Filters** are at the top of a report page; press **{{ui:reports.show}}** to refresh the table. Under the filters it is written what the period counts by: _billing months_ (the month a charge belongs to in the ledger), _service days_ (the days a service was provided) or _reading dates_. They differ: an adjustment booked in March for January stands in March in "billing months" and in January in "service days".
- **Preview**: the screen shows the first 200 rows and the totals; the line "Showing first … of … rows. The Excel file has all of them" tells how many rows the report has in all.
- **{{ui:reports.download}}** gives a file with all rows and the same totals. It has one sheet per report (some reports have two), a header block (cooperative, report name, filters, date and time in Kyiv time, who made it) and a frozen header row; numbers are stored as numbers and dates as dates. The file is in the interface language. The file name is the report code and the period in Latin letters, for example `land-tax_2026-01-01_2026-12-31.xlsx`.
- **The limit**: a report with more than 20 000 rows on a sheet is not produced; you see "The report has more than 20 000 rows on a sheet. Narrow the filters and try again".
- A value that is not known (for example a reading at the start of the period) is shown **empty** with the reason, never as zero. {{ui:reports.plot-balances.title}} and {{ui:reports.charges-by-month.title}} have {{ui:charges.paid}} columns (the allocation of payments), {{ui:reports.plot-balances.title}} also {{ui:charges.prepayment}}; the separate {{ui:nav.payments}} report lists the payments themselves.
- Every report that is made is recorded in the audit log: who, which report, the filters and the row count; the content is not recorded.

## The eight reports

- **Charges by month** — What was charged in each month, by charge type. Filters: {{ui:reports.filters.params.fromMonth}}, {{ui:reports.filters.params.toMonth}}, charge types, street. A row is a month and a type: {{ui:nav.plots}}, {{ui:audit.outcome.RESOLVED}}, {{ui:import.entity.openingBalances}}, {{ui:reportTemplates.fields.rows.months.reversals}}, {{ui:reportTemplates.fields.rows.months.adjustments}} and {{ui:reportTemplates.fields.rows.months.effective}} (the amount you see on the month screen). The second sheet, **{{ui:reports.charges-by-month.sheets.byServicePeriod}}**, groups the same money by the month of the service.
- **Plot balances** — The debt of every active plot by charge type as of a date ({{ui:audit.fields.asOf}}, today by default), with the oldest unpaid charge, days overdue and status (red, yellow, green, grey). There are no owner names. For a past date the status is worked out with today's thresholds; the header says so.
- **Debtors** — Plots with a debt from an amount ({{ui:reports.filters.params.minDebt}}, by default the cooperative's red threshold) _or_ older than a number of days ({{ui:reports.filters.params.olderThanDays}}). Unlike the other reports, this one has the **names, phones and e-mails of the owners**: personal data. So the report needs the {{ui:permission.MEMBERS_VIEW.name}} permission as well, and making it is recorded in the audit log. Do not pass the file on to outsiders.
- **Electricity by meter tree** — Electricity from the substation down to the plots for the range {{ui:audit.fields.from}}-{{ui:audit.fields.to}} (the previous month by default, service days). The **{{ui:charges.detail.consumption}}** sheet: one tree node per row (indented by level), readings at the start and end with their type ({{ui:reports.electricity-tree.valueKinds.REAL}}, {{ui:reports.electricity-tree.valueKinds.CALCULATED}}, {{ui:reports.electricity-tree.valueKinds.UNKNOWN}}), consumption, {{ui:billing.losses.columns.children}} (the sum of the consumption of the nodes below it), **losses in kWh and %** (the node's consumption minus the sum below it; only for substations and quarter meters whose values are known at both ends) and {{ui:reportTemplates.fields.rows.meters.plotsFed}}. When a value is unknown, everything that depends on it in the row is **empty** with the reason, not zero. The **{{ui:audit.outcome.RESOLVED}}** sheet shows what was booked in the ledger for the range: plots' consumption (kWh, price, amount), losses (a fixed percent or runs per node), common-consumer runs with their split, plots and plot-days; adjustments and reversals are separate rows with the original service period. The totals are {{ui:reports.totals.CONSUMPTION}}, {{ui:reports.totals.LOSSES}} and {{ui:reports.totals.COMMON_CONSUMER}}. The header shows the current losses mode; a run booked under another mode shows what it booked.
- **Water** — Plots connected to water on the chosen days ({{ui:audit.fields.from}}, {{ui:audit.fields.to}}, {{ui:audit.fields.source}}: {{ui:reports.filters.options.waterSource.COOPERATIVE_SUPPLY}} or {{ui:reports.filters.options.waterSource.METER}}). A row has the source, {{ui:reports.water.columns.connectedDays}}, the meter, cubic metres for connected days only, and the charges by kind: {{ui:reports.water.columns.consumption}}, {{ui:reports.water.columns.fixedFee}}, {{ui:reports.water.columns.pumpShare}}, with a separate column for reversals and adjustments. The {{ui:reports.water.sheets.summary}} sheet totals plots, plot-days, cubic metres and amount. Plots that were not connected are not listed.
- **Missing readings** — Meters with no _approved_ reading in the range; filters: dates, resource (electricity, water), meter kind (plot meters by default), street. A row has the resource, kind, plot or name, serial number, last reading (date and value), days since it and the mark {{ui:reports.missing-readings.columns.pending}} (such a meter stays in the list). Not listed: a water meter of a plot that was not connected on any day of the range; a meter installed after the range or removed before it; and also a meter installed or removed _inside_ the range, which is treated as having had a point to read. Under the table is how many open {{ui:audit.split.NOT_CHARGED}} items there are for these meters, with a link to the list.
- **Land tax** — The filter is {{ui:reports.filters.params.year}}. A row is a plot the tax applies to: area in m² and sotkas, rate per sotka, {{ui:reports.land-tax.columns.expected}} (the amount the system expects for the year) and {{ui:reports.land-tax.columns.charged}}. If two rates applied in the year, the plot has one row per rate with its days. A plot whose expected and charged amounts differ is marked in {{ui:reports.land-tax.columns.mismatch}}, and a hint under the table says that a recalculation may be due (the report books nothing itself). Example: the rate is 35.00 ₴ per sotka per year and the plot is 600 m² = 6 sotkas → 6 × 35.00 = **210.00 ₴** a year (17.50 ₴ a month).
- **Corrections** — Every reversal and adjustment of the chosen billing months (filters: months, charge types, {{ui:charges.kind.REVERSAL}} or {{ui:charges.kind.ADJUSTMENT}}). A row: booked on, month, plot, type, the original charge (period and amount), the amount, the source ({{ui:charges.source.MANUAL_REVERSAL}}, {{ui:audit.entityType.RECALCULATION}}, {{ui:reports.corrections.sources.RUN_REVERSAL}}), the recalculation cause, the reason and who booked it. It is a ready list of changes of the past for a general meeting. When there are no corrections, the report says so.

> [!important]
> **The same pump share in two reports**
>
> The electricity of a well pump is split among its plots as a common-consumer row (the page [Water and connections](help:how-water-connection)). That row is on the {{ui:audit.outcome.RESOLVED}} sheet of the electricity report _and_ in the {{ui:reports.water.columns.pumpShare}} column of the {{ui:importColumns.plots.water}} report. It is the same amount seen from two sides: do not add the two reports together.

Reports are easy to open from other screens: on a billing month the {{ui:audit.entityType.REPORT}} link opens {{ui:reports.charges-by-month.title}} for exactly that month, and a plot account has a link to the balances report for that plot's street.

## Four cash book reports

Besides the eight reports, the {{ui:nav.reports}} screen has {{ui:reports.cash-book.title}}, {{ui:reports.bank-movements.title}}, {{ui:reports.income-expense.title}} and {{ui:reports.totals.expenses}}. They work the same way (filters, preview, Excel), but need only the {{ui:permission.CASHBOOK_VIEW.name}} permission. They are described on the page [Reports](help:reports), the rules on the page [Income and expenses and the reconciliation act](help:how-income-expenses).

## Templates: your own Excel form

If you need your own form (a statement for the meeting, a report for the tax office), prepare it in Excel as usual, and where a value from the system should stand, write a **placeholder** such as `${name.of.field}`. The system replaces placeholders with data and keeps your formats, merged cells, column widths and formulas. The full list of placeholders is on the **{{ui:reportTemplates.title}}** page, in the {{ui:reportTemplates.catalog.title}} block: type, description, example, a search box and a {{ui:reportTemplates.catalog.copyButton}} button.

- **A placeholder alone in a cell** (`${rows.plots.landTax.expected}` and nothing else) is filled with a number or a date, not text, so formulas over it work. Give the cell a date format for a date. **A placeholder inside text** ("Land tax for ${period.year}") is filled as text in Ukrainian number and date format.
- **Single values**: `${cooperative.name}`, `${cooperative.officialName}`, `${cooperative.iban}` (requisites from the settings), `${period.from}`, `${period.to}`, `${period.year}` (the year of the last day of the period), `${totals.debt}`, `${totals.landTax.expected}` and other totals.
- **A repeating row**: when a row holds placeholders of one set — `rows.plots.*` (plots), `rows.months.*` (billing months), `rows.meters.*` (electricity meters) or `rows.water.*` (water) — the row is repeated once per row of the set. **One repeating row per sheet**; two sets in one row or two repeating rows on a sheet are refused.
- Names and contacts of people are not available in templates: only data of the cooperative, plots and meters, and amounts.

> **A small example: the land tax statement**
>
> The template is an Excel table (rate 35.00 ₴ per sotka per year; plots of 600, 800 and 1000 m²). What you write in the template:
>
> | Row | A | B | C |
> |---|---|---|---|
> | 1 | `${cooperative.officialName}` |
> | 2 | Land tax for `${period.year}` |
> | 4 | Plot | Area, m² | Tax for the year, ₴ |
> | 5 | `${rows.plots.number}` | `${rows.plots.area}` | `${rows.plots.landTax.expected}` |
> | 6 | Total |  | `=SUM(C5:C5)` |
>
> What you get for the period 01.01-31.12.2026 (row 5 is repeated for every plot):
>
> | Plot | Area, m² | Tax for the year, ₴ |
> |---|---|---|
> | 1 | 600 | 210.00 |
> | 2 | 800 | 280.00 |
> | 3 | 1000 | 350.00 |
> | Total |  | 840.00 |
>
> An area of 600 m² is 6 sotkas, 6 × 35.00 = 210.00; likewise 8 × 35.00 = 280.00 and 10 × 35.00 = 350.00. The formula `=SUM(C5:C5)` under the repeating row was turned by the system into `=SUM(C5:C7)`: it covers all three copies, so {{ui:tariffs.expected.total}} equals the sum of the rows. The title in row 1 is the full name of the cooperative from the requisites, and row 2 reads "Land tax for 2026".

## What the system does with a template

- **An unknown placeholder** (a typo, or a field that is not in the catalogue) **does not disappear**: it stays in the cell as written, and a **{{ui:billing.losses.warningsLabel}}** sheet at the end of the file names the cell and the reason. The same goes for a placeholder whose value is unknown (no reading, a requisite not entered): the cell is empty, not zero, and it is listed in the warnings.
- The system does **not evaluate** formulas: it writes the placeholders and leaves the formulas to you. Excel or LibreOffice recalculates them when you open the file; a viewer that does not calculate formulas shows them empty.
- A valid file is an ordinary `.xlsx` workbook **up to 2 MB**. Files with macros (`.xlsm`), active content or embedded objects, damaged files and files that are "too large when unpacked" are refused and nothing is stored. Links to other files are removed from a template with a warning. Charts that refer to the repeating row are not shifted (a warning).

## Versions, the archive and the tax report

- **Add a template**: a name, a description, a file. Uploading a new file into an existing template ({{ui:reportTemplates.uploadVersion}}) makes **version 2, 3…**; the latest version is always the one used, and older ones can be downloaded under {{ui:tariffs.card.history}} ({{ui:reportTemplates.download}}). The generated file's name contains the template's name; the file properties and the audit log record the template and its version.
- **Archive**: the template is no longer offered for generation and takes no new versions, its name becomes free, and its versions stay downloadable.
- **Tax report** is a separate card with the status {{ui:reportTemplates.tax.notAdded}} until the chairman uploads the form with {{ui:reportTemplates.tax.upload}}. The official form will be added as an ordinary template, without changing the program; when the form changes, upload a new version. Fields the official form may be missing will be added by a later feature.
- **{{ui:reportTemplates.generate.button}}** next to an active template opens the {{ui:reportTemplates.generate.title}} window: {{ui:audit.fields.from}} and {{ui:audit.fields.to}} (both days are included, the period is up to one year). {{ui:reportTemplates.generate.submit}} makes the file and downloads it. The system **does not keep** generated files. If there are warnings you see "The file was downloaded. Warnings in the file: …" with the first of them; the full list is on the {{ui:billing.losses.warningsLabel}} sheet. The treasurer generates from the {{ui:nav.reports}} section, the chairman from the templates page.

## Cooperative requisites

**Settings** has a **{{ui:settings.requisites.title}}** block (changing it needs the {{ui:permission.SETTINGS_MANAGE.name}} permission): {{ui:settings.requisites.officialName}}, {{ui:settings.requisites.legalAddress}}, {{ui:settings.requisites.bankName}}, {{ui:settings.requisites.iban}} and {{ui:settings.requisites.edrpou}}. The IBAN must be a valid Ukrainian IBAN (otherwise {{ui:settings.requisites.errors.ibanInvalid}} and nothing is saved). The EDRPOU code is read-only — {{ui:settings.requisites.edrpouHint}}. These are public data of the legal entity, not personal data; changes are recorded in the audit log. These are the data that go into `${cooperative.officialName}`, `${cooperative.iban}` and so on.
