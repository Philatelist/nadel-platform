---
id: report
title: "Report: filters, preview and Excel"
description: "How to open a report, choose filters, press Show and download an Excel file; what each of the twelve reports of the cooperative shows."
kind: screen
routes:
  - /reports/:code
permissions:
  - REPORTS_VIEW
  - CASHBOOK_VIEW
section: reports
order: 20
shots:
  - name: report
    user: chairman
    path: /reports/charges-by-month
  - name: report-electricity
    user: chairman
    path: /reports/electricity-tree
---

## What the screen is for

This is one report: filters at the top, a table below and a button to download an Excel file. All reports are built the same way; the filters and columns differ.

## Who sees it

Reports are seen by those who have "{{ui:permission.REPORTS_VIEW.name}}". The four cash reports ("{{ui:reports.cash-book.title}}", "{{ui:reports.bank-movements.title}}", "{{ui:reports.income-expense.title}}", "{{ui:reports.expenses.title}}") need only "{{ui:permission.CASHBOOK_VIEW.name}}". The report "{{ui:reports.debtors.title}}" also needs "{{ui:permission.MEMBERS_VIEW.name}}", because it has the names, phones and addresses of owners. The report "{{ui:reports.payments.title}}" also needs "{{ui:permission.PAYMENTS_VIEW.name}}". If a report is not shown to you, a message that there is no access is shown instead.

## Every element

### The title and buttons

- "{{ui:reports.back}}" returns to the list of reports.
- "{{ui:reports.download}}" downloads an Excel file with all rows and the same totals. While the file is being prepared the button says "{{ui:reports.downloading}}". If something failed, you see "{{ui:reports.downloadFailed}}".

The Excel file has one sheet per report (some reports have two), a header (the cooperative, the report name, the filters, the date and time in Kyiv, who generated it), a frozen heading row; numbers are stored as numbers, dates as dates. The language of the file is the interface language. The file name is the report code and the period in Latin letters, for example land-tax_2026-01-01_2026-12-31.xlsx.

### Filters

The filters are above the table. Press "{{ui:reports.show}}" to update the table. Which filters there are depends on the report: "{{ui:reports.filters.params.fromMonth}}" and "{{ui:reports.filters.params.toMonth}}", "{{ui:reports.filters.params.from}}" and "{{ui:reports.filters.params.to}}", "{{ui:reports.filters.params.asOf}}", "{{ui:reports.filters.params.year}}", "{{ui:reports.filters.chargeTypes}}", "{{ui:reports.filters.street}}" and others. Your choice stays in the page address.

Under the filters it is written what the period is counted by.

| Text | What it is |
|---|---|
| {{ui:reports.basis.BILLING_MONTHS}} | The charging month: the month a charge belongs to in the ledger. |
| {{ui:reports.basis.SERVICE_DAYS}} | The days the service was provided. |
| {{ui:reports.basis.READING_DATES}} | The dates of meter readings. |
| {{ui:reports.basis.PAYMENT_DATES}} | The days payments arrived. |
| {{ui:reports.basis.MOVEMENT_DATES}} | The dates of cash and bank movements. |

These are different things: an adjustment posted in March for January stands in March in charging months, and in January in service days.

### The table

The screen shows the first 200 rows and the totals. The text "{{ui:reports.rowCount}}" says how many rows the report has in all. If there are no rows, you see "{{ui:reports.noRows}}". A value that is unknown (for example, a reading at the start of a period) is shown empty with a reason, not as zero. In the reports "{{ui:reports.plot-balances.title}}" and "{{ui:reports.charges-by-month.title}}" there are columns "Paid" (the allocation of payments), and the balances also have "Prepayment".

The limit: a report with more than 20,000 rows on a sheet is not generated: "{{ui:reports.errors.REPORT_TOO_LARGE}}" Every generation of a report is written to the change log: who, which report, the filters, the number of rows. The content is not recorded.

### Twelve reports

**{{ui:reports.charges-by-month.title}}.** What was charged in each month by charge type. Filters: "{{ui:reports.filters.params.fromMonth}}", "{{ui:reports.filters.params.toMonth}}", charge types, street. A row is a month and a type: "Plots", "Charged", "Opening balances", "Reversals", "Adjustments" and "Total charged" (the sum from the month screen). The first sheet is "{{ui:reports.charges-by-month.sheets.byBillingMonth}}", the second is "{{ui:reports.charges-by-month.sheets.byServicePeriod}}": the same money by the month of the service. A reading taken in October may carry electricity for August and September: in the first sheet it is in October, in the second in August and September.

**{{ui:reports.plot-balances.title}}.** The debt of each active plot by charge type as of a date ("{{ui:reports.filters.params.asOf}}", today by default), with the oldest unpaid charge, days overdue and the status (red, yellow, green, grey). There are no owner names. For a past date the status is computed with today's thresholds, as stated in the header.

