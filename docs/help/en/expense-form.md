---
id: expense-form
title: "Enter an expense"
description: "How to record an expense of the cooperative: account, amount, category, payee, a photo of the receipt from a phone, documents, and what to do with HEIC photos from an iPhone."
kind: screen
routes:
  - /cashbook/expenses/new
permissions:
  - CASHBOOK_ENTER
section: payments-cash
order: 60
shots:
  - name: expense-form
    user: chairman
    path: /cashbook/expenses/new
---

## What the screen is for

Here you record the money the cooperative spent: payment of electricity to the supplier, materials, waste removal. You can attach a photo of the receipt or an invoice to the expense. After saving, the expense reduces the balance of the cash or the bank.

## Who sees it

The form is seen by those who have the right "{{ui:permission.CASHBOOK_ENTER.name}}": the treasurer and the chairman. After saving, an expense is not edited. It can be reversed only by a person with "{{ui:permission.CASHBOOK_APPROVE.name}}" (the chairman), on the [expense card](help:expense-card).

## Every element

Open the form with the button "{{ui:expenses.add}}" on the [Cash and bank](help:cashbook) and [Expenses](help:expenses) screens. The title is "{{ui:expenses.form.title}}". If you came from a reversed expense, "{{ui:expenses.form.prefilled}}" stands at the top, and the fields and files are already filled.

### Fields

| Field | What to enter |
|---|---|
| {{ui:expenses.form.account}} | "{{ui:cashbook.account.CASH}}" or "{{ui:cashbook.account.BANK}}". |
| {{ui:expenses.form.date}} | Not later than today: otherwise "{{ui:expenses.form.dateFuture}}". |
| {{ui:expenses.form.amount}} | More than 0.00: otherwise "{{ui:expenses.form.amountInvalid}}". |
| {{ui:expenses.form.category}} | Required, from the cooperative's list of categories. If the list is empty, the form says "{{ui:expenses.form.noCategories}}": the chairman adds categories in the [settings](help:settings). Otherwise "{{ui:expenses.form.categoryRequired}}". |
| {{ui:expenses.form.counterparty}} | Optional, up to 200 characters. |
| {{ui:expenses.form.description}} | Optional, up to 500 characters. |
| {{ui:expenses.form.documentNumber}} and {{ui:expenses.form.documentDate}} | Optional: the number and date of the supplier's invoice or receipt. |
| {{ui:expenses.form.attachments}} | From 0 to 5 files (more below). |

The fields "{{ui:expenses.form.counterparty}}" and "{{ui:expenses.form.description}}" may contain the name of a private person, so they are personal data. They are stored protected and visible only to those who have the right "{{ui:permission.CASHBOOK_VIEW.name}}".

The button "{{ui:expenses.form.submit}}" records the expense.

### Files: receipts and invoices

The hint under the buttons: "{{ui:expenses.attachments.hint}}".

- "{{ui:expenses.attachments.pick}}" adds a file from the device.
- "{{ui:expenses.attachments.camera}}" (on a phone) opens the camera: the shot goes into the expense as an attachment.
- Files added before saving are shown as thumbnails. The button "{{ui:expenses.attachments.remove}}" removes a file. The same file (same name, size and content) cannot be attached twice: the form says "{{ui:expenses.attachments.duplicate}}".
- Location (GPS) and camera data are removed from photos on upload. The system determines the file type from its content, not from its name.
- Error messages: "{{ui:expenses.attachments.tooLarge}}", "{{ui:expenses.attachments.tooMany}}" and "{{ui:expenses.attachments.typeNotAllowed}}". A text file renamed to receipt.jpg is rejected.

> [!important]
> HEIC photos from an iPhone are accepted in Safari only. The hint "{{ui:expenses.attachments.heicHint}}" under the buttons reminds you. If another browser cannot open such a file, the form says: "{{ui:expenses.attachments.heicFailed}}" That is, add the photo through Safari or change the phone setting once: "Settings → Camera → Formats → Most Compatible". After that the iPhone shoots in JPEG and the photo opens anywhere. While a photo is being converted you see "{{ui:expenses.attachments.converting}}".

### Saving and errors

For a cash expense the till must have enough money, and the account must have an opening balance. If not, the form shows one of these messages.

| What is shown | What to do |
|---|---|
| {{ui:problem.CASH_INSUFFICIENT}} | A smaller amount, or first put money into the till. A cash expense without money in the till is not recorded. |
| {{ui:problem.OPENING_BALANCE_MISSING}} | The chairman enters the opening balance on the [Cash and bank](help:cashbook) screen. |
| {{ui:problem.BEFORE_OPENING_BALANCE}} | Enter a date not earlier than the opening balance. |

## Worked example

The treasurer bought a cable and terminals in a shop to repair a lamp and paid in cash.

1. On the "Cash and bank" screen press "{{ui:expenses.add}}".
2. Account: "{{ui:cashbook.account.CASH}}", amount 860.00, category "Materials". Paid to: Будмаркет «Демо». Purpose: "Cable and terminals for a lamp repair". Document number: ЧК-1187.
3. Press "{{ui:expenses.attachments.camera}}" and photograph the receipt.
4. Press "{{ui:expenses.form.submit}}". The cash balance went down by 860.00 ₴, and the expense card has the photo of the receipt.

## Screenshots

![The new expense form](shot:expense-form)

## Related pages

- [Expenses](help:expenses)
- [Expense card](help:expense-card)
- [Cash and bank](help:cashbook)
- [Settings: expense and income categories](help:settings)
