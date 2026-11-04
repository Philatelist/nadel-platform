---
id: readings-approvals
title: "Readings for approval"
description: "Readings submitted by plot owners: how to approve or reject a reading with a reason, what the owner sees and when a reading enters calculations."
kind: screen
routes:
  - /readings/approvals
permissions:
  - READINGS_APPROVE
section: meters
order: 50
shots:
  - name: readings-approvals
    user: chairman
    path: /readings/approvals
---

## What the screen is for

A cooperative member can submit a reading of their own meter from their account. Such a reading waits here. The chairman approves it or rejects it with a reason that the owner will see. Until a reading is approved, it is not used in calculations.

## Who sees it

The screen is seen by the one who has the "{{ui:permission.READINGS_APPROVE.name}}" permission: by default the chairman. A member submits a reading from their account, with the button "{{ui:readings.mine.submit}}" in the block "{{ui:readings.mine.title}}": see [How to submit a meter reading](help:member-readings).

## Every element

The heading is "{{ui:readings.approvals.title}}". If there is nothing to wait for: "{{ui:readings.approvals.empty}}".

### A reading card

Each submitted reading is a card:

- the plot ("{{ui:readings.plotNumber}}"), the resource and the serial number of the meter; the serial number leads to the [meter card](help:meter-card);
- "{{ui:readings.approvals.submittedValue}}": what the owner submitted;
- "{{ui:readings.approvals.wouldBe}}": what the consumption will be if approved;
- warnings, for example "{{ui:readings.warnings.UNUSUAL_CONSUMPTION}}".

If the reading does not fit between the accepted readings, the card says: "{{ui:readings.approvals.noInterval}}". Then it cannot be approved without a correction, so reject it with a reason.

### Buttons

| Button | What it does |
|---|---|
| {{ui:readings.approvals.approve}} | Approves the reading: it becomes accepted, and a charge for the interval from the previous reading appears at once. |
| {{ui:readings.approvals.reject}} | Opens the dialog "{{ui:readings.approvals.rejectTitle}}". |

In the rejection dialog the field "{{ui:readings.approvals.rejectReason}}" is required, up to 500 characters. The hint: "{{ui:readings.approvals.rejectHint}}". Without a reason you get "{{ui:readings.approvals.rejectRequired}}". The button "{{ui:readings.approvals.rejectSubmit}}" rejects the reading. The owner sees the reason, and in the meter history the reading has the state "{{ui:readings.history.status.REJECTED}}".

### Errors

| What is shown | What to do |
|---|---|
| {{ui:readings.errors.READING_OUT_OF_ORDER}} | The reading does not fit the sequence. Reject it with a reason. |
| {{ui:readings.usedNeedsChairman}} | The reading is already used. Seen by someone who cannot recalculate: contact the chairman. |

> [!important]
> This screen has no recalculation step. If a reading has already been approved and charged, correct it on the [meter card](help:meter-card): the preview step is there.

## Worked example

1. A plot owner submitted a meter reading from their account.
2. The chairman opens "{{ui:nav.approvals}}" and sees a card: "Reading 205.7 kWh on 04.10", under it "After approval: 93.3 kWh over 12 days" (the previous reading is 112.4 of 22.09).
3. Everything looks plausible, so the chairman clicks "{{ui:readings.approvals.approve}}". The consumption of 93.3 kWh × 4.32 = 403.06 ₴ goes into the charges.
4. If the value were clearly wrong, the chairman would click "{{ui:readings.approvals.reject}}", give the reason "Check the last digit" and confirm. The owner will see the reason and submit the reading again.

## Screenshots

![Readings for approval: approve or reject](shot:readings-approvals)

## Related pages

- [Readings: the electrician's round](help:readings-round)
- [Meter card](help:meter-card)
- [How to submit a meter reading](help:member-readings)
- [How the system calculates readings and consumption](help:how-readings-consumption)
