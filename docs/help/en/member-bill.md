---
id: member-bill
title: "How to read my invoice"
description: "What the amounts in a plot invoice mean: opening debt, charged, paid, amount due, prepayment and the QR code, with a worked example."
kind: topic
routes: []
permissions:
  - member
section: member
order: 20
shots:
  - name: member-bill-invoices
    user: member
    path: /account
    element: { heading: Invoices }
---

The invoice for a month reaches you by e-mail, in Telegram or on paper. You can also download it in your [account](help:account), in the {{ui:invoices.myTitle}} block. The invoice invents nothing: it takes the amounts from the account of your plot, and only the system calculates them.

![Invoices in the account](shot:member-bill-invoices)

## What is in an invoice

- The name of the cooperative and its requisites.
- The invoice number, for example `2026-10/9`: year, month and plot number. If the board issued the invoice again, "(version 2)" is added to the number. The number stays the same.
- Your plot, the street and the owners with their shares.
- A table with one line for each charge type. It has the debt at the start of the month, charged, paid and the debt at the end.
- Under "{{ui:audit.outcome.RESOLVED}}" are the details people ask about most. Every electricity interval is written as one span of reading dates with both readings, the kWh and the price, for example "readings 10.08.2026 → 03.10.2026: 3 435,900 → 3 705,500 = 269,600 kWh × 4.32 ₴". Losses and common consumers stand separately, as do the basis of a fixed tariff and, for a correction, the month it corrects.
- The payments of the month (the block "Payments for October 2026"): the date, the receipt number or the bank reference and the amount. You can check them against your bank. A reversed payment is shown as the payment itself and a line "payment reversal" with a negative amount.
- Payments received after the month but before the invoice was built ("Received after October"). They reduce the "{{ui:invoices.due}}" amount.
- The total: the debt at the end of the month, the prepayment and the "{{ui:invoices.due}}" amount. Next to it is a QR code if "{{ui:invoices.due}}" is above zero, with the line "Pay in your bank app": on a phone, a tap opens the transfer in the bank without scanning.

Every invoice fits one A4 page. A very long invoice continues on a second page, whose header repeats the invoice number and the plot. An invoice for a month that is not closed yet carries the mark "as of" a date. How the figures come about: [Invoice numbers](help:how-invoice-numbers).

## How to read the amounts

| Line | What it means |
|---|---|
| Debt at the start | What was unpaid before the month began |
| {{ui:audit.outcome.RESOLVED}} | Everything the system charged for the month |
| {{ui:charges.paid}} | The payments counted in this month |
| Debt at the end | What is unpaid after the month |
| {{ui:invoices.due}} | The debt at the end of the month minus the money received after the month and before the invoice was made. It is never below zero |
| {{ui:charges.prepayment}} | Money paid in advance above the debt |

A payment clears the oldest charges first, so the lines of the table can hold debt that is not from the current month. More: [How the system allocates payments](help:how-payments-allocate).

## Worked example

Plot 9. The invoice for October 2026.

1. There is no earlier debt. For October the membership fee is 75.00 and electricity is 338.26. There are no payments. Debt at the start 0.00, charged 413.26, paid 0.00, debt at the end 413.26, amount due 413.26. The QR code is also for 413.26.
2. If on 03.11 you paid 413.26, the invoice would still show the debt at the end of October as 413.26. Under the table there would be the line "Received after October" with 413.26, and "{{ui:invoices.due}}" would be 0.00. There would be no QR code.
3. If you had paid 100.00 too much earlier and there is no debt at the end, the invoice would show "{{ui:charges.prepayment}} 100.00", "{{ui:invoices.due}}" 0.00 and have no QR code.

More cases with numbers: [The numbers of the invoice](help:how-invoice-numbers).

## The pay buttons in the account

In [My account](help:account), in the {{ui:invoices.myTitle}} block, every invoice with an amount to pay has buttons. {{ui:invoices.pay.payInBank}} opens the transfer in the banking app (on a phone without scanning the QR code). {{ui:invoices.pay.copyIban}} and {{ui:invoices.pay.copyPurpose}} copy the IBAN and the payment purpose so that you can paste them into the bank if the app did not open. After copying, the button says "{{ui:invoices.pay.copied}}" for a moment. If it failed, the system says "{{ui:invoices.pay.copyFailed}}": select the value and copy it by hand. Check the recipient, the IBAN, the purpose and the amount just as with the QR code.

## The QR code

The code follows the rules of the National Bank of Ukraine. A banking app reads the QR code and opens a filled-in transfer. Before you pay, check in the app:

1. The recipient is the name of your cooperative.
2. The IBAN is the one in the invoice.
3. The payment purpose starts with the word "{{ui:plot.kind.PLOT}}", then your plot number, the month and the invoice number.
4. The amount equals "{{ui:invoices.due}}". You may change it: you can pay a part.

The other fields (recipient, account, recipient code, purpose) are locked. Close the form without paying if something does not match. If something does not match, do not pay and ask the board.

## If the numbers look wrong

- The invoice of a month that is already closed does not change. If the board corrected a charge afterwards, the correction appears in the invoice of a later month, with the month it corrects named.
- Open the {{ui:charges.myTitle}} block in the account: the {{ui:charges.details}} button shows how each amount was obtained.
- If your plot has no meter, nothing is charged for electricity or water on that plot.
- Ask the board about anything else.

## Related pages

- [My account](help:account)
- [Documents for a member](help:member-documents)
- [The numbers of the invoice](help:how-invoice-numbers)
