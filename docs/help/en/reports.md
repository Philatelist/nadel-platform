---
id: reports
title: "Reports"
description: "The list of the cooperative's reports: charges, debts, debtors, electricity, water, readings, land tax, corrections, payments and four cash reports; who sees which."
kind: screen
routes:
  - /reports
permissions:
  - REPORTS_VIEW
  - CASHBOOK_VIEW
section: reports
order: 10
shots:
  - name: reports
    user: chairman
    path: /reports
---

## What the screen is for

Reports gather into one table what the system already knows: charges, payments, debts, electricity, water, corrections, the cash. Each report is shown on the screen and can be downloaded as an Excel file. Here you choose which report to open, and also the templates a report can be written into.

A report does not calculate anything "its own way": it only selects, groups and adds up the sums already posted. So any sum in a report can be checked against the plot account or the charges month screen to the kopeck. Sums in reports are always effective sums: charges plus adjustments minus reversals.

## Who sees it

Reports are opened and downloaded, and files by templates are generated, by those who have "{{ui:permission.REPORTS_VIEW.name}}": the chairman and the treasurer. The four cash reports need only "{{ui:permission.CASHBOOK_VIEW.name}}". For example, a custom role "Audit committee" with just this right sees exactly these four reports and no others.

| Report | The right needed |
|---|---|
| Most reports | "{{ui:permission.REPORTS_VIEW.name}}" |
| "{{ui:reports.debtors.title}}" | also "{{ui:permission.MEMBERS_VIEW.name}}" (the report has names and phones) |
| "{{ui:reports.payments.title}}" | also "{{ui:permission.PAYMENTS_VIEW.name}}" (the payer note is seen only by a person with "{{ui:permission.MEMBERS_VIEW.name}}") |
| "{{ui:reports.cash-book.title}}", "{{ui:reports.bank-movements.title}}", "{{ui:reports.income-expense.title}}", "{{ui:reports.expenses.title}}" | "{{ui:permission.CASHBOOK_VIEW.name}}" |
| Uploading, replacing and archiving templates | "{{ui:permission.REPORT_TEMPLATES_MANAGE.name}}", by default the chairman only |

A role with "{{ui:permission.REPORTS_VIEW.name}}" but without "{{ui:permission.MEMBERS_VIEW.name}}" sees all reports except "{{ui:reports.debtors.title}}". A member and an electrician do not see the "{{ui:nav.reports}}" menu item and get a message that there is no access at a direct address.

## Every element

### Report cards

At the top are the cards of the reports available to you. A card has the report name and one sentence about it; a click opens the [report](help:report). If nothing is available, it says "{{ui:reports.empty}}".

The list of reports:

- "{{ui:reports.charges-by-month.title}}": what was charged in each month by charge type.
- "{{ui:reports.plot-balances.title}}": the debt of each active plot on a date.
- "{{ui:reports.debtors.title}}": plots with a debt, with names and contacts of the owners.
- "{{ui:reports.electricity-tree.title}}": electricity from the substation to the plots.
- "{{ui:reports.water.title}}": plots connected to water and charges for water.
- "{{ui:reports.missing-readings.title}}": meters without a confirmed reading for a period.
- "{{ui:reports.land-tax.title}}": expected and charged land tax for a year.
- "{{ui:reports.corrections.title}}": all reversals and adjustments of the chosen months.
- "{{ui:reports.payments.title}}": each payment, with totals by source.
- "{{ui:reports.cash-book.title}}", "{{ui:reports.bank-movements.title}}", "{{ui:reports.income-expense.title}}", "{{ui:reports.expenses.title}}": the four cash reports.

For details of each report, its filters and columns, see the [Report](help:report) page.

### Templates

- Those who have "{{ui:permission.REPORTS_VIEW.name}}" but cannot manage templates see the section "{{ui:reportTemplates.generate.listTitle}}" with ready templates: the button "{{ui:reportTemplates.generate.button}}" by a template generates a file for the chosen period.
- The chairman sees the card "{{ui:reportTemplates.cardTitle}}", a link to the [templates page](help:report-templates), where they are uploaded and archived.

### Useful links from other screens

On a charges month the link "{{ui:reports.monthReportLink}}" opens "{{ui:reports.charges-by-month.title}}" for that very month. On a plot account there is a link to the debt report for the street of that plot.

> [!important]
> The report "{{ui:reports.charges-by-month.title}}" has two sheets: "{{ui:reports.charges-by-month.sheets.byBillingMonth}}" and "{{ui:reports.charges-by-month.sheets.byServicePeriod}}". The first groups the money by the month in which the charge was booked into the ledger. The second groups the same money by the month the service was provided for. These are different things: a reading taken in October may carry electricity for August and September. In the first sheet this money stands in October, in the second in August and September.

## Worked example

The chairman prepares an electricity report for the general meeting.

1. He opens "{{ui:nav.reports}}" and presses the card "{{ui:reports.electricity-tree.title}}".
2. On the report page he chooses the period and presses "{{ui:reports.show}}".
3. He presses "{{ui:reports.download}}" and gets an Excel file.

For a report on the sum charged for August the chairman opens "{{ui:reports.charges-by-month.title}}" and looks at the second sheet: "{{ui:reports.charges-by-month.sheets.byServicePeriod}}" will also hold the electricity whose reading was taken in October but which belongs to August.

## Screenshots

![The Reports screen: report cards and templates](shot:reports)

## Related pages

- [Report: filters, preview and Excel](help:report)
- [Report templates](help:report-templates)
- [Cash and bank](help:cashbook)
- [Charges of a month](help:charges-month)
- [How the reports work](help:how-reports)
