---
id: expenses
title: "Expenses"
description: "The list of the cooperative's expenses with filters by period, account and category and a mark for attached receipt files."
kind: screen
routes:
  - /cashbook/expenses
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 50
shots:
  - name: expenses
    user: chairman
    path: /cashbook/expenses
---

## What the screen is for

All the cooperative's expenses are listed here: what was paid and to whom, from which account, and how much. From here you open an expense card with receipts and enter a new expense.

## Who sees it

The list is seen by those who have the right "{{ui:permission.CASHBOOK_VIEW.name}}": the chairman and the treasurer. The button "{{ui:expenses.add}}" is seen by those who also have "{{ui:permission.CASHBOOK_ENTER.name}}". A member does not see this screen.

## Every element

### Header buttons

- "{{ui:expenses.back}}" returns to the [Cash and bank](help:cashbook) screen.
- "{{ui:expenses.add}}" opens the [new expense form](help:expense-form).

### Filters

| Filter | What it does |
|---|---|
| {{ui:expenses.list.from}} and {{ui:expenses.list.to}} | Show expenses by the expense date. |
| {{ui:expenses.list.account}} | "{{ui:cashbook.account.CASH}}", "{{ui:cashbook.account.BANK}}" or "{{ui:expenses.list.all}}". |
| {{ui:expenses.list.category}} | One expense category or "{{ui:expenses.list.all}}". |

When no expenses match, the screen says "{{ui:expenses.list.empty}}".

### The table

Columns: {{ui:expenses.list.columns.date}}, {{ui:expenses.list.columns.account}}, {{ui:expenses.list.columns.category}}, {{ui:expenses.list.columns.counterparty}}, {{ui:expenses.list.columns.amount}} and {{ui:expenses.list.columns.files}} (how many receipts are attached). The date is a link to the [expense card](help:expense-card). A reversed expense is marked "{{ui:expenses.list.reversed}}".

An expense is not edited: a mistake is corrected by a reversal on the card.

The field "{{ui:expenses.list.columns.counterparty}}" may contain the name of a private person, so it is personal data: it is seen only by those who have the right "{{ui:permission.CASHBOOK_VIEW.name}}".

## Worked example

The demo cooperative has expenses in four categories: "Electricity to the supplier" 12,480.00 ₴ from the bank, "Materials" 860.00 ₴ in cash (cable and terminals for a lamp, a photo of the receipt attached), "Waste removal" 1,200.00 ₴ and "Office and communications" 145.50 ₴.

1. Open "Expenses".
2. In the filter "{{ui:expenses.list.account}}" choose "{{ui:cashbook.account.CASH}}": three cash expenses remain.
3. Press the date of the 860.00 ₴ expense: a card with the photo of the receipt opens.

## Screenshots

![The expense list with filters](shot:expenses)

## Related pages

- [Enter an expense](help:expense-form)
- [Expense card](help:expense-card)
- [Cash and bank](help:cashbook)
- [Cash reports](help:reports)
