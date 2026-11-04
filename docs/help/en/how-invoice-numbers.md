---
id: how-invoice-numbers
title: "Invoice numbers"
description: "Where the numbers in an invoice come from: opening debt, charged, paid, amount due and the QR amount."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 110
shots: []
---

An invoice does not recalculate anything: it reads the same ledger as the plot account and the reports, and arranges it by billing month. How to work with the screens is the page [The charges month and invoices](help:charges-month); here are the rules and the examples.

- **The booking-month rule** — Every row of the ledger belongs to the billing month it is _booked_ in. For an invoice of October, {{ui:audit.outcome.RESOLVED}} is everything booked in October (regular charges, adjustments and reversals), and {{ui:charges.paid}} is the payments placed in October. "Debt at the start" is what was open before October, "debt at the end" what is open after it.
- **A closed month never changes** — A correction made after October was closed is booked in the month it is made in. So the October invoice and its re-issues keep the same numbers, and the correction shows up in the invoice of the later month, with the month it corrects.
- **Amount due** — Debt at the end of the month, minus the money received after the month and before the issue, never below 0.00. Those payments are listed under the table as "Received after October". When the amount due is 0.00 there is no QR code; a prepayment is shown as {{ui:charges.prepayment}}.
- **The invoice number** — Year-month/plot number, for example `2026-10/9`. A re-issue keeps the number and raises the version.

## Worked examples

Plot 9, charge types in the order Membership, Electricity. The invoice for October 2026, issued on 05.11.2026.

| # | Situation | The October invoice |
|---|---|---|
| 1 | No debt before. October: membership 75.00, electricity 338.26. No payments | Start 0.00; charged 413.26; paid 0.00; end 413.26; due 413.26; QR amount 413.26 |
| 2 | Electricity debt from September 1,135.08. In October a payment of 1,500.00 went to 1,135.08 (September electricity) + 75.00 (membership) + 289.92 (October electricity) | Start 1,135.08; charged 413.26; paid 1,500.00; end 48.34 (electricity); due 48.34 |
| 3 | Example 1 and a payment of 413.26 on 03.11 | End of October 413.26; "Received after October" 413.26; due 0.00; no QR code |
| 4 | Overpaid: no debt at the end, prepayment 100.00 | Due 0.00; "Prepayment 100.00"; no QR code |
| 5 | October was closed on 03.11. On 06.11 a recalculation lowers an October charge by 20.00 (booked in November) | The October invoice does not change, and a re-issue gives the same numbers. The November invoice shows the -20.00 adjustment "for October 2026" |
