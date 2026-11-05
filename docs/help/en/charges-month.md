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

The block "{{ui:invoices.monthPanel.title}}" builds the invoices of the plots for this month and starts sending them. Invoices can be built for an open month too; the board usually does it right after the month's charges. The block is used by those who may build invoices (by default the chairman).

> [!important]
> Until the IBAN and the ЄДРПОУ code of the cooperative are filled in, invoices are not built. The message "{{ui:invoices.requisites.title}}" names what is missing and leads to the settings: the chairman enters the IBAN in Settings → Requisites ({{ui:invoices.requisites.ibanLink}}), the ЄДРПОУ code is set by the platform operator. Details: [Cooperative settings](help:settings).

#### Build the invoices

First the block shows the preview "{{ui:invoices.monthPanel.previewTitle}}": how many plots can get an invoice ("{{ui:invoices.monthPanel.invoiceable}}": active plots, not common land, with a current owner), how many of them already have an invoice for this month ("{{ui:invoices.monthPanel.existing}}") and what the build will do. The summary uses the same words as the result: "To be built: 18. New versions: 0. Unchanged: 1. Skipped: 6." A plot that already has an invoice gets a new version only when its figures changed. Plots with no debt are skipped. The switch "{{ui:invoices.monthPanel.includeNothingDue}}" adds plots with no debt; their invoice has no QR code.

Press "{{ui:invoices.monthPanel.build}}" and wait: an indicator is shown while the build runs, then the result: how many invoices were built, how many got a new version, how many did not change and how many were skipped. If nobody entered anything between the preview and the build, the result equals the preview. The result stays in place of the preview until you change the month or the switch.

Building again creates nothing while nothing has changed. If somebody else is already building the same month, you will see a message about it, and nothing is created twice.

#### Invoice versions

The system keeps every invoice as it was built: later it opens with the same figures and the same owners, even after a payment or a change of owner. After a payment, a correction or a recalculation the treasurer builds the month again. A plot whose figures changed gets a new version ("version 2") with the same invoice number; a plot where nothing changed gets no new version. Older versions stay available: the lists show the latest one, and pressing "{{ui:invoices.earlierVersions}}" unfolds the earlier ones. A member sees only the latest version. An invoice is never deleted.

#### The list of the month's invoices

Below is the list "{{ui:invoices.monthPanel.listTitle}}": one row per plot with the latest version (plot, version, "{{ui:invoices.due}}", build date and a link to the PDF). Older versions of a plot are folded under "{{ui:invoices.earlierVersions}}". With no invoices yet it says "{{ui:invoices.monthPanel.listEmpty}}". Each row has a "{{ui:invoices.send.invoice}}" button that sends this one invoice to the owners of the plot.

"{{ui:invoices.monthPanel.print}}" downloads one PDF with the latest versions of the invoices, one per A4 sheet, in the order of streets and plots. It is for members who chose paper delivery. The switch "{{ui:invoices.monthPanel.scope}}" chooses between "{{ui:invoices.monthPanel.scopePAPER}}" and "{{ui:invoices.monthPanel.scopeALL}}".

#### Check the QR code before handing out

The code follows the rules of the National Bank of Ukraine. Before you hand out invoices, check one code with a phone: open the QR scanner in a banking app, point it at the code on the screen or on paper and check the recipient, the IBAN, the payment purpose and the amount as described on [How to read my invoice](help:member-bill#the-qr-code). The other fields in the bank are locked; close the form without paying.

#### Send the invoices of the month

The button "{{ui:invoices.monthPanel.send}}" does not send anything at once. First the window "{{ui:invoices.send.title}}" shows the preview "{{ui:invoices.send.previewTitle}}": how many members get the invoice in Telegram, how many by email and how many paper copies must be printed. The switch "{{ui:invoices.send.includeNothingDue}}" adds plots with nothing to pay: such a member gets "No debt, thank you!" and the PDF. If everything has been sent already, the window says "{{ui:invoices.send.nothingNew}}".

"{{ui:invoices.send.confirm}}" puts the messages in the queue and returns you at once; the progress is on the [Delivery](help:deliveries) screen. In Telegram the member gets "Invoice for October 2026, plot 9: due 413.26 ₴", the PDF and a "Pay" button (it opens the banking app through the QR link of the invoice). The email has the same text, the PDF attached and the signature "The board …".

Sending the same invoices of a month again duplicates nothing: the same version of an invoice is received by the same member through the same channel once. If an invoice was reissued as a new version, the new one goes out. Paper invoices are marked "to print": print the PDF for printing (every copy has its own QR code for connecting Telegram), then press "{{ui:deliveries.printDone}}" on the Delivery screen. Which channel an invoice takes is described on the [Delivery](help:deliveries) page, certificates on the [Certificates register](help:certificates) page.

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
