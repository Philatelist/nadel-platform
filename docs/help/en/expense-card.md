---
id: expense-card
title: "Expense card"
description: "One expense of the cooperative: its data, attached receipts (view and download), the accounting month, who entered it, and reversal with re-entry."
kind: screen
routes:
  - /cashbook/expenses/:id
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 70
shots:
  - name: expense-card
    user: chairman
    path: /cashbook/expenses
    open: { css: 'main a[href^="/cashbook/expenses/"]:not([href$="/new"])', withFiles: true, rowText: 'Будмаркет' }
---

## What the screen is for

The card shows one expense with all its data and the receipt files. Here the chairman reverses an expense that was recorded with a mistake.

## Who sees it

The card is seen by those who have "{{ui:permission.CASHBOOK_VIEW.name}}": the chairman and the treasurer. The button "{{ui:expenses.card.redo}}" is seen only by those who have "{{ui:permission.CASHBOOK_APPROVE.name}}" (the chairman). A treasurer who made a mistake asks the chairman.

## Every element

### Expense data

A table with the fields: {{ui:expenses.card.account}}, {{ui:expenses.card.date}}, {{ui:expenses.card.amount}}, {{ui:expenses.card.category}}, {{ui:expenses.card.counterparty}}, {{ui:expenses.card.description}}, {{ui:expenses.card.document}} (number and date), {{ui:expenses.card.bookingMonth}} and {{ui:expenses.card.createdBy}}. The accounting month may differ from the date if the date fell in a closed month: then the expense counts in the nearest open one.

The button "{{ui:expenses.back}}" returns to the cash.

### Files

The section "{{ui:expenses.attachments.list}}". If there are no files, it says "{{ui:expenses.attachments.none}}".

- Photos are shown as thumbnails. A click opens the photo at full size right on the page. The Esc key closes it, arrows switch between files; the buttons "{{ui:expenses.attachments.viewer.close}}", "{{ui:expenses.attachments.viewer.previous}}" and "{{ui:expenses.attachments.viewer.next}}" do the same with the mouse.
- A PDF is shown as a file icon. It opens in a new tab ("{{ui:expenses.attachments.openPdf}}").
- The button "{{ui:expenses.attachments.download}}" downloads a file. Files can be downloaded by those who have "{{ui:permission.CASHBOOK_VIEW.name}}".
- If a file did not open, you see "{{ui:expenses.attachments.viewer.failed}}".

### Reverse and enter again

The button "{{ui:expenses.card.redo}}" opens the window "{{ui:expenses.reverse.title}}". Enter a reason (up to 500 characters; otherwise "{{ui:expenses.reverse.required}}") and press "{{ui:expenses.reverse.submit}}". "{{ui:expenses.reverse.cancel}}" closes the window.

What happens next:

- The expense gets the mark "{{ui:expenses.card.reversed}}", with the reason and the date. The money returns to the balance of the cash or the bank.
- The form of a new expense opens at once with the same values and files. Fix the mistake and record it.
- A reversed expense is not reversed a second time.
- The action goes to the change log.

> [!important]
> A reversed expense is not deleted and stays in the list. If you close the new expense form without recording it, the money has already returned to the balance. Enter the expense again through "{{ui:expenses.add}}".

## Worked example

The treasurer entered 860.00 ₴ for materials under the wrong category: it should have been "Other".

1. The chairman opens the card of this expense.
2. He presses "{{ui:expenses.card.redo}}", writes the reason "Wrong category" and confirms.
3. A form opens with the same 860.00 ₴, document ЧК-1187 and the photo of the receipt. The chairman changes the category and presses "{{ui:expenses.form.submit}}".
4. The old record has the mark "{{ui:expenses.card.reversed}}", the new record has the right category, and the cash balance is the same as it was.

## Screenshots

![An expense card with a photo of a receipt](shot:expense-card)

## Related pages

- [Expenses](help:expenses)
- [Enter an expense](help:expense-form)
- [Cash and bank](help:cashbook)
- [Change log](help:audit)
