---
id: payment-card
title: "Payment card: reversal and reallocation"
description: "What the payment card shows, how to reverse a payment with a reason, reallocate money between charges, allocate a prepayment and print a receipt."
kind: screen
routes:
  - /payments/:id
permissions:
  - PAYMENTS_VIEW
section: payments-cash
order: 30
shots:
  - name: payment-card
    user: chairman
    path: /payments
    open: { css: 'main a[href^="/payments/"]:not([href$="/new"])' }
  - name: payment-reverse
    user: chairman
    path: /payments
    open: { css: 'main a[href^="/payments/"]:not([href$="/new"])' }
    steps:
      - { click: Reverse }
    element: { css: 'dialog[open]' }
---

## What the screen is for

The card shows one payment: where the money came from, what it is allocated to and who entered it. This is where a payment is corrected: **reverse a payment** that was entered by mistake, or reallocate the money between charges. The payment record itself is never deleted or edited.

## Who sees it

The card is seen by those who have the right "{{ui:permission.PAYMENTS_VIEW.name}}". The buttons "{{ui:payments.reallocate.action}}" and "{{ui:charges.allocatePrepayment}}" are seen by those who have "{{ui:permission.PAYMENTS_ENTER.name}}" (the chairman and the treasurer). The button "{{ui:payments.reverse.action}}" is seen only by those who have "{{ui:permission.PAYMENTS_REVERSE.name}}": by default the chairman. A treasurer who made a mistake asks the chairman.

The payer note is seen only by those who also have the right "{{ui:permission.MEMBERS_VIEW.name}}".

## Every element

### The title and buttons

The title is "{{ui:payments.card.titleReceipt}}" for cash, or the bank document number (or "{{ui:payments.card.titleBank}}") for a bank payment. If the payment is reversed, the mark "{{ui:payments.reversed}}" stands next to it.

| Button | When it is there | What it does |
|---|---|---|
| {{ui:receipts.print}} | Only for cash with a receipt number | Downloads the receipt PDF. For a payment split between plots the receipt is one, with all the plots. The receipt (A5, half of A4) holds the requisites of the cooperative, the number, the date, the plot, the payer, the amount in figures and in words, what the money was allocated to ("Allocation as of" the print date) and the name of the person who accepted the money, with a line for a signature. The note about the payer is printed only for users who may see member data. The receipt of a reversed payment carries the mark "REVERSED" with the date. It is an internal receipt, not a fiscal one. |
| {{ui:payments.reallocate.action}} | The payment is not reversed and can be reallocated | Moves money from some charges to others. |
| {{ui:charges.allocatePrepayment}} | Automatic allocation of the prepayment is off, the payment has a prepayment, and the plot has an open charge | Allocates the prepayment to open charges. |
| {{ui:payments.reverse.action}} | The payment can be reversed | Opens the reversal window. |
| {{ui:payments.back}} | Always | Returns to the payment list. |

### Payment data

A table with the fields: {{ui:payments.card.plot}}, {{ui:payments.card.receivedOn}}, {{ui:payments.card.billingMonth}}, {{ui:payments.card.source}}, {{ui:payments.card.bankReference}} (for bank), {{ui:payments.card.payer}}, {{ui:payments.card.payerNote}}, {{ui:payments.card.amount}}, {{ui:payments.card.allocated}} and {{ui:payments.card.prepayment}}.

If the payment is reversed, the rows {{ui:payments.card.reversalDate}}, {{ui:payments.card.reversalMonth}} and {{ui:payments.card.reversalReason}} are added. At the bottom of the page you see who entered the payment and when.

### Allocation

The allocation has two parts.

- "{{ui:payments.card.currentTitle}}": which charges the payment is allocated to now (charge, period, amount). If nowhere yet, it says "{{ui:payments.card.nothingNow}}".
- "{{ui:payments.card.historyTitle}}" (expands): every allocation and every return from a charge with date, amount, author and reason. Reasons for a return: "{{ui:payments.card.releaseCause.CHARGE_REVERSED}}", "{{ui:payments.card.releaseCause.CHARGE_ADJUSTED}}", "{{ui:payments.card.releaseCause.PAYMENT_REVERSED}}", "{{ui:payments.card.releaseCause.REALLOCATED}}". Where an allocation came from: "{{ui:payments.card.origin.ENTRY}}", "{{ui:payments.card.origin.REALLOCATION}}", "{{ui:payments.card.origin.PREPAYMENT_MANUAL}}", "{{ui:payments.card.origin.PREPAYMENT_AUTO}}". Records of the system are signed "{{ui:payments.card.system}}".

