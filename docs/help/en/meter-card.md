---
id: meter-card
title: "Meter card"
description: "A meter with its reading history: editing, replacing, rolling over zero, removing, deleting and correcting readings."
kind: screen
routes:
  - /meters/:id
permissions:
  - METERS_VIEW
section: meters
order: 20
shots:
  - name: meter-card
    user: chairman
    path: /meters
    open: { css: 'main a[href^="/meters/"]', text: 'ЕЛ-9/2' }
  - name: reading-recalculation-step
    user: chairman
    path: /meters
    open: { css: 'main a[href^="/meters/"]', text: 'ЕЛ-9/2' }
    steps:
      - { click: Correct }
      - { fill: { label: 'Reading, kWh', value: '195' } }
      - { click: Save the correction }
    element: { css: 'dialog[open]' }
---

## What the screen is for

This is one meter: its data, the link to the previous or next meter in case of a replacement, and the history of all readings. Here the chairman edits, replaces, removes and deletes the meter and corrects readings.

## Who sees it

The chairman and the treasurer see the card (the "{{ui:permission.METERS_VIEW.name}}" permission). The buttons for changing a meter belong to the one who also has "{{ui:permission.METERS_MANAGE.name}}", that is, the chairman. A reading can be corrected or deleted with the "{{ui:permission.READINGS_ENTER.name}}" permission. But if the reading has already been charged, only the one who can recalculate ("{{ui:permission.RECALCULATION_RUN.name}}") does it. An electrician without that permission sees: "{{ui:readings.usedNeedsChairman}}", and nothing changes.

## Every element

At the top are the link "{{ui:meters.back}}", the title "Meter …" and the mark "{{ui:meters.active}}" or "{{ui:meters.removed}}".

### The meter data

Kind, resource, plot (a link to the plot card), serial number, name, installation date, initial reading, and for a removed meter the removal date and the final reading. Also the last reading and the note.

If the meter was replaced, the link is shown: "{{ui:meters.chainReplaced}}" (on the new one) and "{{ui:meters.chainReplacedBy}}" (on the old one). Both meters keep their history.

### Buttons

| Button | What it does |
|---|---|
| {{ui:meters.edit}} | The dialog "{{ui:meters.editTitle}}": change the serial number, the name, the installation date or the note. |
| {{ui:meters.replace}} | The dialog "{{ui:meters.replaceTitle}}", see below. Working meters only. |
| {{ui:meters.remove}} | The dialog "{{ui:meters.removeTitle}}": the removal date and the final reading. The meter stops working from that date, the readings stay. |
| {{ui:meters.delete}} | The question "{{ui:meters.deleteTitle}}". Only a meter without readings, created by mistake, can be deleted. This cannot be undone. |

### Replacing a meter

One action: the replacement date, the final reading of the old meter, the serial number and the initial reading of the new one. The old meter is removed on that day, and the new one is installed the same day. Fields: {{ui:meters.replacedOn}}, {{ui:meters.oldFinalReading}}, {{ui:meters.newSerial}}, {{ui:meters.newInitialReading}}. The button "{{ui:meters.replaceSubmit}}". Without a serial number: "{{ui:meters.serialRequired}}".

Did the meter roll over zero (for example, from 99 999 to 00 012)? This is entered as replacing the meter with itself. The button "{{ui:meters.wrapAroundAction}}" fills in the same serial number, the final reading before the rollover and 0 as the initial reading. The hint in the dialog: "{{ui:meters.wrapAroundHint}}"

### The block "{{ui:meters.historyTitle}}"

The table of readings: {{ui:readings.history.columns.date}}, {{ui:readings.history.columns.value}}, {{ui:readings.history.columns.status}}, {{ui:readings.history.columns.source}}, consumption for the interval and actions. A reading's state: "{{ui:readings.history.status.APPROVED}}", "{{ui:readings.history.status.PENDING}}", "{{ui:readings.history.status.REJECTED}}" (with the rejection reason) or "{{ui:readings.history.status.SUPERSEDED}}". The source: "{{ui:readings.history.source.ENTERED}}", "{{ui:readings.history.source.IMPORTED}}" or "{{ui:readings.history.source.MEMBER}}". A reading for the same date replaces the previous one; the old one stays in the history marked "replaced". An empty history: "{{ui:readings.history.empty}}".

Each reading has actions:

- "{{ui:readings.history.correct}}" opens the dialog "{{ui:readings.history.correctTitle}}", the button "{{ui:readings.history.correctSubmit}}";
- "{{ui:readings.history.delete}}" asks for confirmation "{{ui:readings.history.deleteTitle}}": the reading is deleted from the history, and the consumption for the intervals is recalculated.

While a reading has no charge, correcting it is simple. After a charge:

- the chairman sees the preview step "{{ui:readings.recalc.title}}" with a reason: what exactly will change in the charges, and after booking "{{ui:readings.recalc.corrected}}";
- an electrician without the right to recalculate sees "{{ui:readings.usedNeedsChairman}}".

Reading errors: "{{ui:readings.errors.READING_OUT_OF_ORDER}}", "{{ui:readings.errors.READING_DATE_IN_FUTURE}}", "{{ui:readings.errors.READING_DATE_OUTSIDE_METER}}", "{{ui:readings.errors.METER_VALUE_INVALID}}".

## Worked example

Plot 9 in the demo: the old meter ЕЛ-9 was removed 30 days ago with a final reading of 3,340, and the new ЕЛ-9/2 was installed the same day from zero.

1. Open ЕЛ-9/2 from the [register](help:meters). In the data block you see "Replaced meter ЕЛ-9…".
2. In the history find a reading and click "{{ui:readings.history.correct}}". Change the value to 195 and click "{{ui:readings.history.correctSubmit}}".
3. Both readings of this meter are already charged, so the recalculation preview step appears. Nothing is saved until you enter a reason and click "{{ui:recalculation.step.book}}".

A replacement gives two electricity charges: 65 kWh × 4.32 = 280.80 ₴ and 66 kWh × 4.32 = 285.12 ₴.

## Screenshots

![The meter card with the reading history](shot:meter-card)

![The preview step after correcting a charged reading](shot:reading-recalculation-step)

## Related pages

- [Meters](help:meters)
- [Readings: the electrician's round](help:readings-round)
- [Readings for approval](help:readings-approvals)
- [New recalculation](help:recalculation-new)
- [How the system calculates readings and consumption](help:how-readings-consumption)
