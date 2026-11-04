---
id: payments
title: "Payments"
description: "The list of the cooperative's payments, newest first: filters by date, source, plot and accounting month, the Reversed mark and links to the payment card."
kind: screen
routes:
  - /payments
permissions:
  - PAYMENTS_VIEW
section: payments-cash
order: 10
shots:
  - name: payments
    user: treasurer
    path: /payments
---

## What the screen is for

Here you see all the payments entered in the cooperative: cash at the till and bank transfers. Payments go from newest to oldest. From here you can enter a new payment or open the [payment card](help:payment-card).

A payment is never deleted. If it is reversed, it stays in the list with the mark "{{ui:payments.reversed}}".

## Who sees it

The screen is seen by those who have the right "{{ui:permission.PAYMENTS_VIEW.name}}": the chairman and the treasurer. The "{{ui:payments.enter}}" button is seen by those who also have "{{ui:permission.PAYMENTS_ENTER.name}}" (the chairman and the treasurer). Only a person with "{{ui:permission.PAYMENTS_REVERSE.name}}" can reverse a payment; by default that is the chairman.

A member does not see this screen. A member sees their own payments in the [account](help:account), and only for their own plots.

## Every element

### The "{{ui:payments.enter}}" button

On the right in the header. It opens the [payment entry form](help:payments-enter). Without the right to enter payments the button is not there.

### Filters

The filters are above the table. You can combine them. Whatever you choose stays in the page address, so you can send a link to a filtered list to a colleague.

| Filter | What it does |
|---|---|
| {{ui:payments.filters.from}} and {{ui:payments.filters.to}} | Show payments by the date the money arrived. You can enter just one of the dates. |
| {{ui:payments.filters.month}} | Shows the payments of one accounting month. The accounting month may differ from the receipt date if the money arrived in an already closed month. The option "{{ui:payments.filters.allMonths}}" removes the restriction. |
| {{ui:payments.filters.source}} | "{{ui:payments.source.CASH}}", "{{ui:payments.source.BANK}}" or "{{ui:payments.filters.allSources}}". |
| {{ui:payments.filters.plot}} | The field "{{ui:payments.filters.plotSearch}}": start typing a number and choose the plot. The button "{{ui:payments.filters.clearPlot}}" clears the choice. |

When no payments match, the screen says "{{ui:payments.nothingFound}}". If there are no payments at all, it says "{{ui:payments.empty}}".

### The payment table

| Column | What is in it |
|---|---|
| {{ui:payments.columns.reference}} | The receipt number for cash ("{{ui:payments.receiptNumber}}") or the bank document number. If there is neither, the date is shown. It is a link to the payment card. |
| {{ui:payments.columns.receivedOn}} | The day the money arrived. |
| {{ui:payments.columns.plot}} | The plot number. The link opens the plot card. |
| {{ui:payments.columns.payer}} | Who paid, if entered. |
| {{ui:payments.columns.source}} | {{ui:payments.source.CASH}} or {{ui:payments.source.BANK}}. |
| {{ui:payments.columns.amount}} | The payment amount. |
| {{ui:payments.columns.allocated}} | How much of this amount is allocated to charges. |
| {{ui:payments.columns.prepayment}} | How much is left over the debts. |

Marks may stand by the number.

- "{{ui:payments.reversed}}": the payment is reversed, its money is taken off the charges.
- "{{ui:payments.groupMarker}}": the payer brought one sum for several of their plots. Each plot has its own row, and the card shows all the parts.

At the bottom there is a page switch: 20 payments per page.

## Worked example

The treasurer wants to find all cash payments of plot 9 for the current month.

1. Open "Payments".
2. In the filter "{{ui:payments.filters.source}}" choose "{{ui:payments.source.CASH}}".
3. In the filter "{{ui:payments.filters.plot}}" type 9 and choose the plot.
4. In the filter "{{ui:payments.filters.month}}" choose the current month.
5. Press the receipt number in the row you need: the payment card opens.

The payment of plot 9 in the demo data is a bank payment and is paid only in part, so with the source "{{ui:payments.source.BANK}}" this payment is in the list, and with "{{ui:payments.source.CASH}}" it is not.

## Screenshots

![The payment list with filters](shot:payments)

## Related pages

- [Enter a payment](help:payments-enter)
- [Payment card: reversal and reallocation](help:payment-card)
- [How the system allocates payments](help:how-payments-allocate)
- [Cash and bank](help:cashbook)
