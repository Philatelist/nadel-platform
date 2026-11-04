---
id: readings-round
title: "Readings: the electrician's round"
description: "How the electrician enters meter readings during a round: the round date, filters, saving each reading separately and warnings."
kind: screen
routes:
  - /readings
permissions:
  - READINGS_ENTER
section: meters
order: 40
shots:
  - name: readings-round
    user: electrician
    path: /readings
  - name: readings-round-phone
    user: electrician
    path: /readings
    viewport: phone
---

## What the screen is for

Here meter readings are entered. Readings are not tied to months: the electrician may come in a month, in three or in a few days. A reading is the meter's value on a date, and the system calculates consumption between neighbouring readings. The screen is convenient on a phone: a card per plot, with a row for each meter.

## Who sees it

The screen is seen by the one who has the "{{ui:permission.READINGS_ENTER.name}}" permission: the chairman and the electrician (the special role "Electrician"). The treasurer does not see this screen. If a reading has already been charged, only the one who also has "{{ui:permission.RECALCULATION_RUN.name}}", that is, the chairman, can correct it.

## Every element

### Filters at the top

| Element | What it does |
|---|---|
| {{ui:readings.roundDate}} | The date for all readings of the round. It already holds today's date, with the hint under the field: "{{ui:readings.dateHint}}". For a single meter the date can be changed in its row. A future date cannot be entered. |
| {{ui:meters.filterResource}} | "{{ui:meters.allResources}}", "{{ui:meters.resource.ELECTRICITY}}" or "{{ui:meters.resource.WATER}}". The choice is remembered in this browser. |
| {{ui:meters.filterStreet}} | Search by street. |
| {{ui:readings.noReadingSince}} | Shows the meters that have not been read for a long time. After switching it on, the field "{{ui:readings.noReadingSinceDate}}" appears. |

If nothing matches: "{{ui:readings.nothingFound}}", and if there are no meters for the round at all: "{{ui:readings.empty}}".

### Cards and rows

Each card is a plot with its meters ("{{ui:readings.plotNumber}}") or a single non-plot meter. In a meter's row:

- the resource icon, the serial number (a link to the [meter card](help:meter-card)) and the mark "{{ui:readings.saved}}" after saving;
- "{{ui:readings.lastReading}}": the last reading and its date;
- the "Reading" field with the unit (kWh or m³);
- the field "{{ui:readings.rowDate}}";
- the button "{{ui:readings.save}}" saves this row's reading; it says "{{ui:readings.saving}}" while saving.

After saving, the consumption for the interval appears at once: "{{ui:readings.interval}}". The Enter key in a saved row moves the cursor to the next card.

### Warnings and errors

| What is shown | What to do |
|---|---|
| {{ui:readings.warnings.UNUSUAL_CONSUMPTION}} | The reading is saved, but the consumption is much higher than usual. Check the digits; if wrong, correct the reading on the meter card. |
| {{ui:readings.errors.READING_OUT_OF_ORDER}} | The reading is not accepted. Check the value: it cannot be less than the previous reading or more than the next one. |
| {{ui:readings.errors.READING_DATE_IN_FUTURE}} | Choose a date no later than today. |
| {{ui:readings.errors.READING_DATE_OUTSIDE_METER}} | The date is outside the period the meter worked. |
| {{ui:readings.errors.METER_VALUE_INVALID}} | Correct the number. |
| {{ui:readings.superseded}} | Not an error: a reading for the same date replaces the previous one, and the old one stays in the history marked "replaced". |
| {{ui:readings.usedNeedsChairman}} | The reading has already been used in a charge. Ask the chairman to correct it. |

> [!important]
> This screen does not take photos. But if you attach photos from a phone elsewhere (for example, a receipt for an expense), note that HEIC photos from an iPhone are accepted in Safari only. In another browser on the iPhone open "Settings → Camera → Formats" and choose "Most Compatible", then photos will be saved as JPEG.

### Water and electricity

For water, each plot pays only for the days it is connected to the well. A tariff per cubic metre also needs a water meter. The connection is entered on the [plot card](help:plot-card).

## Worked example

1. Open "{{ui:nav.readings}}". The round date is already today.
2. Choose the resource "{{ui:meters.resource.ELECTRICITY}}" and, if needed, a street.
3. On the card of plot 7 you see the last reading and its date. Enter the new reading, for example 205.7, and click "{{ui:readings.save}}" in this row.
4. If the previous reading was 112.4 of 22.09 and the new one is 205.7 of 04.10, the consumption of 93.3 kWh over 12 days (23.09–04.10) appears under the row. The electricity charge: 93.3 × 4.32 = 403.06 ₴.
5. If the tariff changed inside the interval, the system splits the kWh by days. Readings 1,000 (01.10) and 1,300 (31.10), tariff 4.32 until 16.10 and 5.00 from 17.10: 15 days each, 150 × 4.32 + 150 × 5.00 = 648.00 + 750.00 = 1,398.00 ₴.

A reading lower than the previous one is not accepted by the system: "{{ui:readings.errors.READING_OUT_OF_ORDER}}".

## Screenshots

![The Readings screen: a card per plot, a row per meter](shot:readings-round)

![The same screen on a phone](shot:readings-round-phone)

## Related pages

- [Meters](help:meters)
- [Meter card](help:meter-card)
- [Readings for approval](help:readings-approvals)
- [How the system calculates readings and consumption](help:how-readings-consumption)
- [Water and connection](help:how-water-connection)