**{{ui:reports.debtors.title}}.** Plots with a debt from a sum ("{{ui:reports.filters.params.minDebt}}", by default the cooperative's red threshold) or older than a number of days ("{{ui:reports.filters.params.olderThanDays}}"). Here there are names, phones and e-mail addresses of owners, so the report also needs "{{ui:permission.MEMBERS_VIEW.name}}". Do not pass the file to outsiders.

**{{ui:reports.electricity-tree.title}}.** Electricity from the substation to the plots for a period (by default last month, service days). The sheet "Consumption": a tree node per row, readings at the start and end with the type ("Actual", "Estimated", "Unknown"), consumption, "Below it, kWh", losses in kWh and %, and "Plots fed". If a value is unknown, everything that depends on it in the row is empty with a reason. The sheet "Charged" shows what was posted in the ledger for this period: plot consumption, losses, calculations of common consumers, adjustments and reversals in separate rows. The header states the current loss mode.

**{{ui:reports.water.title}}.** Plots connected to water on the chosen days (filters "{{ui:reports.filters.params.from}}", "{{ui:reports.filters.params.to}}", "{{ui:reports.filters.params.waterSource}}"). A row: source, days connected, meter, m³ only for the connected days and charges by type: "By meter", "Subscription", "Well pump share", with a separate column for reversals and adjustments. The sheet "By source" sums plots, plot-days, m³ and the amount. Plots that are not connected are not shown.

**{{ui:reports.missing-readings.title}}.** Meters that have no confirmed reading for the period. Filters: dates, resource, meter kind (by default only plot meters), street. A row: resource, kind, plot or name, serial number, last reading, days since, and the mark "Reading awaits confirmation". Under the table there is the link "{{ui:reports.notChargedLink}}".

**{{ui:reports.land-tax.title}}.** The filter "{{ui:reports.filters.params.year}}". A row is a plot the tax applies to: area in m² and in sotkas, the rate per sotka, "Expected" and "Charged for the year". Plots where these sums differ are marked in the column "Discrepancy", and under the table is a hint that a recalculation may be needed (the report posts nothing itself). Example: a rate of 35.00 ₴ per sotka per year, a plot of 600 m² = 6 sotkas, 6 × 35.00 = 210.00 ₴ per year.

**{{ui:reports.corrections.title}}.** All reversals and adjustments of the chosen charging months. Filters: months, charge types, "Reversal" or "Adjustment". A row: posting date, month, plot, type, original charge, amount, source, reason, basis and who posted it. This is a ready list of changes to the past for the general meeting.

**{{ui:reports.payments.title}}.** Each payment, with totals by source (cash, bank).

**Four cash reports.** They work the same way but need only "{{ui:permission.CASHBOOK_VIEW.name}}".

- "{{ui:reports.cash-book.title}}": the period; for each day the opening balance, every line (time, kind, receipt or document number, counterparty, receipt or expense) and the closing balance; totals for the period. The closing balance of each day equals the cash balance of that day on the [Cash and bank](help:cashbook) screen.
- "{{ui:reports.bank-movements.title}}": the same for the bank account, with the reconciliation status of each month.
- "{{ui:reports.income-expense.title}}": the report for the general meeting for a period. Income: member payments by charge type, "Prepayments" (the part of payments not allocated to any charge), reversals of earlier accepted payments as a separate negative row, other income by category. Expenses by category. Settled differences of counts and reconciliations as a separate row. Result = income − expenses + differences, and it equals the change of cash plus bank for the period. Also the cash and bank balances at the start and end.
- "{{ui:reports.expenses.title}}": a list of expenses with the filters "Period", "Category", "Account". On the screen each expense has a link to its files.

> [!important]
> The light of the well pump is split between its plots as a row of a common consumer. This row is on the sheet "Charged" of the electricity report and in the column "Well pump share" of the report "{{ui:reports.water.title}}". It is the same sum shown from two sides: do not add these two reports to each other.

## Worked example

The chairman opens "{{ui:reports.land-tax.title}}" for 2026. The rate is 35.00 ₴ per sotka per year, three plots: 600, 800 and 1000 m².

1. The filter "{{ui:reports.filters.params.year}}": 2026. He presses "{{ui:reports.show}}".
2. In the table the plots are 6, 8 and 10 sotkas: expected 210.00, 280.00 and 350.00 ₴, 840.00 ₴ in total.
3. The column "Discrepancy" is empty, so exactly as much was charged as expected.
4. "{{ui:reports.download}}": the file land-tax_2026-01-01_2026-12-31.xlsx with all rows.

## Screenshots

![The report "Charges by month": filters and table](shot:report)

![The report "Electricity by meter tree"](shot:report-electricity)

## Related pages

- [Reports](help:reports)
- [Report templates](help:report-templates)
- [Plot account and charges month](help:charges-month)
- [How the reports work](help:how-reports)
