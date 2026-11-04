---
id: how-readings-consumption
title: "Readings and consumption"
description: "What a reading is, its statuses, how the system finds consumption for a period and which checks it makes."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 40
shots: []
---

**A reading is a meter's value on a date**, not "the reading for a month". The electrician comes when they can: after a few days or after a few months. The person enters the date; it defaults to today, with an unobtrusive hint.

## Reading statuses

| Status | Meaning | Counted |
|---|---|---|
| Approved | Entered by the electrician or the board, imported, or a member's reading approved | yes |
| Pending | Submitted by a member, waits for approval | no |
| Rejected | With a reason the owner sees | no |
| Superseded | Replaced by a new reading for the same date or a correction; kept in the history | no |

## Intervals and the value on a date

- **Interval consumption** is the difference between a reading and the previous reading of the same meter.
- **The value on any date**: the real reading if there is one on that date; otherwise a linear interpolation by days between the neighbouring readings, marked {{ui:meterTree.calculated}}; with no reading after the date yet, unknown.
- **Consumption for a range "from … to …"** = the value at the end − the value at the start.

Real readings (green) and values calculated at the range boundaries (dashed)

> **Where 1,150 comes from**
>
> Between 01.03 (1,000) and 01.04 (1,310) there are 31 days and 310 kWh. 16.03 is day 15: 1,000 + 310 × 15 / 31 = 1,150. Calculated values are always marked so that nobody takes them for a real reading.

## Checks

- A reading lower than the previous one (or higher than the next one, when entered back-dated) is refused. A lower value is valid only as the initial reading of a new meter.
- A date in the future or outside the meter's working period is refused.
- Daily consumption three times the average of the last 180 days: the reading is accepted with the warning "Check the reading".
- A reading can be corrected or deleted while no charge uses it; after that, through recalculation.
