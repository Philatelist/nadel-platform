---
id: cashbook
title: "Cash and bank"
description: "The cooperative's two accounts, cash and bank: balances, the opening balance, the movement journal, handing cash over to the bank, other income and reversal of entries."
kind: screen
routes:
  - /cashbook
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 40
shots:
  - name: cashbook
    user: chairman
    path: /cashbook
  - name: cashbook-phone
    user: chairman
    path: /cashbook
    viewport: phone
---

## What the screen is for

The system knows not only how much members owe and pay, but also where that money is: in the treasurer's till or in the bank account, and what it was spent on. This screen shows both accounts, their balances and the movement journal. It does not change members' debts: it shows where their money went.

The cooperative has exactly two accounts: "{{ui:cashbook.account.CASH}}" (cash held by the treasurer) and "{{ui:cashbook.account.BANK}}". There is no second till or second bank account.

## Who sees it

The screen is seen by those who have the right "{{ui:permission.CASHBOOK_VIEW.name}}": the chairman and the treasurer. The buttons for entering records are seen by those who have "{{ui:permission.CASHBOOK_ENTER.name}}" (the treasurer and the chairman). Opening balances, settling differences and reversing records are available only to those who have "{{ui:permission.CASHBOOK_APPROVE.name}}": by default the chairman. Without the right to enter you see only balances and journals.

A member does not see this menu item, and gets a message that there is no access at a direct address. Each of the three rights can be given to a custom role on the [Roles](help:roles) page.

## Every element

### Header buttons

| Button | What it does |
|---|---|
| {{ui:expenses.title}} | Opens the [expense list](help:expenses). |
| {{ui:cashbook.count.action}} | Opens the [cash count](help:cash-count). Needs the right to enter. |
| {{ui:cashbook.handover.action}} | The window for handing cash to the bank (below). Needs the right to enter. |
| {{ui:cashbook.otherIncome.action}} | The window for other income (below). Needs the right to enter. |
| {{ui:expenses.add}} | Opens the [expense form](help:expense-form). Needs the right to enter. |

On a phone the cards stand one under another, and everything works the same.

### An account card

Each account has its own card.

- **Balance.** The large line "{{ui:cashbook.balance}}" shows the balance for today. Under it you see the opening balance and from which date.
- **Opening balance.** While the account has none, the sum is replaced by "{{ui:cashbook.enterOpening}}". The chairman enters it once: the amount and the date "{{ui:cashbook.openingForm.date}}" (the balance at the start of that day) and presses "{{ui:cashbook.openingForm.save}}". After saving it cannot be changed: a mistake is corrected by a cash count (for cash) or a bank difference (for the bank). A treasurer without the approval right sees a hint but no button.
- **Last cash count**: date and result ("{{ui:cashbook.count.lastCountMatches}}" or "{{ui:cashbook.count.lastCountDifference}}"). The link leads to the [count](help:cash-count). If a difference is not yet settled, the warning "{{ui:cashbook.count.unresolved}}" is shown.
- **Months of bank reconciliation** (only in the bank card): a list of months with the mark "{{ui:cashbook.bank.status.NOT_RECONCILED}}", "{{ui:cashbook.bank.status.RECONCILED}}" or "{{ui:cashbook.bank.status.NEEDS_RECHECK}}". Press a month to open the [bank reconciliation](help:bank-reconciliation).
- **Warnings.** If the cash went negative, you see "{{ui:cashbook.negative.CASH}}". For the bank it is "{{ui:cashbook.negative.BANK}}": an overdraft is the bank's business, the system only warns.

While there is no opening balance, expenses, handing over to the bank, other income, counts and reconciliations for this account are rejected with the message "{{ui:problem.OPENING_BALANCE_MISSING}}". Member payments are recorded anyway. Payments dated before the opening balance stay in the journal with the mark "{{ui:cashbook.movements.beforeOpening}}" and are not part of the balance.

The balance for a day is the opening balance plus all receipts and minus all expenses from the day of the opening balance to that day inclusive.

### The movement journal

Under each card there is the journal "{{ui:cashbook.movements.title}}". The filters "{{ui:cashbook.movements.from}}" and "{{ui:cashbook.movements.to}}" set the period; without them the current month up to today is shown. Above the table you see "{{ui:cashbook.movements.openingBalance}}" and "{{ui:cashbook.movements.closingBalance}}".

