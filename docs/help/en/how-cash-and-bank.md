---
id: how-cash-and-bank
title: "Cash, bank and reconciliation"
description: "How the system counts the cash and bank balance, the cash recount, the bank reconciliation and the dating of entries."
kind: topic
routes: []
permissions:
  - CASHBOOK_VIEW
section: how-it-calculates
order: 130
shots: []
---

The cash and the bank are kept as a **journal of lines** that erase nothing: a line is neither edited nor deleted, a mistake is corrected by a reversal. How to work with the screens is the page [Cash and bank](help:cashbook); here are the rules and examples. The server calculates all amounts.

- **The balance on a day** — The opening balance plus every receipt and minus every spending from the opening date up to and including that day. The opening balance is the balance _at the start_ of its date; it is entered once. An entry other than a member payment dated before the opening balance is refused.
- **Member payments in the journal** — A payment from the page [Payments](help:payments) becomes a line by itself: a cash payment in the cash, a bank payment in the bank. Reversing a payment adds a line of the opposite amount dated on _the day of the reversal_. The member's debt view (the account, the reconciliation act) keeps the dating from the page [How the system allocates payments](help:how-payments-allocate). When the feature is first installed, every payment already entered and every reversal of a payment gets its line exactly once.
- **Cash never goes negative** — A cash expense, a handover, the reversal of a cash income and a booked shortage are refused if they would make the cash negative _on their date or on any later day_. The message names the balance: "Not enough cash in the till: balance 200.00 on 07.10.2026". A wrong balance is fixed by a cash count, not by going negative. Two entries saved at the same moment can never together push the cash below zero.
- **The only exception** — Reversing a _cash member payment_: the money is already gone, so the reversal is allowed. If the cash is negative because of it, the card says "The cash balance is negative - do a cash count".
- **The bank may be negative** — An overdraft is the bank's business: the screen warns {{ui:cashbook.negative.BANK}} but does not refuse.
- **Closed months** — A line dated in a closed billing month keeps its date and falls into the earliest open month. Reports by day use the date, reports by billing month use the month it belongs to.
- **Cash count** — Difference = counted − book. Zero - {{ui:audit.countStatus.MATCHES}}. Otherwise an explanation is required, and the difference hangs as {{ui:audit.countStatus.UNRESOLVED}} until the chairman books it as a "Surplus" or a "Shortage". A saved count keeps the book balance it showed.
- **Bank reconciliation** — The statement figures must agree: opening + in − out = closing. Four pairs of figures are compared: the opening balance, the total in, the total out and the closing balance (from the books: the bank balance at the start of the month, the sum of receipts, the sum of spendings, the balance at the end of the last day). {{ui:cashbook.bank.status.RECONCILED}} is available only when the **closing balance** difference is 0.00; a difference in the total in or out only warns. Months are calendar months by the date of the line. Any bank line dated in a reconciled month (a payment too) sends it to {{ui:cashbook.bank.status.NEEDS_RECHECK}}.

## Worked examples

Cash opening balance on 01.10.2026 is 1,000.00, the bank's is 20,000.00.

> **Example 21. Cash balance by day**
>
> 05.10 a cash payment of 500.00 - cash 1,500.00. 06.10 a cash expense of 300.00 {{ui:expenseCategories.system.MATERIALS}} - cash 1,200.00. 07.10 a handover of 1,000.00 to the bank - cash 200.00, bank 21,000.00.

> **Example 22. Not enough cash**
>
> Then on 07.10 the treasurer enters a cash expense of 250.00. The cash is 200.00, so the entry is refused: "Not enough cash in the till: balance 200.00 on 07.10.2026".

> **Example 23. A back-dated entry**
>
> On 08.10 the treasurer enters an expense of 400.00 dated 06.10. On 06.10 there was 1,200.00 and after it there would be 800.00 - fine. But 07.10 after the handover would be 200.00 − 400.00 = −200.00, so the entry is refused and the message names that day and its balance.

> **Example 24. Reversing a cash payment**
>
> On 12.10 the chairman reverses the cash payment of 500.00 from 05.10 when the cash holds only 200.00. The cash journal gets a line of −500.00 dated 12.10; the cash becomes −300.00. The reversal is allowed (the money is already given out), and the cash card asks for a cash count.

> **Example 25. A shortage found at a count**
>
> On 10.10 the book says 200.00, the treasurer counted 180.00: the difference is −20.00, an explanation is required, "Unresolved difference −20.00". The chairman books a "Shortage" of −20.00: the cash is 180.00 and no difference hangs.

> **Example 26. October reconciliation: a bank fee**
>
> The statement: opening 20,000.00, in 3,000.00, out 150.00, closing 22,850.00 (20,000.00 + 3,000.00 − 150.00 = 22,850.00, the figures agree). The books: in 3,000.00 (2,000.00 member bank payments plus the 1,000.00 handover), out 0.00. The "out" differs by 150.00 (and the closing balance too). It is a bank fee nobody entered: the treasurer enters an expense of 150.00 {{ui:expenseCategories.system.BANK_FEES}} in October, the differences become 0.00 and October can be marked {{ui:cashbook.bank.status.RECONCILED}}.

> **Example 27. A reconciled month is reopened**
>
> October is {{ui:cashbook.bank.status.RECONCILED}}. On 03.11 a bank payment dated 20.10 is entered. October goes to {{ui:cashbook.bank.status.NEEDS_RECHECK}} and the screen says: "Reconcile this month again: a member payment of 20.10.2026 for 500.00 ₴ was entered on 04.11.2026, 10:15." November, if it is also {{ui:cashbook.bank.status.RECONCILED}}, is reopened with the same notice: its opening balance changed. Check October first, then November.

> **Example 28. The statement figures do not agree**
>
> Opening 100.00, in 50.00, out 20.00, closing 200.00. 100.00 + 50.00 − 20.00 = 130.00, not 200.00: the statement is not saved - "The statement figures do not agree: 100.00 + 50.00 − 20.00 ≠ 200.00".

> **Example 29. A closed month**
>
> September is closed. On 3 October the treasurer enters an expense dated 30.09: the journal keeps the date 30.09 and the billing month is October.

> [!important]
> **Limits of reconciliation**
>
> {{ui:cashbook.bank.status.RECONCILED}} needs only a zero closing balance difference. A mistake made in a past month and reversed today cannot be accounted for in that month (the reversal is dated today), but this is only a warning about a difference in the total in or out and does not block. A back-dated entry changes the opening balance of all later months, so the month of the entry and every later {{ui:cashbook.bank.status.RECONCILED}} month are reopened; check them one by one, starting with the earliest.
