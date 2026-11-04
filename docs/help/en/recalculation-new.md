---
id: recalculation-new
title: "New recalculation"
description: "How to run a recalculation of past charges: choosing months, types and plots, the preview of changes, the reason and booking."
kind: screen
routes:
  - /charges/recalculations/new
permissions:
  - RECALCULATION_RUN
section: tariffs-charges
order: 110
shots:
  - name: recalculation-new
    user: chairman
    path: /charges/recalculations/new
  - name: recalculation-preview-empty
    user: chairman
    path: /charges/recalculations/new
    steps:
      - { click: Preview }
---

## What the screen is for

Here the chairman recalculates past charges when the data they depend on has changed: a tariff rate, a meter reading, a plot's area, the composition of a tariff, a water connection or the meter tree. The system compares what was charged with what should be by the current data and shows the difference. Nothing is written until you enter a reason and book.

## Who sees it

The screen is seen by the one who has the "{{ui:permission.RECALCULATION_RUN.name}}" permission: by default the chairman. The treasurer sees the [log](help:recalculations) but cannot recalculate.

An electrician who can enter readings but cannot recalculate, when correcting an already charged reading, sees: "{{ui:readings.usedNeedsChairman}}". Nothing changes.

## Every element

At the top are the link "{{ui:recalculation.form.back}}", the heading "{{ui:recalculation.form.title}}" and an explanation.

### Step 1: what to recalculate

| Field | What to enter |
|---|---|
| {{ui:recalculation.form.from}} | The first month. No later than the current one. |
| {{ui:recalculation.form.to}} | The last month. No more than 24 months from the first. |
| {{ui:recalculation.form.chargeTypes}} | "{{ui:recalculation.form.chargeTypesAll}}" or "{{ui:recalculation.form.chargeTypesChosen}}" with ticks on the types. At least one type: "{{ui:recalculation.form.chargeTypesNone}}". |
| {{ui:recalculation.form.plots}} | "{{ui:recalculation.form.plotsAll}}" or "{{ui:recalculation.form.plotsChosen}}": find a plot by number ("{{ui:recalculation.form.plotsSearch}}") and add it. The button next to a number removes the plot. At least one: "{{ui:recalculation.form.plotsNone}}". |

The button "{{ui:recalculation.form.preview}}" calculates what will change; it writes nothing. Form errors: "{{ui:recalculation.form.monthsRequired}}", "{{ui:recalculation.form.monthsOrder}}", "{{ui:recalculation.form.monthInFuture}}", "{{ui:problem.RECALCULATION_RANGE_TOO_LONG}}".

### Step 2: what will change

The block "{{ui:recalculation.preview.title}}" shows the scope (the months and the number of plots with changes), the difference by type ("{{ui:recalculation.preview.totals}}") and the rows "was charged → should be → difference". Next to each row is the reason for the change, for example "{{ui:recalculation.changeReason.TARIFF_VERSION}}" or "{{ui:recalculation.changeReason.AREA}}". The action for a row:

| Action | When |
|---|---|
| {{ui:recalculation.action.REVERSE_AND_RECHARGE}} | The month is open: the old charge is reversed, a new one is created. |
| An adjustment in an open month | The month is closed: nothing changes in it, and an adjustment for the difference, referring to the original month, appears in the nearest open month. |
| {{ui:recalculation.action.NEW}} | It was not charged before. |
| {{ui:recalculation.action.REVERSE}} | The charge no longer belongs. |

Then:

- the field "{{ui:recalculation.preview.reason}}": required, up to 500 characters, with the counter "{{ui:recalculation.preview.reasonCounter}}"; the hint "{{ui:recalculation.preview.reasonHint}}"; without a reason: "{{ui:recalculation.preview.reasonRequired}}";
- the button "{{ui:recalculation.step.book}}" books exactly the preview you saw;
- the button "{{ui:recalculation.step.back}}" goes back to the selection.

If charges changed meanwhile, the preview is refreshed and says "{{ui:recalculation.step.outdated}}": confirm again. When the charges already match the data, the preview says "{{ui:recalculation.preview.noChanges}}", and there is nothing to book.

> [!important]
> A closed month is never changed. The correction lands in the nearest open month as an adjustment. If a debt decreased and has already been paid, the excess becomes a prepayment, not a "negative debt".

### Other ways into a recalculation

The same preview step opens without this form when you:

- correct or delete a tariff version that is already used, or add a new version with a past date: on the [tariff card](help:tariff-card);
- correct or delete an already charged reading: on the [meter card](help:meter-card), the heading "{{ui:readings.recalc.title}}".

In "{{ui:nav.approvals}}" there is no recalculation step. If a reading has already been approved and charged, correct it on the meter card.

## Worked example

From 1 September 60 ₴/sotka/year was entered by mistake instead of 50, the plot has 6 sotkas. September is closed, October is open.

| | Was | After the correction |
|---|---|---|
| September (closed) | 30.00 | Does not change; in October an adjustment of −5.00 "for September" |
| October (open) | 30.00 | A reversal of −30.00 and a new charge of 25.00 |
| Total | 60.00 | 50.00; if 60.00 was paid, a prepayment of 10.00 |

Steps: choose the months September and October, the type "Membership fee", plot 2, click "{{ui:recalculation.form.preview}}", check the rows, enter the reason "Wrong rate" and click "{{ui:recalculation.step.book}}". The demo is already consistent, so the preview of all plots of the open month says "{{ui:recalculation.preview.noChanges}}".

## Screenshots

![The new recalculation form](shot:recalculation-new)

![The preview when there are no changes](shot:recalculation-preview-empty)

## Related pages

- [Recalculations: the log](help:recalculations)
- [Recalculation card](help:recalculation-card)
- [Tariff card](help:tariff-card)
- [Meter card](help:meter-card)
- [How the system calculates a recalculation](help:how-recalculation)
