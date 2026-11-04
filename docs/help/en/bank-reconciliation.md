---
id: bank-reconciliation
title: "Bank reconciliation for a month"
description: "How to compare the bank statement with the book at the end of a month: the four figures of the statement, differences, the bank difference and the Reconciled button."
kind: screen
routes:
  - /cashbook/bank/:month
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 90
shots:
  - name: bank-reconciliation
    user: chairman
    path: /cashbook
    open: { css: 'main a[href^="/cashbook/bank/"]' }
---

## What the screen is for

At the end of a month the treasurer compares the bank statement with what is recorded in the book. The screen shows both sets of figures side by side, the difference for each and all the bank lines of the month. When the closing balance agrees, the month is marked "{{ui:cashbook.bank.status.RECONCILED}}".

## Who sees it

The screen is seen by those who have "{{ui:permission.CASHBOOK_VIEW.name}}". The statement figures are entered and "{{ui:cashbook.bank.confirm}}" is pressed by a person with "{{ui:permission.CASHBOOK_ENTER.name}}" (the treasurer, the chairman). The button "{{ui:cashbook.bank.book}}" and the reversal of a bank difference are seen only by a person with "{{ui:permission.CASHBOOK_APPROVE.name}}" (the chairman).

## Every element

A month is opened from the bank card on the [Cash and bank](help:cashbook) screen: press the name of the month. The title is "{{ui:cashbook.bank.title}}". The button "{{ui:cashbook.bank.back}}" returns back. Next to the title you see the status: "{{ui:cashbook.bank.status.NOT_RECONCILED}}", "{{ui:cashbook.bank.status.RECONCILED}}" or "{{ui:cashbook.bank.status.NEEDS_RECHECK}}", and for a reconciled month also who reconciled it and when ("{{ui:cashbook.bank.reconciledBy}}").

### Bank statement and book

The section "{{ui:cashbook.bank.figuresTitle}}". The treasurer enters four figures from the bank statement:

1. {{ui:cashbook.bank.figure.opening}};
2. {{ui:cashbook.bank.figure.incoming}};
3. {{ui:cashbook.bank.figure.outgoing}};
4. {{ui:cashbook.bank.figure.closing}}.

They must add up: opening + receipts − debits = closing. Otherwise the system says, for example: "The statement figures do not add up: 100.00 + 50.00 − 20.00 ≠ 200.00" (the text template is "{{ui:cashbook.bank.inconsistent}}"). The button "{{ui:cashbook.bank.save}}" saves the statement. While the statement is not entered you see "{{ui:cashbook.bank.notEntered}}".

The table has three columns: "{{ui:cashbook.bank.columns.statement}}", "{{ui:cashbook.bank.columns.books}}" and "{{ui:cashbook.bank.columns.difference}}".

### How to find a difference

Under the table there is the section "{{ui:cashbook.bank.movementsTitle}}": all the bank lines of the month. This makes it easier to find what is missing.

- If a record is missing (the bank charged a fee and you do not have it), enter it: a payment, an expense or [other income](help:cashbook). The comparison updates by itself.
- If no record explains the difference, the chairman presses "{{ui:cashbook.bank.book}}". In the window "{{ui:cashbook.bank.bookDialog.title}}" enter the amount (negative means a debit) and a reason and press "{{ui:cashbook.bank.bookDialog.submit}}". The line goes to the bank journal; it can be reversed ("{{ui:cashbook.bank.reverseDialog.title}}").

### The button "{{ui:cashbook.bank.confirm}}"

It works only when the difference of the closing balance (closing by the statement minus closing by the book) is 0.00. Otherwise the hint "{{ui:cashbook.bank.confirmHint}}" stands next to it, and the action is rejected with the message "{{ui:problem.BANK_MONTH_NOT_BALANCED}}".

A difference in receipts or debits does not block the button. The screen shows it as a warning: "{{ui:cashbook.bank.flowDiffers}}"

A reconciled month cannot be changed ("{{ui:cashbook.bank.lockedHint}}").

### A reconciled month can become "unreconciled"

If a bank line dated in a reconciled month appears later (for example, on 3 November a bank payment for 20 October is entered), the month goes to "{{ui:cashbook.bank.status.NEEDS_RECHECK}}". The screen says what was added and when: "The month must be reconciled again: 04.11.2026, 10:15 a member payment dated 20.10.2026 for 500.00 ₴ was entered." The same happens to every following reconciled month, because their opening balance changed. Check the months one by one starting with the earliest and press "{{ui:cashbook.bank.confirm}}" for each. The change log keeps a record of which line reopened them.

> [!important]
> A mistake of a past month that you reverse today: the reversal goes into the current month with today's date. In the past month this gives a difference in receipts or debits: it only warns and does not block the reconciliation.

## Worked example

The statement for the month: opening balance 48,200.00, receipts 5,000.00, debits 14,000.00, closing balance 39,200.00. The book shows 35.00 ₴ less in debits, because the bank charged a fee that you do not have.

1. The treasurer enters the four figures and presses "{{ui:cashbook.bank.save}}". The difference of the closing balance is 35.00 ₴, the button "{{ui:cashbook.bank.confirm}}" does not work.
2. The fee is not among the bank lines. The treasurer enters it as an expense of 35.00 ₴ in the category "Bank fees".
3. The difference became 0.00. The treasurer presses "{{ui:cashbook.bank.confirm}}", and the month is marked reconciled.

## Screenshots

![Bank reconciliation for a month: statement, book and differences](shot:bank-reconciliation)

## Related pages

- [Cash and bank](help:cashbook)
- [Enter an expense](help:expense-form)
- [Cash count](help:cash-count)
- [How the system calculates cash, bank and reconciliation](help:how-cash-and-bank)
