---
id: payments-enter
title: Enter a payment
description: How the treasurer enters a cash or bank payment, checks the proposed allocation to charges and gets a receipt number.
kind: screen
routes:
  - /payments/new
permissions:
  - PAYMENTS_ENTER
section: payments-cash
order: 20
shots:
  - name: payment-form
    user: treasurer
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '9' }
    steps:
      - { link: Enter payment }
      - { fill: { label: Amount, value: '1500' } }
      - { table: Allocation of the payment to charges }
---

## What the screen is for

The treasurer records money received here: cash at the cooperative's till or a bank transfer. The system allocates the payment to the plot's debts. After that, the debt, the plot colour on the map and the reports take this payment into account.

## Who sees it

The chairman and the treasurer see it: they have the right "Enter payments". Other roles have no entry for entering payments. If you are not among them and need to enter a payment, ask the chairman to give you this right on the [Roles](help:roles) page.

## Every element

Open the screen with the {{ui:payments.enter}} button in the payment list or on a plot card. From a plot card the plot is already chosen.

### Form fields

| Field | What to enter |
|---|---|
| {{ui:payments.form.plot}} | The plot number. Common land and an archived plot do not accept payments. The {{ui:payments.form.changePlot}} button lets you choose another plot. |
| {{ui:payments.form.source}} | {{ui:payments.source.CASH}} or {{ui:payments.source.BANK}}. |
| {{ui:payments.form.receivedOn}} | Today by default. You can enter another date, but the warning "{{ui:payments.form.dateWarning}}" appears. A future date is not accepted. |
| {{ui:payments.form.amount}} | More than zero, at most two decimals, for example 1250.50. |
| {{ui:payments.form.payer}} | Optional. The plot owners come first ({{ui:payments.form.payerOwners}}), then {{ui:payments.form.payerOther}}. The option {{ui:payments.form.payerNone}} leaves the field empty. |
| {{ui:payments.form.note}} | Optional, up to 200 characters, for example the name of a relative who brought the money. This is personal data, so the note is stored encrypted and only a person who also has the right "View members" can see it. |
| {{ui:payments.form.bankReference}} | Only for the source "{{ui:payments.source.BANK}}". Optional, up to 64 characters. |

### The proposed allocation

As soon as the plot and the amount are known, the table "{{ui:payments.proposal.caption}}" appears under the form. It lists the plot's unpaid charges: {{ui:payments.proposal.charge}}, {{ui:payments.proposal.period}}, {{ui:payments.proposal.open}} and the {{ui:payments.proposal.allocate}} field. The {{ui:payments.proposal.prepayment}} row shows what is left over the debts.

The system pays the oldest debt first. If debts are from the same day, it follows the order of charge types set in the cooperative.

- You can change the amount in any row.
- The {{ui:payments.proposal.add}} button adds another open charge of this plot.
- At the bottom you see how much is allocated: "{{ui:payments.proposal.allocatedTotal}} X {{ui:payments.proposal.of}} Y".

> [!important]
> When automatic allocation of the prepayment is on in the settings, rows you leave empty do not become a prepayment: the free part of the payment goes straight to the oldest debts of the plot. The note "{{ui:payments.proposal.automaticNote}}" then stands under the "{{ui:payments.proposal.prepayment}}" row. A prepayment remains only when the plot has no open debts.

### The mode "Several plots of one payer"

If a member brings one sum for several of their plots, choose the mode "{{ui:payments.form.modeSplit}}", then enter the payer and the amount. The form shows the payer's plots with their debts in the table "{{ui:payments.split.title}}" and proposes parts in proportion to the debts. Cents that do not divide go to the plots with the largest balances. You can change any part, but together they must equal the payment amount. Each part becomes a separate payment of its plot; a cash group has one receipt number.

### Saving

The {{ui:payments.form.save}} button records the payment. A payment is never deleted or edited: a mistake is corrected by [a reversal on the payment card](help:payment-card).

- For cash the screen shows "{{ui:payments.saved.title}}" and the receipt number, for example "Receipt № 42", and the {{ui:payments.saved.another}} button. For a bank payment it shows the saved payment.
- Receipt numbers run through the whole cooperative, with no gaps and no repeats, even when two treasurers enter cash at the same time. The receipt is internal; it is not a fiscal receipt.
- The {{ui:payments.saved.openCard}} button opens the payment card.

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:payments.problem.PLOT_COMMON_LAND}} | Payments for common land are not entered. Choose a plot with an owner. |
| {{ui:payments.problem.PLOT_ARCHIVED}} | The plot is archived. A payment cannot be entered for it. |
| {{ui:payments.form.amountInvalid}} | Correct the amount: a number with at most two decimals. |
| {{ui:payments.proposal.outdated}} | Someone changed the plot account between the preview and saving. Review the allocation again and save. |
| The amount in a row is more than the charge balance | A message "More than the remaining" stands by the row. Reduce the amount to the balance. |

If any allocation row is wrong, the table stays on the screen, a message stands by the row, and you can save as soon as there are no errors. The total allocated cannot be more than the payment.

### A payment for a past month

If money arrived on 31 January and you enter it on 3 February, and January is already closed, the payment keeps the date 31.01 but counts in February. January totals do not change.

## Worked example

A plot has two debts: the membership fee for January 600.00 and electricity for February 250.00. The order of types in the cooperative: membership fee, then electricity.

1. Open the plot card and press {{ui:payments.enter}}.
2. Choose the source "{{ui:payments.source.CASH}}" and enter the amount 700.00.
3. Look at the proposal: fee 600.00 and electricity 100.00, prepayment 0.00. The oldest debt is paid first.
4. Press {{ui:payments.form.save}}. The screen shows the receipt number.

Afterwards the electricity balance is 150.00, and this is the whole debt of the plot. Had the payment been 1,000.00, both debts would be paid in full and 150.00 would remain as a prepayment: the plot is green even though a prepayment exists.

## Screenshots

![The payment form with the proposed allocation of 1,500.00](shot:payment-form)

## Related pages

- [Payment list](help:payments)
- [Payment card: reversal and reallocation](help:payment-card)
- [Plot card](help:plot-card)
- [How the system allocates payments](help:how-payments-allocate)
