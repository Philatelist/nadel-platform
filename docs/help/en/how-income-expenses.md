---
id: how-income-expenses
title: "Income and expenses, and the reconciliation act"
description: "How income and expenses for the general meeting and the reconciliation act with a member are counted."
kind: topic
routes: []
permissions:
  - CASHBOOK_VIEW
  - REPORTS_VIEW
section: how-it-calculates
order: 140
shots: []
---

How to work with the report and the act is the page [Reports](help:reports); here is what exactly they calculate.

- **Member payments in {{ui:reports.income-expense.title}}** — The payments received in the period are taken by charge type - _as the payments are allocated at the end of the period_. The part not allocated to any charge is the line "Prepayments". Reversals made in the period of payments received earlier are a separate negative line.
- **The identity** — **Income − expenses + differences = the change of (cash + bank) over the period.** A handover of cash to the bank is neither income nor expense: it is a move between two accounts and cancels out.
- **The reconciliation act** — The act reads the same ledger as the plot account, so the balances at the start and the end of the period equal the balances in the {{ui:reports.plot-balances.title}} report on those days. Every charge, adjustment, reversal and payment stands by date in the columns {{ui:audit.outcome.RESOLVED}} and {{ui:charges.paid}}. The period runs from the plot's first charge up to today.

> **Example 30. Reconciliation act, plot 9, 01.09-31.10**
>
> The balance on 31.08: a debt of 200.00. September is charged 300.00, October 250.00; on 15.10 a payment of 300.00 is entered. Turnover: charged 550.00, paid 300.00. The balance on 31.10: 200.00 + 550.00 − 300.00 = 450.00, and the {{ui:reports.plot-balances.title}} report shows the same debt on 31.10. The act ends with "According to the cooperative's records the debt as of 31.10.2026 is 450.00".

> **Example 31. Income and expenses for October**
>
> Cash 1,000.00 and bank 20,000.00 on 01.10. During October: member cash payments of 500.00 and bank payments of 2,000.00 (2,500.00 together - 1,800.00 of it membership fee, 500.00 electricity, 200.00 prepayments); bank interest 35.00; a cash expense of 300.00 and a bank fee of 150.00; a shortage of −20.00; a handover of 1,000.00 from cash to the bank. Income 2,500.00 + 35.00 = 2,535.00; expenses 300.00 + 150.00 = 450.00; differences −20.00. The result: 2,535.00 − 450.00 − 20.00 = 2,065.00. The change of cash: 1,000.00 + 500.00 − 300.00 − 1,000.00 − 20.00 = 180.00, that is −820.00 for the month. The change of the bank: 20,000.00 + 2,000.00 + 1,000.00 + 35.00 − 150.00 = 22,885.00, that is +2,885.00. Together −820.00 + 2,885.00 = 2,065.00 - equal to the result. If a payment of 100.00 from September were reversed in October, the income would get a line of −100.00 and both the result and the change would fall by the same amount.
