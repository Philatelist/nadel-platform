---
id: charges-month
title: "Month page: charge and close"
description: "One billing month: totals by type, the checklist before closing, charging the month with a preview, and closing the month."
kind: screen
routes:
  - /charges/months/:ym
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 60
shots:
  - name: charges-month
    user: chairman
    path: /charges
    open: { css: 'main a[href^="/charges/months/"]' }
  - name: charges-run
    user: chairman
    path: /charges
    open: { css: 'main a[href^="/charges/months/"]' }
    steps:
      - { click: Charge the month }
  - name: charges-close
    user: chairman
    path: /charges
    open: { css: 'main a[href^="/charges/months/"]' }
    steps:
      - { click: Close the month }
---

## What the screen is for

This is one billing month: how much is charged by type, how much came in and what is left to do before closing. Here the chairman charges the fixed tariffs for the month and closes the month.

## Who sees it

The chairman and the treasurer see the page (the "{{ui:permission.CHARGES_VIEW.name}}" permission). The button "{{ui:billing.run.action}}" needs the "{{ui:permission.CHARGES_RUN.name}}" permission, the button "{{ui:billing.close.action}}" needs "{{ui:permission.PERIODS_CLOSE.name}}"; by default only the chairman has them. The treasurer sees months but no actions.

## Every element

At the top are the charges tabs and the link "{{ui:billing.back}}". The page title is the month with its status. For a closed month, under the title it says when it was closed and by whom.

### Buttons at the top

| Button | What it does |
|---|---|
| {{ui:reports.monthReportLink}} | Opens the [charges-by-month report](help:reports) for this month. Shown if you have the right to reports. |
| {{ui:billing.run.action}} | Open month only. First shows a preview, see below. |
| {{ui:billing.close.action}} | Open month only. Asks for confirmation. |

### The block "{{ui:billing.totalsTitle}}"

The amounts by charge type, "{{ui:billing.total}}" and "{{ui:billing.received}}".

### The block "{{ui:billing.checklist.title}}"

This list helps you decide whether it is time to close the month:

- whether the month's charging is done ("{{ui:billing.checklist.runDone}}" or "{{ui:billing.checklist.runNotDone}}");
- how many items wait in the queue ["{{ui:billing.checklist.notCharged}}"](help:not-charged); the link leads to it;
- which meters have had no fresh readings for a long time (over 60 days): next to each, the plot number and the date of the last reading, or "{{ui:billing.checklist.noStale}}".

The list does not stop you from closing: the person decides, seeing everything.

### Invoices of the month

Below there is a block "Invoices of the month": here the invoices of the plots for a closed month are built and their sending is started. The sending itself and its results are described on the page [Delivery](help:deliveries), and certificates on the page [Certificates register](help:certificates).

### Charge the month

The dialog "{{ui:billing.run.title}}" first calculates what will be charged and writes nothing. It shows:

- "{{ui:billing.run.plots}}";
- "{{ui:billing.run.totalsTitle}}" by type;
- the block "{{ui:billing.run.notChargedTitle}}" with a hint: remove the cause and repeat in the "Not charged" section.

If there is nothing new, the dialog says "{{ui:billing.run.nothingNew}}". The button "{{ui:billing.run.confirm}}" books exactly what you saw. After that "{{ui:billing.run.done}}" appears. If something changed meanwhile, the system says "{{ui:problem.PREVIEW_OUTDATED}}", and the dialog offers the button "{{ui:billing.run.previewAgain}}".

Running again creates only what is missing: a plot or a tariff added later gets its charge, and there are no duplicates. A month after the current one cannot be charged: "{{ui:problem.PERIOD_IN_FUTURE}}".

### Close the month

The dialog "{{ui:billing.close.confirm}}" shows the list once more and asks for confirmation. A closed month is never reopened. Only the earliest open month can be closed, otherwise you get "{{ui:problem.PERIOD_CLOSE_ORDER}}". A charge that belongs to a closed month (for example, a late reading) lands in the earliest open month with a note "for September, charged in October".

> [!important]
> In a closed month nothing is corrected with the "{{ui:charges.reverse.action}}" button: it is not there. A mistake in a closed month is corrected by a [recalculation](help:recalculation-new), which adds an adjustment for the difference in the nearest open month.

## Worked example

There is an open month in which the chairman wants to charge the fixed tariffs.

1. Open "{{ui:nav.charges}}" and click the month name.
2. Click "{{ui:billing.run.action}}". The dialog shows the number of plots and the new charges by type: a membership fee of 25.00 ₴ and a land tax of 17.50 ₴ for a plot of 6 sotkas.
3. If there is something in the block "{{ui:billing.run.notChargedTitle}}", take note, but it will not stop you: click "{{ui:billing.run.confirm}}".
4. When all items are in order, click "{{ui:billing.close.action}}" and confirm.

In the demo the current month has already been charged, so the preview says there is nothing new.

## Screenshots

![The month page with the checklist before closing](shot:charges-month)

![The preview of charging the month](shot:charges-run)

![The confirmation of closing the month](shot:charges-close)

## Related pages

- [Charges: months](help:charges)
- [Not charged](help:not-charged)
- [New recalculation](help:recalculation-new)
- [Delivery](help:deliveries)
- [Certificates register](help:certificates)
- [How the system calculates charges and closing of months](help:how-charges-months)
