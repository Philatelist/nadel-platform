---
id: how-meters-tree
title: "Meters and the tree"
description: "How a meter, the meter tree and a meter replacement work, with a worked consumption example."
kind: topic
routes: []
permissions:
  - METERS_VIEW
section: how-it-calculates
order: 30
shots: []
---

A meter measures one resource: electricity (kWh) or water (m³). Meter values are not money: up to three decimals.

The meter tree: substation → quarters → plots; common consumers are nodes of their own. There may be any number of substations, quarters and wells.

- The chairman builds the tree; nothing about it is hard-coded. Moving a meter under another node takes effect from a date; the tree's history is kept, so a past range is calculated with the tree as it was then.
- Any number of substations, quarter meters and common consumers (wells included); every quarter meter and every well has its own list of plots.
- A plot has at most one active meter per resource.

## Replacing a meter

A replacement is one action: the final reading of the old meter, the serial number and the initial reading of the new one. Consumption of a period with a replacement is the sum over both meters.

> **Example**
>
> Old meter: 8,140 at the start, removed at 8,205. New one: installed at 3, reads 69 at the end. Consumption: (8,205 − 8,140) + (69 − 3) = 65 + 66 = **131 kWh**.

A counter wrapping past zero (99,999 → 00,012) is "a replacement of the meter by itself": same serial number, final 99,999, initial 0. The same rule then applies.
