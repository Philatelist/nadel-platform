---
id: recalculations
title: "Recalculations: the log"
description: "The log of corrections of past charges: when, why, who booked, how the amount changed, and how to open a single recalculation."
kind: screen
routes:
  - /charges/recalculations
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 100
shots:
  - name: recalculations
    user: chairman
    path: /charges/recalculations
---

## What the screen is for

Sometimes past charges must be corrected: a tariff rate was entered with a mistake, a meter reading was corrected, a plot's area changed. A recalculation does this. The log here shows all booked recalculations: when, for what reason, who booked them and how much the amounts changed. The log is view-only: a recalculation is never changed or cancelled, and a mistake is corrected by the next recalculation.

## Who sees it

The chairman and the treasurer see the log (the "{{ui:permission.CHARGES_VIEW.name}}" permission). The new recalculation button is shown only to the one who has the "{{ui:permission.RECALCULATION_RUN.name}}" permission, that is, the chairman.

## Every element

At the top are the heading "{{ui:recalculation.heading}}" with the explanation "{{ui:recalculation.description}}" and the charges tabs. If there have been no recalculations yet: "{{ui:recalculation.empty}}".

### The button

{{ui:recalculation.newButton}} opens the [new recalculation form](help:recalculation-new). The button is shown to the chairman only.

### The table

| Column | What it shows |
|---|---|
| {{ui:recalculation.columns.time}} | The date and time of booking. |
| {{ui:recalculation.columns.cause}} | The cause: "{{ui:recalculation.causeKind.MANUAL}}", "{{ui:recalculation.causeKind.TARIFF_VERSION}}" or "{{ui:recalculation.causeKind.READING}}", and what exactly happened (a tariff version added, corrected or deleted; a reading entered, corrected or deleted). |
| {{ui:recalculation.columns.author}} | Who booked it; "{{ui:recalculation.system}}" if the system made the recalculation. |
| {{ui:recalculation.columns.reason}} | The reason given by the person. |
| {{ui:recalculation.columns.total}} | The difference in the amount and the number of plots. |
| {{ui:recalculation.columns.actions}} | The button "{{ui:recalculation.open}}": opens the [recalculation card](help:recalculation-card). |

The list is split into pages; at the bottom are the buttons "{{ui:pagination.previous}}" and "{{ui:pagination.next}}".

### A hint on other screens

Screens that change data affecting past charges (a plot's area, the composition of a tariff, a water connection, moving a meter in the tree) recalculate nothing themselves. They remind you: "{{ui:recalculation.hint.text}}" Only those who can recalculate see the hint.

## Worked example

The demo has one booked recalculation: plot 2, the membership fee rate was 60 ₴ per sotka per year instead of 50.

1. Open "{{ui:billing.tabs.recalculations}}".
2. Find the only row and look at its cause, reason and difference.
3. Click "{{ui:recalculation.open}}" to see exactly what changed: see the [Recalculation card](help:recalculation-card).

A plot of 6 sotkas: the old fee is 60 × 6 / 12 = 30.00 ₴ per month, the new one is 50 × 6 / 12 = 25.00 ₴, the difference is −5.00 ₴ for each month.

## Screenshots

![The recalculations log](shot:recalculations)

## Related pages

- [New recalculation](help:recalculation-new)
- [Recalculation card](help:recalculation-card)
- [Charges: months](help:charges)
- [How the system calculates a recalculation](help:how-recalculation)