### Other parts of the payment

If the payer brought one sum for several plots, there is a table "{{ui:payments.card.groupTitle}}": the plot and the amount of each part. Each part is reversed separately, the rest stay.

### Reverse a payment

Press "{{ui:payments.reverse.action}}". In the window "{{ui:payments.reverse.title}}" enter the "{{ui:payments.reverse.reason}}" (up to 500 characters, for example "Wrong plot") and press "{{ui:payments.reverse.submit}}". Without a reason you see "{{ui:payments.reverse.required}}". The button "{{ui:payments.reverse.cancel}}" closes the window without changes.

What happens next:

- The system removes the whole allocation of the payment, and the charges it paid become debts again.
- The payment record stays; in the list it has the mark "{{ui:payments.reversed}}". A reversed cash payment keeps its receipt number.
- A line of the opposite amount appears in the cash or bank journal, dated the day of the reversal. The cash may become negative: then the cash card asks you to do a cash count.
- Owners who receive messages learn about the reversal if "notify about payments" is on in the settings.
- A payment of a plot in the archive can be reversed.
- A payment is reversed only once.

> [!important]
> A reversal cannot be undone. If you reversed a payment by mistake, enter it again as a new payment. A closed month does not change because of this: the reversal goes to the nearest open month.

### Reallocate

The button "{{ui:payments.reallocate.action}}" opens the window "{{ui:payments.reallocate.title}}". The window shows what is allocated now ("{{ui:payments.reallocate.current}}"), and below it a new proposal ("{{ui:payments.reallocate.proposal}}") that you can change the same way as when entering. The button "{{ui:payments.reallocate.submit}}" removes the old allocation and records the new one in one step. It goes to the change log. This is how money is moved between charges.

When automatic allocation of the prepayment is on in the settings, empty rows do not create a prepayment while the plot has open debts: the free sum goes to the oldest debts.

### Allocate a prepayment

A plot's prepayment is money entered for the plot but not allocated to any charge. If automatic allocation of the prepayment is on (it is by default), the prepayment goes to new charges by itself. If it is off, the prepayment waits until the treasurer allocates it. The button "{{ui:charges.allocatePrepayment}}" opens the window "{{ui:payments.allocatePrepayment.title}}" with a proposal over the open charges; you can change it and confirm with "{{ui:payments.allocatePrepayment.submit}}". Turning automatic allocation on does not allocate the old prepayment at once: it goes to the plot's next charges.

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:payments.reverse.required}} | Write the reason for the reversal. |
| {{ui:problem.PAYMENT_ALREADY_REVERSED}} | Refresh the page: the payment is already corrected. |
| {{ui:payments.proposal.outdated}} | Someone changed the plot account. Review the new allocation and save. |

## Worked example

The treasurer mistakenly entered 100.00 ₴ in cash for plot 9 instead of plot 19. He asks the chairman.

1. The chairman opens "Payments" and the card of this payment (the receipt is, say, № 42).
2. He presses "{{ui:payments.reverse.action}}", writes the reason "Wrong plot" and confirms.
3. The charges of plot 9 that this payment closed are a debt of 100.00 ₴ again. The payment stays in the list with the mark "{{ui:payments.reversed}}".
4. The treasurer enters 100.00 ₴ for plot 19 as a new payment. The new receipt has the next number, for example № 43.

Had the money been entered for the right plot but against the wrong charge, it would be enough to press "{{ui:payments.reallocate.action}}": the payment stays, only what it closes changes.

## Screenshots

![The payment card with the allocation and buttons](shot:payment-card)

![The payment reversal window with a reason](shot:payment-reverse)

## Related pages

- [Payment list](help:payments)
- [Enter a payment](help:payments-enter)
- [Cash and bank](help:cashbook)
- [How the system allocates payments](help:how-payments-allocate)
- [Delivery](help:deliveries)