Columns: {{ui:cashbook.movements.columns.date}}, {{ui:cashbook.movements.columns.kind}}, {{ui:cashbook.movements.columns.details}} (receipt number, document, category, reason), {{ui:cashbook.movements.columns.amount}}, {{ui:cashbook.movements.columns.createdBy}} and {{ui:cashbook.movements.columns.actions}}.

Kinds of entries: "{{ui:cashbook.kind.MEMBER_PAYMENT}}", "{{ui:cashbook.kind.EXPENSE}}", "{{ui:cashbook.kind.HANDOVER_OUT}}", "{{ui:cashbook.kind.OTHER_INCOME}}", "{{ui:cashbook.kind.COUNT_DIFFERENCE}}" and "{{ui:cashbook.kind.BANK_DIFFERENCE}}". A member payment appears here by itself at the moment the payment is saved. A reversed entry and its reversal both stay in the journal: the reversal has the mark "{{ui:cashbook.movements.reversal}}" and the reversed entry has "{{ui:payments.reversed}}".

In the actions column:

- by an expense there is the link "{{ui:cashbook.reverse.expense}}": an expense is reversed on its card;
- the button "{{ui:cashbook.reverse.action}}" (only with the approval right) is by a handover to the bank, other income, a count difference and a bank difference;
- a member payment is reversed on the [payment card](help:payment-card), not here. Its reversal appears in the journal by itself.

A reversal is a new line of the opposite amount dated the day you reverse. The entry itself is not deleted. An entry is reversed only once, and a reason is required (up to 500 characters, otherwise "{{ui:cashbook.reverse.handover.required}}"). A reversal of a cash receipt after which the cash would go negative is rejected.

> [!important]
> An entry dated in an already closed accounting month keeps its date but counts in the nearest open month, as payments do. Reports by day use the entry date, reports by accounting month use the accounting month.

### The window "{{ui:cashbook.handover.title}}"

The button "{{ui:cashbook.handover.action}}". Cash goes from the till to the bank account, and two lines appear in the journals: an expense in the cash and a receipt in the bank of the same date and amount.

| Field | What to enter |
|---|---|
| {{ui:cashbook.handover.date}} | Not later than today: otherwise "{{ui:cashbook.handover.dateFuture}}". |
| {{ui:cashbook.handover.amount}} | More than 0.00: otherwise "{{ui:cashbook.handover.amountInvalid}}". |
| {{ui:cashbook.handover.documentNumber}} | Required, for example "КВ-123" (error: "{{ui:cashbook.handover.documentRequired}}"). |

The button "{{ui:cashbook.handover.save}}" records the handover, "{{ui:cashbook.handover.cancel}}" closes the window. A handover after which the cash would go negative is rejected with the message "{{ui:problem.CASH_INSUFFICIENT}}".

### The window "{{ui:cashbook.otherIncome.title}}"

Receipts that are not from members: bank interest, a grant, sale of scrap metal. Fields: "{{ui:cashbook.otherIncome.account}}", "{{ui:cashbook.otherIncome.date}}", "{{ui:cashbook.otherIncome.amount}}", "{{ui:cashbook.otherIncome.category}}" and "{{ui:cashbook.otherIncome.description}}" (up to 500 characters). The button "{{ui:cashbook.otherIncome.save}}". If the category list is empty, the window says "{{ui:cashbook.otherIncome.noCategories}}": the chairman adds categories in the [settings](help:settings).

## Worked example

Suppose the till holds 9,000.00 ₴ and the bank account 48,200.00 ₴ (these are the opening balances in the demo cooperative).

1. The treasurer enters a cash payment of 500.00 ₴. A line "{{ui:cashbook.kind.MEMBER_PAYMENT}}" of +500.00 appears in the cash journal, and the cash balance becomes 9,500.00 ₴.
2. He presses "{{ui:cashbook.handover.action}}", enters 3,000.00 and the number "КВ-123". The cash gets a line of −3,000.00, the bank +3,000.00. Cash: 6,500.00 ₴, bank: 51,200.00 ₴.
3. He got a digit wrong. The chairman presses "{{ui:cashbook.reverse.action}}" by the handover and writes a reason. Both lines are reversed, the balances are back to 9,500.00 ₴ and 48,200.00 ₴.

## Screenshots

![Cash and bank: two cards with balances and journals](shot:cashbook)

![Cash and bank on a phone](shot:cashbook-phone)

## Related pages

- [Expenses](help:expenses)
- [Cash count](help:cash-count)
- [Bank reconciliation](help:bank-reconciliation)
- [Payments](help:payments)
- [Cash reports](help:reports)
- [How the system calculates cash, bank and reconciliation](help:how-cash-and-bank)
