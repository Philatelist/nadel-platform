---
id: cash-count
title: "Cash count"
description: "How to count the cash in the till and compare it with the book: the difference, the explanation, settling a surplus or shortage, and the count act as a PDF."
kind: screen
routes:
  - /cashbook/counts/new
  - /cashbook/counts/:id
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 80
shots:
  - name: cash-count
    user: chairman
    path: /cashbook
    open: { css: 'main a[href^="/cashbook/counts/"]:not([href$="/new"])' }
---

## What the screen is for

From time to time the treasurer counts the cash in the till and compares it with the book. This screen shows how much there should be according to the book, takes the actual amount and at once shows the difference. The chairman also settles a surplus or a shortage here. The page has two views: a **new count** and a **saved count**.

## Who sees it

Saved counts are seen by those who have "{{ui:permission.CASHBOOK_VIEW.name}}". A new count is recorded by a person with "{{ui:permission.CASHBOOK_ENTER.name}}". The button "{{ui:cashbook.count.book}}" is seen only by a person with "{{ui:permission.CASHBOOK_APPROVE.name}}" (the chairman).

## Every element

A new count is opened with the button "{{ui:cashbook.count.action}}" on the [Cash and bank](help:cashbook) screen. A saved one is opened with the link "{{ui:cashbook.count.lastCount}}" in the cash card. The button "{{ui:cashbook.count.back}}" returns back.

### A new count

| Field | What to enter |
|---|---|
| {{ui:cashbook.count.countedAt}} | When you counted the money. A future time is not accepted: "{{ui:cashbook.count.countedAtFuture}}". |
| {{ui:cashbook.count.counted}} | How much cash is really in the till. Not less than 0.00. |

As soon as you enter the amount, the screen shows "{{ui:cashbook.count.bookBalance}}" and "{{ui:cashbook.count.difference}}" (actual minus book). Below stands "{{ui:cashbook.count.sinceTitle}}": the cash movement since the previous count, so you can see what could have been lost. If there are many lines, only a part is shown: "{{ui:cashbook.count.sinceMore}}".

- A difference of 0.00: the count is recorded as "{{ui:cashbook.count.matches}}".
- Any other difference needs an explanation in the field "{{ui:cashbook.count.explanation}}" (up to 500 characters). Without it: "{{ui:cashbook.count.explanationRequired}}".

The button "{{ui:cashbook.count.submit}}" saves the count. A saved count keeps the book balance it showed: an entry that later appears with an old date does not change it.

### A saved count

A table: {{ui:cashbook.count.countedAt}}, {{ui:cashbook.count.counted}}, {{ui:cashbook.count.bookBalance}}, {{ui:cashbook.count.result}} (matches, or the difference with the explanation), {{ui:cashbook.count.status}} and {{ui:cashbook.count.createdBy}}.

If there is a difference that is not yet settled, the status is: "{{ui:cashbook.count.unresolved}}". The same mark hangs on the cash card until the chairman settles the difference.

- **{{ui:cashbook.count.book}}** (the chairman only). In the window "{{ui:cashbook.count.bookDialog.title}}" enter a reason and press "{{ui:cashbook.count.bookDialog.submit}}". A line "Surplus" (receipt) or "Shortage" (expense) appears in the cash journal. A difference is settled once. A shortage after which the cash would go negative on later days is rejected.
- **{{ui:cashbook.count.act}}** downloads a PDF: the cooperative's name, date and time, the book balance, the actual balance, the difference (with the explanation and, once settled, the way it was settled), signature lines for the treasurer and the chairman. The act is always in Ukrainian.
- The settlement of a difference can be reversed in the cash journal with the button "{{ui:cashbook.reverse.action}}" by the difference line; then the count shows "{{ui:cashbook.count.bookedReversed}}".

> [!important]
> A count and its record are not deleted. If you recorded a wrong amount, make a new count: it will also show the movement of money between the two counts.

## Worked example

According to the book the till has 6,520.00 ₴, and the treasurer counted 6,500.00 ₴.

1. The treasurer presses "{{ui:cashbook.count.action}}" and enters 6500.00.
2. The screen shows a difference of −20.00. The treasurer writes the explanation: "20.00 ₴ is missing: probably a mistake when giving change" and presses "{{ui:cashbook.count.submit}}".
3. The cash card shows "Unsettled difference −20.00 ₴".
4. The chairman opens the count, presses "{{ui:cashbook.count.book}}" and gives the reason "Shortage recognised by the count act". A "Shortage" line of −20.00 ₴ appears in the cash journal; the cash balance went down by 20.00 ₴.
5. The chairman downloads the count act, has the treasurer sign it and signs it himself.

## Screenshots

![A saved cash count with a difference and an act](shot:cash-count)

## Related pages

- [Cash and bank](help:cashbook)
- [Bank reconciliation](help:bank-reconciliation)
- [How the system calculates cash, bank and reconciliation](help:how-cash-and-bank)
